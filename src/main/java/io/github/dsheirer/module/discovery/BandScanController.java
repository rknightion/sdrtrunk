/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.discovery;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a full band-scan operation: spectral survey → probing → discovery model population.
 *
 * <h3>Scan lifecycle</h3>
 * <ol>
 *   <li>{@link #startScan(ScanRequest)} transitions state to {@code SURVEYING} and submits
 *       work to the executor.</li>
 *   <li>The survey calls {@link SpectralSurveyApi#survey} to get energy peaks, populating
 *       {@link Discovery} rows in the {@link DiscoveryModel} as {@code ENERGY_DETECTED}.</li>
 *   <li>Peaks that overlap an ignore-range are silently dropped; peaks that overlap an already
 *       configured channel are added as {@code KNOWN} and skipped for probing.</li>
 *   <li>State transitions to {@code PROBING}; peaks are classified sequentially (one at a time,
 *       matching the default {@code maxConcurrentClassifications == 1}).</li>
 *   <li>On completion: {@code DONE}.  If the scan is continuous the controller waits
 *       {@code continuousInterval} then re-surveys, updating existing rows rather than duplicating.</li>
 * </ol>
 *
 * <h3>Operator actions</h3>
 * <p>{@link #addAsChannel}, {@link #addAllAtLeast}, {@link #ignore}, {@link #setWatched},
 * and {@link #reprobe} may be called from any thread (including the JavaFX thread in Phase 4).
 * They interact with {@link ChannelModel} and {@link ChannelProcessingManager} directly on the
 * calling thread, mirroring the pattern in {@code ClickToTuneController}.</p>
 *
 * <h3>Threading</h3>
 * <p>The scan body runs on the shared discovery {@link ExecutorService}.
 * {@link DiscoveryModel} mutations happen on that background thread.
 * Phase 4 must marshal them to the JavaFX Application Thread before binding a UI.</p>
 */
public class BandScanController
{
    private static final Logger mLog = LoggerFactory.getLogger(BandScanController.class);

    private final Classifier mClassifier;
    private final SpectralSurveyApi mSpectralSurvey;
    private final DiscoveryModel mDiscoveryModel;
    private final ChannelModel mChannelModel;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final DiscoveryChannelFactory mDiscoveryChannelFactory;
    private final UserPreferences mUserPreferences;
    private final ExecutorService mExecutor;

    /** Internal scheduler for continuous re-survey delays. One thread is sufficient. */
    private final ScheduledExecutorService mScheduler;

    private final ObjectProperty<ScanState> mScanState = new SimpleObjectProperty<>(ScanState.IDLE);
    private final DoubleProperty mProgress = new SimpleDoubleProperty(0.0);

    /** The currently running scan future (null when idle). */
    private final AtomicReference<Future<?>> mActiveScanFuture = new AtomicReference<>();

    /** The currently running classification future (null when not probing). */
    private final AtomicReference<CompletableFuture<ClassificationResult>> mActiveClassifyFuture =
        new AtomicReference<>();

    /** Set when shutdown() has been called; no further startScan calls are accepted. */
    private final AtomicBoolean mShutdown = new AtomicBoolean(false);

    /** Cancellation flag checked by the running scan body. */
    private final AtomicBoolean mCancelled = new AtomicBoolean(false);

    /** Scheduled continuous re-survey future; cancelled by stop(). */
    private final AtomicReference<ScheduledFuture<?>> mContinuousSchedule = new AtomicReference<>();

    /** The active scan request (null when idle). */
    private volatile ScanRequest mActiveScanRequest;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the controller.
     *
     * @param classifier              signal classification engine
     * @param spectralSurvey          spectral survey engine
     * @param discoveryModel          model that receives discovered signals
     * @param channelModel            model for playlist channels (read for KNOWN check; write for addAsChannel)
     * @param channelProcessingManager manager that starts/stops channels
     * @param discoveryChannelFactory factory that builds {@link Channel} instances from discoveries
     * @param userPreferences         preference bag (ignore list, thresholds, etc.)
     * @param executor                shared discovery executor
     */
    public BandScanController(Classifier classifier,
                               SpectralSurveyApi spectralSurvey,
                               DiscoveryModel discoveryModel,
                               ChannelModel channelModel,
                               ChannelProcessingManager channelProcessingManager,
                               DiscoveryChannelFactory discoveryChannelFactory,
                               UserPreferences userPreferences,
                               ExecutorService executor)
    {
        mClassifier = classifier;
        mSpectralSurvey = spectralSurvey;
        mDiscoveryModel = discoveryModel;
        mChannelModel = channelModel;
        mChannelProcessingManager = channelProcessingManager;
        mDiscoveryChannelFactory = discoveryChannelFactory;
        mUserPreferences = userPreferences;
        mExecutor = executor;

        // Single-thread scheduler for continuous delay; daemon so it does not
        // prevent JVM exit if the controller is not explicitly shutdown.
        ScheduledThreadPoolExecutor ste = new ScheduledThreadPoolExecutor(1, r ->
        {
            Thread t = new Thread(r, "band-scan-scheduler");
            t.setDaemon(true);
            return t;
        });
        ste.setRemoveOnCancelPolicy(true);
        mScheduler = ste;

        // Subscribe to channel-removal events so createdChannel can be nulled out
        mChannelModel.addListener(this::onChannelEvent);
    }

    // -------------------------------------------------------------------------
    // Observable properties
    // -------------------------------------------------------------------------

    /**
     * Observable property tracking the current scan state.
     *
     * @return state property (never null)
     */
    public ObjectProperty<ScanState> scanStateProperty()
    {
        return mScanState;
    }

    /**
     * Current scan state.
     *
     * @return state
     */
    public ScanState getScanState()
    {
        return mScanState.get();
    }

    /**
     * Observable property tracking scan progress (0.0 = start, 1.0 = survey complete).
     *
     * @return progress property
     */
    public DoubleProperty progressProperty()
    {
        return mProgress;
    }

    /**
     * Current progress fraction.
     *
     * @return 0.0..1.0
     */
    public double getProgress()
    {
        return mProgress.get();
    }

    // -------------------------------------------------------------------------
    // Scan lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts a new band scan with the given request.
     *
     * <p>If a scan is already running it is stopped first (the previous results remain in
     * the {@link DiscoveryModel} — the operator can call {@link DiscoveryModel#clear()} if
     * desired).  Does nothing if {@link #shutdown()} has been called.</p>
     *
     * @param request the scan parameters; must not be null
     */
    public void startScan(ScanRequest request)
    {
        if(request == null)
        {
            throw new IllegalArgumentException("ScanRequest must not be null");
        }

        if(mShutdown.get())
        {
            mLog.warn("BandScanController is shut down — ignoring startScan request");
            return;
        }

        // Cancel any in-progress scan
        stopInternal(false);

        mCancelled.set(false);
        mActiveScanRequest = request;
        setState(ScanState.SURVEYING);
        setProgress(0.0);

        Future<?> scanFuture = mExecutor.submit(() -> runScan(request));
        mActiveScanFuture.set(scanFuture);
    }

    /**
     * Stops the current scan, cancels any in-flight survey or classification, and sets state
     * to {@link ScanState#CANCELLED}.  Safe to call from any thread.
     * Leaves the {@link DiscoveryModel} rows intact.
     */
    public void stop()
    {
        stopInternal(true);
    }

    /**
     * Stops the current scan and refuses any future {@link #startScan} calls.
     */
    public void shutdown()
    {
        mShutdown.set(true);
        stopInternal(true);
        mScheduler.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Operator actions
    // -------------------------------------------------------------------------

    /**
     * Creates and starts a playlist channel from an IDENTIFIED discovery.
     *
     * <p>No-op if the discovery is not {@code IDENTIFIED}, has no detected decoder, or
     * already has a created channel.  On {@link ChannelException} the channel is removed
     * from the model and the error is logged.</p>
     *
     * @param discovery the discovery to add as a channel
     * @return the created channel, or {@code null} if the operation was a no-op or failed
     */
    public Channel addAsChannel(Discovery discovery)
    {
        if(discovery == null)
        {
            return null;
        }

        if(discovery.getState() != DiscoveryState.IDENTIFIED)
        {
            mLog.debug("addAsChannel: discovery not IDENTIFIED (state={})", discovery.getState());
            return null;
        }

        if(discovery.getDetectedDecoder() == null)
        {
            mLog.debug("addAsChannel: discovery has no detected decoder");
            return null;
        }

        if(discovery.getCreatedChannel() != null)
        {
            mLog.debug("addAsChannel: discovery already has a created channel");
            return discovery.getCreatedChannel();
        }

        String aliasListName = defaultAliasListName();

        Channel channel = mDiscoveryChannelFactory.createChannel(
            discovery.getCenterFrequencyHz(),
            discovery.getDetectedDecoder(),
            aliasListName);

        mChannelModel.addChannel(channel);

        try
        {
            mChannelProcessingManager.start(channel);
            discovery.setCreatedChannel(channel);
            mDiscoveryModel.update(discovery);
            mLog.info("Added channel '{}' for discovery at {} Hz", channel.getName(),
                discovery.getCenterFrequencyHz());
            return channel;
        }
        catch(ChannelException ce)
        {
            mLog.warn("Failed to start channel for discovery at {} Hz: {}",
                discovery.getCenterFrequencyHz(), ce.getMessage());
            mChannelModel.removeChannel(channel);
            return null;
        }
    }

    /**
     * Calls {@link #addAsChannel} for every IDENTIFIED discovery whose confidence is at least
     * {@code minConfidencePips} and that does not already have a created channel.
     *
     * @param minConfidencePips minimum confidence value (0..4)
     */
    public void addAllAtLeast(int minConfidencePips)
    {
        // Snapshot the list to avoid ConcurrentModificationException
        List<Discovery> snapshot = new ArrayList<>(mDiscoveryModel.getDiscoveries());

        for(Discovery d : snapshot)
        {
            if(d.getState() == DiscoveryState.IDENTIFIED
                && d.getConfidence() >= minConfidencePips
                && d.getCreatedChannel() == null)
            {
                addAsChannel(d);
            }
        }
    }

    /**
     * Marks a discovery as ignored: adds the frequency range to the user's ignore list and
     * removes the row from the {@link DiscoveryModel}.
     *
     * @param discovery the discovery to ignore
     */
    public void ignore(Discovery discovery)
    {
        if(discovery == null)
        {
            return;
        }

        long minHz = discovery.getCenterFrequencyHz() - discovery.getBandwidthHz() / 2L;
        long maxHz = discovery.getCenterFrequencyHz() + discovery.getBandwidthHz() / 2L;

        IgnoreRange range = new IgnoreRange(minHz, maxHz, "ignored from band scan", Instant.now());
        mUserPreferences.getDiscoveryPreference().addIgnoreRange(range);

        mDiscoveryModel.remove(discovery);
    }

    /**
     * Sets the watched flag on a discovery and fires an UPDATED event.
     *
     * @param discovery the discovery to update
     * @param watched   new watched state
     */
    public void setWatched(Discovery discovery, boolean watched)
    {
        if(discovery == null)
        {
            return;
        }

        discovery.setWatched(watched);
        mDiscoveryModel.update(discovery);
    }

    /**
     * Re-probes a discovery, updating its state, decoder, confidence, and metadata.
     *
     * <p>Runs on the executor; returns immediately.  The discovery is set to
     * {@code PROBING} before the async work starts.</p>
     *
     * @param discovery the discovery to re-probe
     */
    public void reprobe(Discovery discovery)
    {
        if(discovery == null)
        {
            return;
        }

        discovery.setState(DiscoveryState.PROBING);
        mDiscoveryModel.update(discovery);

        mExecutor.submit(() -> {
            ClassificationRequest req = ClassificationRequest.forFrequency(
                discovery.getCenterFrequencyHz(),
                discovery.getBandwidthHz(),
                "reprobe@" + discovery.getCenterFrequencyHz());

            CompletableFuture<ClassificationResult> future = mClassifier.classify(req);

            try
            {
                ClassificationResult result = future.get();
                applyClassificationResult(discovery, result);
                mDiscoveryModel.update(discovery);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                mLog.debug("reprobe interrupted for {} Hz", discovery.getCenterFrequencyHz());
            }
            catch(ExecutionException e)
            {
                mLog.warn("reprobe error for {} Hz: {}", discovery.getCenterFrequencyHz(),
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                discovery.setState(DiscoveryState.ERROR);
                mDiscoveryModel.update(discovery);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Core scan body (runs on executor thread)
    // -------------------------------------------------------------------------

    private void runScan(ScanRequest request)
    {
        try
        {
            doRunScan(request);
        }
        catch(Throwable t)
        {
            mLog.error("Unexpected error in band scan", t);
            setState(ScanState.ERROR);
        }
    }

    private void doRunScan(ScanRequest request)
    {
        // --- Step 1: Spectral survey ------------------------------------------
        setState(ScanState.SURVEYING);
        setProgress(0.0);

        CompletableFuture<List<EnergyPeak>> surveyFuture = mSpectralSurvey.survey(
            request.minFrequencyHz(),
            request.maxFrequencyHz(),
            request.surveyDwell(),
            request.thresholdDb(),
            fraction -> setProgress(fraction * 0.5));  // survey = first 50% of progress

        List<EnergyPeak> peaks;

        try
        {
            peaks = surveyFuture.get();
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            setState(ScanState.CANCELLED);
            return;
        }
        catch(Exception e)
        {
            if(mCancelled.get())
            {
                setState(ScanState.CANCELLED);
                return;
            }

            mLog.error("Spectral survey failed: {}", e.getMessage(), e);
            setState(ScanState.ERROR);
            return;
        }

        if(mCancelled.get())
        {
            setState(ScanState.CANCELLED);
            return;
        }

        // --- Step 2: Classify energy detected vs KNOWN, add rows -------------
        setState(ScanState.PROBING);

        List<Discovery> toProbeLater = new ArrayList<>();

        for(EnergyPeak peak : peaks)
        {
            if(mCancelled.get())
            {
                setState(ScanState.CANCELLED);
                return;
            }

            // Skip peaks in the user's ignore list
            if(isIgnored(peak))
            {
                mLog.debug("Skipping ignored peak at {} Hz", peak.centerFrequencyHz());
                continue;
            }

            Discovery discovery = new Discovery(peak, Instant.now());

            // Check if an existing channel already covers this frequency
            if(isKnownChannel(peak))
            {
                discovery.setState(DiscoveryState.KNOWN);
                mDiscoveryModel.add(discovery);
            }
            else
            {
                mDiscoveryModel.add(discovery);
                toProbeLater.add(discovery);
            }
        }

        // --- Step 3: Sequential probing (one classification at a time) --------
        int probeLimit = (request.maxSignalsToProbe() > 0) ? request.maxSignalsToProbe() : Integer.MAX_VALUE;
        int probeCount = 0;
        int totalToProbe = Math.min(toProbeLater.size(), probeLimit);

        for(Discovery discovery : toProbeLater)
        {
            if(mCancelled.get())
            {
                setState(ScanState.CANCELLED);
                return;
            }

            if(probeCount >= probeLimit)
            {
                break;
            }

            probeOne(discovery);
            probeCount++;

            // Update probe progress: second half of the 0..1 range
            if(totalToProbe > 0)
            {
                double probeProgress = (double) probeCount / totalToProbe;
                setProgress(0.5 + probeProgress * 0.5);
            }
        }

        if(mCancelled.get())
        {
            setState(ScanState.CANCELLED);
            return;
        }

        setProgress(1.0);

        // --- Step 4: Finished or schedule continuous re-scan ------------------
        if(request.continuous())
        {
            setState(ScanState.IDLE_CONTINUOUS);

            ScheduledFuture<?> scheduled = mScheduler.schedule(
                () -> {
                    if(!mCancelled.get() && !mShutdown.get())
                    {
                        runContinuousRescan(request);
                    }
                },
                request.continuousInterval().toMillis(),
                TimeUnit.MILLISECONDS);

            mContinuousSchedule.set(scheduled);
        }
        else
        {
            setState(ScanState.DONE);
        }
    }

    /**
     * Probes a single discovery synchronously (called on the executor thread).
     */
    private void probeOne(Discovery discovery)
    {
        discovery.setState(DiscoveryState.PROBING);
        mDiscoveryModel.update(discovery);

        ClassificationRequest req = ClassificationRequest.forFrequency(
            discovery.getCenterFrequencyHz(),
            discovery.getBandwidthHz(),
            "scan@" + discovery.getCenterFrequencyHz());

        CompletableFuture<ClassificationResult> future = mClassifier.classify(req);
        mActiveClassifyFuture.set(future);

        try
        {
            ClassificationResult result = future.get();
            applyClassificationResult(discovery, result);
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            mCancelled.set(true);
        }
        catch(ExecutionException e)
        {
            mLog.warn("Classification error for {} Hz: {}", discovery.getCenterFrequencyHz(),
                e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            discovery.setState(DiscoveryState.ERROR);
        }
        finally
        {
            mActiveClassifyFuture.compareAndSet(future, null);
        }

        mDiscoveryModel.update(discovery);
    }

    /**
     * Applies a {@link ClassificationResult} to a {@link Discovery}, updating all relevant fields.
     */
    private void applyClassificationResult(Discovery discovery, ClassificationResult result)
    {
        if(result == null)
        {
            discovery.setState(DiscoveryState.ERROR);
            return;
        }

        discovery.setLastSeen(Instant.now());

        switch(result.outcome())
        {
            case IDENTIFIED ->
            {
                discovery.setState(DiscoveryState.IDENTIFIED);
                discovery.setDetectedDecoder(result.bestDecoder());
                discovery.setKind(result.kind() != null ? result.kind() : SignalKind.UNKNOWN);
                discovery.setConfidence(computeConfidence(result));
                if(result.metadata() != null)
                {
                    discovery.setMetadata(result.metadata());
                }
            }
            case UNIDENTIFIED, NO_SIGNAL -> discovery.setState(DiscoveryState.UNIDENTIFIED);
            case ERROR -> discovery.setState(DiscoveryState.ERROR);
            case CANCELLED ->
            {
                // On cancellation, leave state as PROBING (scan was cancelled; row stays)
                // but don't overwrite a completed state if the future raced
            }
        }
    }

    /**
     * Computes a 0..4 confidence pip count from the classification result.
     * <ul>
     *   <li>LOCKED + quality &gt;= 0.75 → 4</li>
     *   <li>LOCKED + quality &lt; 0.75  → 3</li>
     *   <li>PARTIAL + quality &gt;= 0.5 → 2</li>
     *   <li>PARTIAL + quality &lt; 0.5  → 1</li>
     *   <li>NONE or ERROR              → 0 (but IDENTIFIED with no useful candidate → 2)</li>
     * </ul>
     */
    private static int computeConfidence(ClassificationResult result)
    {
        if(result.candidates() == null || result.candidates().isEmpty())
        {
            return 2; // IDENTIFIED but no candidate detail → moderate confidence
        }

        // Find the winning candidate (the one that matches bestDecoder)
        for(Candidate c : result.candidates())
        {
            if(c.decoderType() == result.bestDecoder())
            {
                return switch(c.lockState())
                {
                    case LOCKED  -> c.lockQuality() >= 0.75 ? 4 : 3;
                    case PARTIAL -> c.lockQuality() >= 0.50 ? 2 : 1;
                    default      -> 0;
                };
            }
        }

        return 2; // fallback
    }

    // -------------------------------------------------------------------------
    // Continuous re-scan
    // -------------------------------------------------------------------------

    private void runContinuousRescan(ScanRequest request)
    {
        if(mCancelled.get() || mShutdown.get())
        {
            return;
        }

        mCancelled.set(false);
        setState(ScanState.SURVEYING);
        setProgress(0.0);

        CompletableFuture<List<EnergyPeak>> surveyFuture = mSpectralSurvey.survey(
            request.minFrequencyHz(),
            request.maxFrequencyHz(),
            request.surveyDwell(),
            request.thresholdDb(),
            fraction -> setProgress(fraction * 0.5));

        List<EnergyPeak> peaks;

        try
        {
            peaks = surveyFuture.get();
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            setState(ScanState.CANCELLED);
            return;
        }
        catch(Exception e)
        {
            if(mCancelled.get())
            {
                setState(ScanState.CANCELLED);
                return;
            }

            mLog.error("Continuous re-survey failed: {}", e.getMessage(), e);
            setState(ScanState.ERROR);
            return;
        }

        if(mCancelled.get())
        {
            setState(ScanState.CANCELLED);
            return;
        }

        setState(ScanState.PROBING);

        List<Discovery> toProbeLater = new ArrayList<>();

        for(EnergyPeak peak : peaks)
        {
            if(mCancelled.get())
            {
                setState(ScanState.CANCELLED);
                return;
            }

            if(isIgnored(peak))
            {
                continue;
            }

            // Try to match an existing row by approximate frequency
            Discovery existing = findExistingDiscovery(peak);

            if(existing != null)
            {
                // Update the existing row's last-seen time and state
                existing.setLastSeen(Instant.now());

                if(existing.getState() == DiscoveryState.UNIDENTIFIED)
                {
                    if(existing.isWatched())
                    {
                        // Re-probe watched unidentified rows
                        toProbeLater.add(existing);
                    }
                    else
                    {
                        // Update state to ENERGY_DETECTED to reflect fresh energy
                        existing.setState(DiscoveryState.ENERGY_DETECTED);
                        mDiscoveryModel.update(existing);
                    }
                }
                else
                {
                    mDiscoveryModel.update(existing);
                }
            }
            else
            {
                // Brand new peak
                if(isKnownChannel(peak))
                {
                    Discovery discovery = new Discovery(peak, Instant.now());
                    discovery.setState(DiscoveryState.KNOWN);
                    mDiscoveryModel.add(discovery);
                }
                else
                {
                    Discovery discovery = new Discovery(peak, Instant.now());
                    mDiscoveryModel.add(discovery);
                    toProbeLater.add(discovery);
                }
            }
        }

        // Sequential probing for new/watched discoveries
        int probeLimit = (request.maxSignalsToProbe() > 0) ? request.maxSignalsToProbe() : Integer.MAX_VALUE;
        int probeCount = 0;

        for(Discovery discovery : toProbeLater)
        {
            if(mCancelled.get())
            {
                setState(ScanState.CANCELLED);
                return;
            }

            if(probeCount >= probeLimit)
            {
                break;
            }

            probeOne(discovery);
            probeCount++;
        }

        if(mCancelled.get())
        {
            setState(ScanState.CANCELLED);
            return;
        }

        setProgress(1.0);

        // Schedule the next cycle
        setState(ScanState.IDLE_CONTINUOUS);

        ScheduledFuture<?> scheduled = mScheduler.schedule(
            () -> {
                if(!mCancelled.get() && !mShutdown.get())
                {
                    runContinuousRescan(request);
                }
            },
            request.continuousInterval().toMillis(),
            TimeUnit.MILLISECONDS);

        mContinuousSchedule.set(scheduled);
    }

    /**
     * Finds an existing discovery that overlaps the given energy peak.
     *
     * @param peak the energy peak to match
     * @return the first overlapping discovery, or null if none
     */
    private Discovery findExistingDiscovery(EnergyPeak peak)
    {
        List<Discovery> overlapping = mDiscoveryModel.findOverlapping(
            peak.centerFrequencyHz(), peak.occupiedBandwidthHz());
        return overlapping.isEmpty() ? null : overlapping.get(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Determines whether the given peak falls within any user-configured ignore range.
     */
    private boolean isIgnored(EnergyPeak peak)
    {
        long peakMin = peak.centerFrequencyHz() - peak.occupiedBandwidthHz() / 2L;
        long peakMax = peak.centerFrequencyHz() + peak.occupiedBandwidthHz() / 2L;

        for(IgnoreRange range : mUserPreferences.getDiscoveryPreference().getIgnoreList())
        {
            if(range.overlaps(peakMin, peakMax))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Determines whether the given peak overlaps a channel already in the channel model.
     */
    private boolean isKnownChannel(EnergyPeak peak)
    {
        long peakMin = peak.centerFrequencyHz() - peak.occupiedBandwidthHz() / 2L;
        long peakMax = peak.centerFrequencyHz() + peak.occupiedBandwidthHz() / 2L;

        return !mChannelModel.getChannelsInFrequencyRange(peakMin, peakMax).isEmpty();
    }

    /**
     * Returns the first alias list name from the channel model, or null if none exist.
     */
    private String defaultAliasListName()
    {
        List<String> names = mChannelModel.getAliasListNames();
        return (names != null && !names.isEmpty()) ? names.get(0) : null;
    }

    /**
     * Sets the scan state property.  Called from background threads.
     */
    private void setState(ScanState state)
    {
        mScanState.set(state);
    }

    /**
     * Sets the progress property.  Called from background threads.
     */
    private void setProgress(double value)
    {
        mProgress.set(value);
    }

    /**
     * Internal stop logic.
     *
     * @param setStateCancelled whether to set state to CANCELLED after stopping
     */
    private void stopInternal(boolean setStateCancelled)
    {
        mCancelled.set(true);

        // Cancel the continuous schedule
        ScheduledFuture<?> schedule = mContinuousSchedule.getAndSet(null);
        if(schedule != null)
        {
            schedule.cancel(false);
        }

        // Cancel any in-flight classification
        CompletableFuture<ClassificationResult> classifyFuture = mActiveClassifyFuture.getAndSet(null);
        if(classifyFuture != null)
        {
            classifyFuture.cancel(true);
        }

        // Cancel the scan task
        Future<?> scanFuture = mActiveScanFuture.getAndSet(null);
        if(scanFuture != null)
        {
            scanFuture.cancel(true);
        }

        if(setStateCancelled)
        {
            setState(ScanState.CANCELLED);
        }
    }

    /**
     * Receives channel events from the model.  When a channel created by this controller
     * is deleted, the corresponding discovery's {@code createdChannel} field is nulled so
     * the operator can add it again.
     */
    private void onChannelEvent(ChannelEvent event)
    {
        if(event.getEvent() != ChannelEvent.Event.NOTIFICATION_DELETE)
        {
            return;
        }

        Channel deleted = event.getChannel();

        // Scan all discoveries for a matching createdChannel reference
        for(Discovery d : mDiscoveryModel.getDiscoveries())
        {
            if(deleted.equals(d.getCreatedChannel()))
            {
                d.setCreatedChannel(null);
                mDiscoveryModel.update(d);
                break;
            }
        }
    }

    /**
     * Extracts the center frequency from a channel's source configuration.
     *
     * @param channel the channel
     * @return frequency in Hz, or -1 if not determinable
     */
    private static long channelFrequency(Channel channel)
    {
        if(channel.getSourceConfiguration() instanceof SourceConfigTuner sc)
        {
            return sc.getFrequency();
        }

        return -1L;
    }
}
