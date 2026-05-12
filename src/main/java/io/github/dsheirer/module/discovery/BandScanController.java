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
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.FxThreads;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
 *       work to the executor.  If a scan is already running it is stopped first; the stop
 *       is synchronised via an epoch counter (see §Thread safety below) so the old scan
 *       cannot clobber the new one.</li>
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
 * <h3>Thread safety — epoch / generation counter</h3>
 * <p>A monotonically-increasing epoch ({@link AtomicInteger}) guards against stale scan
 * iterations mutating the model after a new scan has started.  Every scan / rescan captures its
 * epoch at start ({@code myEpoch}) and checks {@code myEpoch == mCurrentEpoch.get()} at every
 * loop iteration and before each model mutation.  {@link #stopInternal} and
 * {@link #startScan} bump the epoch, making all in-flight work detect "stale" and exit cleanly
 * without touching the model.  This replaces the previous shared {@code mCancelled} boolean
 * (kept for legacy compatibility but only set/cleared in tandem with epoch bumps).</p>
 *
 * <h3>DiscoveryModel thread safety</h3>
 * <p>As of Phase 3 {@link DiscoveryModel} marshals all mutations to the JavaFX Application Thread
 * when the FX toolkit is running, and runs inline under its internal lock otherwise.  Callers
 * (including this class and Phase 4) may call any {@link DiscoveryModel} method from any thread
 * without additional marshalling.</p>
 *
 * <h3>Note on the executor</h3>
 * <p>{@code mExecutor} must be an unbounded / cached pool because the scan body submits nested
 * tasks (one per {@link #probeOne} classification) while holding the outer scan thread.
 * A fixed-size pool risks deadlock if all threads are waiting for classify futures that are
 * queued behind the waiting threads.</p>
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

    /**
     * Optional tuner control for the stepped sweep.  When non-null and available, a scan
     * whose in-band survey fails with a "span too wide" condition will automatically fall
     * back to {@link SpectralSurveyApi#surveyWide}.  Set once at construction and never
     * mutated, so no synchronisation is required.  {@code null} in headless / standalone mode.
     */
    private final TunerControl mTunerControl;

    /** Internal scheduler for continuous re-survey delays. One thread is sufficient. */
    private final ScheduledExecutorService mScheduler;

    private final ObjectProperty<ScanState> mScanState = new SimpleObjectProperty<>(ScanState.IDLE);
    private final DoubleProperty mProgress = new SimpleDoubleProperty(0.0);

    /** The currently running scan future (null when idle). */
    private final AtomicReference<Future<?>> mActiveScanFuture = new AtomicReference<>();

    /**
     * The survey future currently running (set in {@link #startScanBody} and
     * {@link #rescanBody}, cleared when the survey completes or is cancelled).
     * Cancelling this future from {@link #stopInternal} releases the survey's
     * tuner source promptly rather than waiting for the dwell to expire.
     */
    private final AtomicReference<CompletableFuture<List<EnergyPeak>>> mActiveSurveyFuture =
        new AtomicReference<>();

    /** The currently running classification future (null when not probing). */
    private final AtomicReference<CompletableFuture<ClassificationResult>> mActiveClassifyFuture =
        new AtomicReference<>();

    /** Set when shutdown() has been called; no further startScan calls are accepted. */
    private final AtomicBoolean mShutdown = new AtomicBoolean(false);

    /**
     * Monotonically-increasing generation counter.  Bumped by {@link #stopInternal} and
     * {@link #startScan} so in-flight scan bodies detect stale state and exit.
     */
    private final AtomicInteger mCurrentEpoch = new AtomicInteger(0);

    /** Scheduled continuous re-survey future; cancelled by stop(). */
    private final AtomicReference<ScheduledFuture<?>> mContinuousSchedule = new AtomicReference<>();

    /** The active scan request (null when idle). */
    private volatile ScanRequest mActiveScanRequest;

    /**
     * Descriptive message from the most recent error, or {@code null} if the last scan succeeded.
     * Set when the scan body catches a survey or classification failure.
     */
    private volatile String mLastErrorMessage;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the controller.
     *
     * @param classifier              signal classification engine
     * @param spectralSurvey          spectral survey engine
     * @param discoveryModel          model that receives discovered signals; all methods are
     *                                safe to call from any thread (thread-safety is owned by the model)
     * @param channelModel            model for playlist channels (read for KNOWN check; write for addAsChannel)
     * @param channelProcessingManager manager that starts/stops channels
     * @param discoveryChannelFactory factory that builds {@link Channel} instances from discoveries
     * @param userPreferences         preference bag (ignore list, thresholds, etc.)
     * @param executor                shared discovery executor — must be an unbounded/cached pool
     *                                because the scan body submits nested classification tasks while
     *                                blocking on the outer scan thread
     * @param tunerControl            optional tuner control for stepped sweeps; may be {@code null}
     *                                (headless / standalone mode — stepped sweep is unavailable)
     */
    public BandScanController(Classifier classifier,
                               SpectralSurveyApi spectralSurvey,
                               DiscoveryModel discoveryModel,
                               ChannelModel channelModel,
                               ChannelProcessingManager channelProcessingManager,
                               DiscoveryChannelFactory discoveryChannelFactory,
                               UserPreferences userPreferences,
                               ExecutorService executor,
                               TunerControl tunerControl)
    {
        mClassifier = classifier;
        mSpectralSurvey = spectralSurvey;
        mDiscoveryModel = discoveryModel;
        mChannelModel = channelModel;
        mChannelProcessingManager = channelProcessingManager;
        mDiscoveryChannelFactory = discoveryChannelFactory;
        mUserPreferences = userPreferences;
        mExecutor = executor;
        mTunerControl = tunerControl;

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
     * Returns the {@link DiscoveryModel} that this controller populates during a scan.
     *
     * <p>Phase 4 uses this to bind the {@link javafx.scene.control.TableView} in
     * {@link io.github.dsheirer.gui.playlist.discovery.DiscoveryEditor}.</p>
     *
     * @return the discovery model; never null
     */
    public DiscoveryModel getDiscoveryModel()
    {
        return mDiscoveryModel;
    }

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

    /**
     * Returns the descriptive error message from the most recent scan failure, or
     * {@code null} if no error has occurred (or the controller is newly constructed).
     *
     * <p>The value is reset to {@code null} when a new scan starts successfully.</p>
     *
     * @return error message, or null
     */
    public String getLastErrorMessage()
    {
        return mLastErrorMessage;
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
     * <p>Rejection policy: if the executor is shut down when the scan body is submitted,
     * the new scan is abandoned gracefully (state stays at the current value rather than
     * hanging).</p>
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

        // Apply excluded decoders from the user's discovery preferences.
        // This guards programmatic callers that bypass ScanDialog (which does not apply
        // exclusions itself) as well as the ScanRequest.defaults() factory path.
        // We compute the effective request exactly once so that the local can be captured
        // by the lambda below without a "not effectively final" compile error.
        final ScanRequest effectiveRequest = applyExclusions(request);

        // Bump the epoch to invalidate any currently running scan / rescan.
        // stopInternal sets CANCELLED; we immediately re-arm mCurrentEpoch to the new value.
        stopInternal(false);

        int myEpoch = mCurrentEpoch.incrementAndGet();

        mActiveScanRequest = effectiveRequest;
        mLastErrorMessage = null;
        // setState(SURVEYING) is deferred into runSurvey() so the survey future is
        // always stored in mActiveSurveyFuture before SURVEYING state is visible.
        setProgress(0.0);

        try
        {
            Future<?> scanFuture = mExecutor.submit(() -> runScan(effectiveRequest, myEpoch));
            mActiveScanFuture.set(scanFuture);
        }
        catch(RejectedExecutionException e)
        {
            mLog.warn("BandScanController: executor rejected scan submission — executor may be shut down");
            setState(ScanState.ERROR);
        }
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
            FxThreads.run(() -> discovery.setCreatedChannel(channel));
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

        FxThreads.run(() -> discovery.setWatched(watched));
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

        FxThreads.run(() -> discovery.setState(DiscoveryState.PROBING));
        mDiscoveryModel.update(discovery);

        try
        {
            mExecutor.submit(() -> {
                ClassificationRequest req = ClassificationRequest.forFrequency(
                    discovery.getCenterFrequencyHz(),
                    discovery.getBandwidthHz(),
                    // Use all primaries for reprobe (no scan request in scope)
                    null,
                    "reprobe@" + discovery.getCenterFrequencyHz());

                CompletableFuture<ClassificationResult> future = mClassifier.classify(req);

                try
                {
                    ClassificationResult result = future.get();
                    applyClassificationResult(discovery, result);
                    mDiscoveryModel.update(discovery);
                }
                catch(CancellationException e)
                {
                    // Cancelled (e.g. shutdown) — leave discovery in PROBING state
                    mLog.debug("reprobe cancelled for {} Hz", discovery.getCenterFrequencyHz());
                }
                catch(InterruptedException e)
                {
                    // Restore interrupted flag; leave discovery in PROBING state
                    mLog.debug("reprobe interrupted for {} Hz", discovery.getCenterFrequencyHz());
                    // Do NOT re-interrupt: this thread returns to the pool; re-setting the flag
                    // would propagate the interrupt into the pool's housekeeping.
                }
                catch(ExecutionException e)
                {
                    mLog.warn("reprobe error for {} Hz: {}", discovery.getCenterFrequencyHz(),
                        e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                    FxThreads.run(() -> discovery.setState(DiscoveryState.ERROR));
                    mDiscoveryModel.update(discovery);
                }
            });
        }
        catch(RejectedExecutionException e)
        {
            mLog.warn("BandScanController: executor rejected reprobe — executor may be shut down");
            FxThreads.run(() -> discovery.setState(DiscoveryState.ERROR));
            mDiscoveryModel.update(discovery);
        }
    }

    // -------------------------------------------------------------------------
    // Core scan body (runs on executor thread)
    // -------------------------------------------------------------------------

    private void runScan(ScanRequest request, int myEpoch)
    {
        try
        {
            startScanBody(request, myEpoch);
        }
        catch(Throwable t)
        {
            if(mCurrentEpoch.get() == myEpoch)
            {
                mLog.error("Unexpected error in band scan", t);
                setState(ScanState.ERROR);
            }
        }
    }

    /**
     * Body of a single scan pass (initial scan and continuous-rescan share the probe loop
     * via {@link #runProbeLoop}).
     */
    private void startScanBody(ScanRequest request, int myEpoch)
    {
        // --- Step 1: Spectral survey ------------------------------------------
        // NOTE: setState(SURVEYING) is deferred to inside runSurvey(), after
        // mActiveSurveyFuture is set, so that any observer of the SURVEYING state
        // can rely on the survey future already being available for cancellation.
        setProgress(0.0);

        List<EnergyPeak> peaks = runSurvey(request, myEpoch, 0.0, 0.5);

        if(peaks == null)
        {
            // Epoch stale, cancelled, or survey error — runSurvey already set state
            return;
        }

        // --- Steps 2-3: Ignore/known filter + probing -------------------------
        runProbeLoop(request, myEpoch, peaks, true);

        if(mCurrentEpoch.get() != myEpoch)
        {
            return;
        }

        setProgress(1.0);

        // --- Step 4: Finished or schedule continuous re-scan ------------------
        if(request.continuous())
        {
            setState(ScanState.IDLE_CONTINUOUS);
            scheduleRescan(request, myEpoch);
        }
        else
        {
            setState(ScanState.DONE);
        }
    }

    /**
     * Runs one continuous re-scan cycle on the executor (submitted from the scheduler so
     * the scheduler thread is never blocked by long-running work).
     */
    private void runRescan(ScanRequest request, int myEpoch)
    {
        try
        {
            rescanBody(request, myEpoch);
        }
        catch(Throwable t)
        {
            if(mCurrentEpoch.get() == myEpoch)
            {
                mLog.error("Unexpected error in continuous re-scan", t);
                setState(ScanState.ERROR);
            }
        }
    }

    private void rescanBody(ScanRequest request, int myEpoch)
    {
        if(mCurrentEpoch.get() != myEpoch || mShutdown.get())
        {
            return;
        }

        // setState(SURVEYING) is deferred to inside runSurvey() so mActiveSurveyFuture
        // is always set before the state is published (see runSurvey javadoc).
        setProgress(0.0);

        List<EnergyPeak> peaks = runSurvey(request, myEpoch, 0.0, 0.5);

        if(peaks == null)
        {
            return;
        }

        setState(ScanState.PROBING);

        List<Discovery> toProbeLater = new ArrayList<>();

        for(EnergyPeak peak : peaks)
        {
            if(mCurrentEpoch.get() != myEpoch)
            {
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
                Instant nowForExisting = Instant.now();

                if(existing.getState() == DiscoveryState.UNIDENTIFIED)
                {
                    if(existing.isWatched())
                    {
                        // Re-probe watched unidentified rows; update last-seen on FX thread
                        FxThreads.run(() -> existing.setLastSeen(nowForExisting));
                        toProbeLater.add(existing);
                    }
                    else
                    {
                        // Update state to ENERGY_DETECTED to reflect fresh energy
                        FxThreads.run(() -> {
                            existing.setLastSeen(nowForExisting);
                            existing.setState(DiscoveryState.ENERGY_DETECTED);
                        });
                        mDiscoveryModel.update(existing);
                    }
                }
                else
                {
                    FxThreads.run(() -> existing.setLastSeen(nowForExisting));
                    mDiscoveryModel.update(existing);
                }
            }
            else
            {
                // Brand new peak
                if(isKnownChannel(peak))
                {
                    Discovery discovery = new Discovery(peak, Instant.now());
                    FxThreads.run(() -> discovery.setState(DiscoveryState.KNOWN));

                    if(mCurrentEpoch.get() == myEpoch)
                    {
                        mDiscoveryModel.add(discovery);
                    }
                }
                else
                {
                    Discovery discovery = new Discovery(peak, Instant.now());

                    if(mCurrentEpoch.get() == myEpoch)
                    {
                        mDiscoveryModel.add(discovery);
                        toProbeLater.add(discovery);
                    }
                }
            }
        }

        // Also re-probe any watched+UNIDENTIFIED rows that did NOT appear in the survey
        // (the signal may be intermittent — keep trying as long as the operator watches it)
        for(Discovery d : new ArrayList<>(mDiscoveryModel.getDiscoveries()))
        {
            if(d.isWatched() && d.getState() == DiscoveryState.UNIDENTIFIED
                && !toProbeLater.contains(d))
            {
                toProbeLater.add(d);
            }
        }

        // Sequential probing for new/watched discoveries
        int probeLimit = (request.maxSignalsToProbe() > 0) ? request.maxSignalsToProbe() : Integer.MAX_VALUE;
        int probeCount = 0;

        for(Discovery discovery : toProbeLater)
        {
            if(mCurrentEpoch.get() != myEpoch)
            {
                return;
            }

            if(probeCount >= probeLimit)
            {
                break;
            }

            probeOne(discovery, request, myEpoch);
            probeCount++;
        }

        if(mCurrentEpoch.get() != myEpoch)
        {
            return;
        }

        setProgress(1.0);
        setState(ScanState.IDLE_CONTINUOUS);
        scheduleRescan(request, myEpoch);
    }

    // -------------------------------------------------------------------------
    // Shared helpers for scan body
    // -------------------------------------------------------------------------

    /**
     * Runs the spectral survey phase.  Returns the peak list on success, or {@code null} if
     * the epoch became stale, the survey was cancelled, or a fatal error occurred (in each
     * case the appropriate state has already been set).
     *
     * @param request          the active scan request
     * @param myEpoch          the epoch of the current scan pass
     * @param progressStart    the progress fraction at the start of the survey (0.0..1.0)
     * @param progressEnd      the progress fraction at the end of the survey (0.0..1.0)
     * @return the list of energy peaks, or {@code null} to signal "stop"
     */
    private List<EnergyPeak> runSurvey(ScanRequest request, int myEpoch,
                                        double progressStart, double progressEnd)
    {
        double progressRange = progressEnd - progressStart;

        // Obtain the future BEFORE publishing SURVEYING state so that any thread that
        // observes SURVEYING can rely on mActiveSurveyFuture already being set for cancellation.
        CompletableFuture<List<EnergyPeak>> surveyFuture = mSpectralSurvey.survey(
            request.minFrequencyHz(),
            request.maxFrequencyHz(),
            request.surveyDwell(),
            request.thresholdDb(),
            fraction -> setProgress(progressStart + fraction * progressRange));

        // Store before publishing state
        mActiveSurveyFuture.set(surveyFuture);

        // Now publish SURVEYING — at this point mActiveSurveyFuture is visible to stop()
        setState(ScanState.SURVEYING);

        try
        {
            List<EnergyPeak> peaks = surveyFuture.get();
            mActiveSurveyFuture.compareAndSet(surveyFuture, null);
            return peaks;
        }
        catch(CancellationException e)
        {
            // Future was cancelled (e.g. by stopInternal) — treat as cancelled scan
            mActiveSurveyFuture.compareAndSet(surveyFuture, null);

            if(mCurrentEpoch.get() == myEpoch)
            {
                setState(ScanState.CANCELLED);
            }

            return null;
        }
        catch(InterruptedException e)
        {
            mActiveSurveyFuture.compareAndSet(surveyFuture, null);

            // Do NOT re-interrupt: we let the epoch check handle cancellation, and
            // re-setting the flag would propagate into the pool's housekeeping.
            if(mCurrentEpoch.get() == myEpoch)
            {
                setState(ScanState.CANCELLED);
            }

            return null;
        }
        catch(Exception e)
        {
            mActiveSurveyFuture.compareAndSet(surveyFuture, null);

            if(mCurrentEpoch.get() != myEpoch)
            {
                // Stale — another scan is running
                return null;
            }

            // Check if in-band survey failed because the span is too wide.
            // The error message from SpectralSurvey contains a recognizable marker when
            // the source provider returned null (span exceeds tuner bandwidth).
            // If a TunerControl is available, automatically retry with the stepped sweep.
            Throwable cause = (e instanceof java.util.concurrent.ExecutionException && e.getCause() != null)
                ? e.getCause() : e;
            String msg = cause.getMessage() != null ? cause.getMessage() : "";

            if(msg.contains("stepped sweep") && mTunerControl != null && mTunerControl.isAvailable())
            {
                mLog.info("Band scan: in-band survey failed (span too wide), falling back to stepped sweep "
                    + "for {} – {} MHz",
                    request.minFrequencyHz() / 1_000_000.0,
                    request.maxFrequencyHz() / 1_000_000.0);

                return runSteppedSurvey(request, myEpoch, progressStart, progressEnd);
            }

            mLog.error("Spectral survey failed: {}", e.getMessage(), e);
            mLastErrorMessage = e.getMessage() != null ? e.getMessage()
                : (e.getCause() != null ? e.getCause().getMessage() : "Spectral survey failed");
            setState(ScanState.ERROR);
            return null;
        }
    }

    /**
     * Filters peaks (ignore-list + KNOWN channels), adds rows to the model, then probes the
     * unknowns sequentially.  Returns {@code false} if the epoch became stale (caller should
     * exit immediately); returns {@code true} if the loop completed normally.
     *
    /**
     * Executes the stepped sweep survey phase and returns the peak list, or {@code null} if the
     * epoch became stale, the sweep was cancelled, or an error occurred.
     *
     * <p>Called from {@link #runSurvey} when the in-band survey fails with a "span too wide"
     * error and a {@link TunerControl} is available.  The survey future stored in
     * {@link #mActiveSurveyFuture} is replaced with the stepped-sweep future so that
     * {@link #stopInternal} can cancel it and the restore-on-cancel path in
     * {@code SpectralSurvey.doSteppedSurvey} runs correctly.</p>
     */
    private List<EnergyPeak> runSteppedSurvey(ScanRequest request, int myEpoch,
                                               double progressStart, double progressEnd)
    {
        if(mCurrentEpoch.get() != myEpoch)
        {
            return null;
        }

        double progressRange = progressEnd - progressStart;

        CompletableFuture<List<EnergyPeak>> steppedFuture = mSpectralSurvey.surveyWide(
            request.minFrequencyHz(),
            request.maxFrequencyHz(),
            request.surveyDwell(),
            request.thresholdDb(),
            mTunerControl,
            fraction -> setProgress(progressStart + fraction * progressRange));

        // Replace the survey future so stopInternal() cancels this one
        mActiveSurveyFuture.set(steppedFuture);

        try
        {
            List<EnergyPeak> peaks = steppedFuture.get();
            mActiveSurveyFuture.compareAndSet(steppedFuture, null);
            return peaks;
        }
        catch(CancellationException e)
        {
            mActiveSurveyFuture.compareAndSet(steppedFuture, null);

            if(mCurrentEpoch.get() == myEpoch)
            {
                setState(ScanState.CANCELLED);
            }

            return null;
        }
        catch(InterruptedException e)
        {
            mActiveSurveyFuture.compareAndSet(steppedFuture, null);

            if(mCurrentEpoch.get() == myEpoch)
            {
                setState(ScanState.CANCELLED);
            }

            return null;
        }
        catch(Exception e)
        {
            mActiveSurveyFuture.compareAndSet(steppedFuture, null);

            if(mCurrentEpoch.get() != myEpoch)
            {
                return null;
            }

            mLog.error("Stepped sweep survey failed: {}", e.getMessage(), e);
            mLastErrorMessage = e.getMessage() != null ? e.getMessage()
                : (e.getCause() != null ? e.getCause().getMessage() : "Stepped sweep survey failed");
            setState(ScanState.ERROR);
            return null;
        }
    }

    /**
     * @param request         the active scan request
     * @param myEpoch         the epoch of the current scan pass
     * @param peaks           survey output
     * @param addEnergyRows   if {@code true}, ENERGY_DETECTED rows are added to the model;
     *                        {@code false} is reserved for future use
     */
    private void runProbeLoop(ScanRequest request, int myEpoch,
                               List<EnergyPeak> peaks, boolean addEnergyRows)
    {
        setState(ScanState.PROBING);

        List<Discovery> toProbeLater = new ArrayList<>();

        for(EnergyPeak peak : peaks)
        {
            if(mCurrentEpoch.get() != myEpoch)
            {
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
                FxThreads.run(() -> discovery.setState(DiscoveryState.KNOWN));
                mDiscoveryModel.add(discovery);
            }
            else
            {
                mDiscoveryModel.add(discovery);
                toProbeLater.add(discovery);
            }
        }

        // Sequential probing (one classification at a time)
        int probeLimit = (request.maxSignalsToProbe() > 0) ? request.maxSignalsToProbe() : Integer.MAX_VALUE;
        int probeCount = 0;
        int totalToProbe = Math.min(toProbeLater.size(), probeLimit);

        for(Discovery discovery : toProbeLater)
        {
            if(mCurrentEpoch.get() != myEpoch)
            {
                return;
            }

            if(probeCount >= probeLimit)
            {
                break;
            }

            probeOne(discovery, request, myEpoch);
            probeCount++;

            // Update probe progress: second half of the 0..1 range
            if(totalToProbe > 0)
            {
                double probeProgress = (double) probeCount / totalToProbe;
                setProgress(0.5 + probeProgress * 0.5);
            }
        }
    }

    /**
     * Schedules a continuous rescan via the scheduler.  The rescan body runs on
     * {@code mExecutor} (submitted from the scheduled task) so the scheduler thread
     * is never blocked by long-running work, and cancelling the submitted future
     * actually stops the rescan.
     */
    private void scheduleRescan(ScanRequest request, int myEpoch)
    {
        try
        {
            ScheduledFuture<?> scheduled = mScheduler.schedule(
                () -> {
                    if(mCurrentEpoch.get() == myEpoch && !mShutdown.get())
                    {
                        try
                        {
                            Future<?> rescanFuture = mExecutor.submit(() -> runRescan(request, myEpoch));
                            mActiveScanFuture.compareAndSet(null, rescanFuture);
                        }
                        catch(RejectedExecutionException e)
                        {
                            mLog.warn("BandScanController: executor rejected rescan — executor may be shut down");
                        }
                    }
                },
                request.continuousInterval().toMillis(),
                TimeUnit.MILLISECONDS);

            mContinuousSchedule.set(scheduled);
        }
        catch(RejectedExecutionException e)
        {
            mLog.warn("BandScanController: scheduler rejected rescan scheduling — scheduler may be shut down");
        }
    }

    /**
     * Probes a single discovery synchronously (called on the executor thread).
     *
     * <p>If the classifier future is cancelled ({@code CancellationException}) the discovery
     * is left in its current state (not set to ERROR) and the method returns without touching
     * the model, allowing the epoch check in the caller to determine the final outcome.</p>
     *
     * @param discovery the discovery to probe
     * @param request   the active scan request (used to get the candidate decoder set)
     * @param myEpoch   the epoch of the current scan pass
     */
    private void probeOne(Discovery discovery, ScanRequest request, int myEpoch)
    {
        if(mCurrentEpoch.get() != myEpoch)
        {
            return;
        }

        FxThreads.run(() -> discovery.setState(DiscoveryState.PROBING));
        mDiscoveryModel.update(discovery);

        // Pass the operator's chosen decoder set through to the classifier
        ClassificationRequest req = ClassificationRequest.forFrequency(
            discovery.getCenterFrequencyHz(),
            discovery.getBandwidthHz(),
            request.candidateDecoders(),
            "scan@" + discovery.getCenterFrequencyHz());

        CompletableFuture<ClassificationResult> future = mClassifier.classify(req);
        mActiveClassifyFuture.set(future);

        try
        {
            ClassificationResult result = future.get();

            if(mCurrentEpoch.get() == myEpoch)
            {
                applyClassificationResult(discovery, result);
                mDiscoveryModel.update(discovery);
            }
        }
        catch(CancellationException e)
        {
            // Future was cancelled (e.g. by stopInternal) — leave the discovery in its
            // current state and let the epoch check in the caller decide the outcome.
            mLog.debug("Classification cancelled for {} Hz", discovery.getCenterFrequencyHz());
        }
        catch(InterruptedException e)
        {
            // Do NOT re-interrupt: returning the thread to the pool with an interrupt set
            // causes unrelated pool housekeeping to be interrupted.
            mLog.debug("Classification interrupted for {} Hz", discovery.getCenterFrequencyHz());
        }
        catch(ExecutionException e)
        {
            if(mCurrentEpoch.get() == myEpoch)
            {
                mLog.warn("Classification error for {} Hz: {}", discovery.getCenterFrequencyHz(),
                    e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                FxThreads.run(() -> discovery.setState(DiscoveryState.ERROR));
                mDiscoveryModel.update(discovery);
            }
        }
        finally
        {
            mActiveClassifyFuture.compareAndSet(future, null);
        }
    }

    /**
     * Applies a {@link ClassificationResult} to a {@link Discovery}, updating all relevant fields.
     * All JavaFX property mutations are marshalled to the FX Application Thread via
     * {@link FxThreads#run(Runnable)}.
     */
    private void applyClassificationResult(Discovery discovery, ClassificationResult result)
    {
        if(result == null)
        {
            FxThreads.run(() -> discovery.setState(DiscoveryState.ERROR));
            return;
        }

        Instant now = Instant.now();

        switch(result.outcome())
        {
            case IDENTIFIED ->
            {
                int confidence = computeConfidence(result);
                SignalKind kind = result.kind() != null ? result.kind() : SignalKind.UNKNOWN;
                java.util.Map<String, String> metadata = result.metadata();
                FxThreads.run(() -> {
                    discovery.setLastSeen(now);
                    discovery.setState(DiscoveryState.IDENTIFIED);
                    discovery.setDetectedDecoder(result.bestDecoder());
                    discovery.setKind(kind);
                    discovery.setConfidence(confidence);
                    if(metadata != null)
                    {
                        discovery.setMetadata(metadata);
                        discovery.bumpMetadataVersion();
                    }
                });
            }
            case UNIDENTIFIED, NO_SIGNAL ->
                FxThreads.run(() -> {
                    discovery.setLastSeen(now);
                    discovery.setState(DiscoveryState.UNIDENTIFIED);
                });
            case ERROR ->
                FxThreads.run(() -> {
                    discovery.setLastSeen(now);
                    discovery.setState(DiscoveryState.ERROR);
                });
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
    // Helpers
    // -------------------------------------------------------------------------

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
     * Returns a possibly-new {@link ScanRequest} with the user's excluded decoders removed
     * from the candidate set.  If the exclusion set is empty or produces no change, the
     * original request is returned unchanged.
     *
     * <p>This ensures the programmatic {@link #startScan(ScanRequest)} path applies the
     * same exclusions as {@link ScanRequest#defaults(long, long,
     * io.github.dsheirer.preference.discovery.DiscoveryPreference)}.</p>
     *
     * @param request the original scan request
     * @return the request with excluded decoders removed, or the original if no change
     */
    private ScanRequest applyExclusions(ScanRequest request)
    {
        Set<DecoderType> excluded = mUserPreferences.getDiscoveryPreference().getExcludedDecoders();
        if(excluded.isEmpty())
        {
            return request;
        }

        EnumSet<DecoderType> filtered = EnumSet.copyOf(request.candidateDecoders());
        filtered.removeAll(excluded);

        if(filtered.isEmpty())
        {
            mLog.info("applyExclusions: all decoders excluded for this scan; ignoring the exclusion list");
            return request;
        }

        if(filtered.equals(request.candidateDecoders()))
        {
            return request;
        }

        return new ScanRequest(
            request.minFrequencyHz(),
            request.maxFrequencyHz(),
            filtered,
            request.surveyDwell(),
            request.thresholdDb(),
            request.maxSignalsToProbe(),
            request.continuous(),
            request.continuousInterval());
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
     * Sets the scan state property on the FX Application Thread.
     * Safe to call from any thread; uses {@link FxThreads#run(Runnable)} which runs
     * inline on the FX thread or falls back to the calling thread in headless tests.
     */
    private void setState(ScanState state)
    {
        FxThreads.run(() -> mScanState.set(state));
    }

    /**
     * Sets the progress property on the FX Application Thread.
     * Safe to call from any thread; uses {@link FxThreads#run(Runnable)} which runs
     * inline on the FX thread or falls back to the calling thread in headless tests.
     */
    private void setProgress(double value)
    {
        FxThreads.run(() -> mProgress.set(value));
    }

    /**
     * Internal stop logic.  Bumps the epoch so in-flight scan bodies detect stale state and
     * exit, cancels the active survey future (promptly releasing the tuner source), cancels
     * the active classifier future, cancels any pending continuous-rescan schedule, and
     * optionally sets the state to {@link ScanState#CANCELLED}.
     *
     * @param setStateCancelled whether to set state to CANCELLED after stopping
     */
    private void stopInternal(boolean setStateCancelled)
    {
        // Bump epoch — in-flight scan bodies check this at every loop iteration
        mCurrentEpoch.incrementAndGet();

        // Cancel the continuous schedule
        ScheduledFuture<?> schedule = mContinuousSchedule.getAndSet(null);
        if(schedule != null)
        {
            schedule.cancel(false);
        }

        // Cancel the active survey — releases the tuner source promptly
        CompletableFuture<List<EnergyPeak>> surveyFuture = mActiveSurveyFuture.getAndSet(null);
        if(surveyFuture != null)
        {
            surveyFuture.cancel(true);
        }

        // Cancel any in-flight classification
        CompletableFuture<ClassificationResult> classifyFuture = mActiveClassifyFuture.getAndSet(null);
        if(classifyFuture != null)
        {
            classifyFuture.cancel(true);
        }

        // Cancel the scan task (interrupt the blocked .get() if any)
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

        // Snapshot to avoid ConcurrentModificationException when DiscoveryModel marshals
        // adds/removes on the FX thread while we iterate
        for(Discovery d : new ArrayList<>(mDiscoveryModel.getDiscoveries()))
        {
            if(deleted.equals(d.getCreatedChannel()))
            {
                FxThreads.run(() -> d.setCreatedChannel(null));
                mDiscoveryModel.update(d);
                break;
            }
        }
    }
}
