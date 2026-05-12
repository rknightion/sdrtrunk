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

import io.github.dsheirer.dsp.squelch.PowerMonitor;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Engine that classifies (auto-detects the protocol of) a signal at a given frequency.
 *
 * <h3>Algorithm overview</h3>
 * <ol>
 *   <li>Acquire a {@link ComplexSource} from the injected {@link SourceProvider}.
 *       If the provider returns {@code null} (no tuner capacity) → {@link ClassificationOutcome#ERROR}.</li>
 *   <li>Start a {@link ComplexSampleFanout} over the real source and subscribe a
 *       {@link PowerMonitor} to it.  Wait up to the <em>energy gate window</em> (~1 s)
 *       collecting power readings; estimate the noise floor and return
 *       {@link ClassificationOutcome#NO_SIGNAL} only when the peak reading is not at
 *       least {@link DiscoveryPreference#getEnergyThresholdDb()} dB above the floor.
 *       If zero readings arrive (no samples at all), that is also {@code NO_SIGNAL}.
 *       The threshold is treated as a relative SNR value (dB above estimated floor), not
 *       an absolute dBFS offset.</li>
 *   <li>Subscribe up to N probe chains concurrently (N = {@link DiscoveryPreference#getMaxConcurrentProbes()},
 *       clamped ≥ 1), in {@link CandidateOrdering} priority order.  As soon as any chain
 *       reaches {@link LockState#LOCKED} the remaining lower-priority candidates are skipped.
 *       Each candidate's per-protocol probe window ({@link DiscoveryPreference#probeWindow(DecoderType)})
 *       acts as a per-candidate deadline; the request's {@code overallDeadline} is the global cap.</li>
 *   <li>Pick the best result (highest quality among any LOCKED candidates, then PARTIAL,
 *       then NONE).  Build the final {@link ClassificationResult}.</li>
 *   <li>Always close the {@link ClassificationSession} (stops fanout + all probe chains).</li>
 * </ol>
 *
 * <h3>Cancellation</h3>
 * <p>{@link #classify(ClassificationRequest)} submits the work via
 * {@link ExecutorService#submit(java.util.concurrent.Callable)} and returns a wrapper
 * {@link CompletableFuture} whose {@code cancel()} propagates an interrupt to the worker
 * thread and sets an internal cancelled flag.  The energy-gate wait loop and probe-wait
 * loop both poll the flag and respond within ~100 ms, producing a
 * {@link ClassificationOutcome#CANCELLED} result with full cleanup.</p>
 *
 * <h3>Testability seams</h3>
 * <ul>
 *   <li>{@link SourceProvider} — injected; default binding wraps {@code TunerManager.getSource}</li>
 *   <li>{@link ProbeChainFactory} — injected; tests supply scripted fakes</li>
 * </ul>
 */
public class SignalClassifier implements Classifier
{
    private static final Logger mLog = LoggerFactory.getLogger(SignalClassifier.class);

    /**
     * How long the energy gate collects power readings before making its decision.
     * Long enough to receive several PowerMonitor notifications (~500 ms each at 25 kHz).
     */
    private static final Duration ENERGY_GATE_WINDOW = Duration.ofMillis(1000);

    /**
     * Poll interval used when waiting for the LockWatcher to settle.
     */
    private static final long POLL_INTERVAL_MS = 25;

    private final SourceProvider mSourceProvider;
    private final ProbeChainFactory mProbeChainFactory;
    private final DiscoveryPreference mDiscoveryPreference;
    private final ExecutorService mExecutor;

    /**
     * Constructs a {@code SignalClassifier}.
     *
     * @param sourceProvider     seam for acquiring a tuner channel source
     * @param probeChainFactory  factory that builds decoder-only probe chains
     * @param discoveryPreference user preferences (thresholds, windows, etc.)
     * @param executor           thread pool on which classify() runs its work
     */
    public SignalClassifier(SourceProvider sourceProvider,
                            ProbeChainFactory probeChainFactory,
                            DiscoveryPreference discoveryPreference,
                            ExecutorService executor)
    {
        mSourceProvider = sourceProvider;
        mProbeChainFactory = probeChainFactory;
        mDiscoveryPreference = discoveryPreference;
        mExecutor = executor;
    }

    /**
     * Classifies the signal at the frequency specified by the request.
     *
     * <p>The returned future never completes exceptionally; all errors are
     * encoded as {@link ClassificationOutcome#ERROR} results.  Calling
     * {@code future.cancel(mayInterruptIfRunning)} (either value) interrupts the
     * worker thread and initiates full cleanup; the result will be
     * {@link ClassificationOutcome#CANCELLED}.</p>
     *
     * @param request classification parameters
     * @return a future that resolves to the classification result; cancellable
     */
    public CompletableFuture<ClassificationResult> classify(ClassificationRequest request)
    {
        AtomicBoolean cancelledFlag = new AtomicBoolean(false);

        // Wrapper that overrides cancel() to interrupt the worker thread.
        // Using AtomicReference so the inner class can assign workerFuture after construction.
        AtomicReference<Future<?>> workerFutureRef = new AtomicReference<>();

        CompletableFuture<ClassificationResult> wrapper = new CompletableFuture<ClassificationResult>()
        {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning)
            {
                cancelledFlag.set(true);
                // Mark as cancelled BEFORE interrupting the worker so that when the
                // worker thread calls wrapper.complete(result) after being interrupted,
                // the future is already in the cancelled state and complete() is a no-op.
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                Future<?> wf = workerFutureRef.get();
                if(wf != null)
                {
                    wf.cancel(true); // interrupt the worker thread
                }
                return cancelled;
            }
        };

        // Submit work as a real Future so we can interrupt it on cancel.
        Future<?> workerFuture = mExecutor.submit(() -> {
            try
            {
                ClassificationResult result = doClassify(request, cancelledFlag);
                wrapper.complete(result); // no-op if wrapper was already cancelled
            }
            catch(Throwable t)
            {
                // Belt-and-suspenders: doClassify wraps internally, but guard here too
                mLog.error("Unexpected error in SignalClassifier worker", t);
                wrapper.complete(ClassificationResult.error(request.centerFrequencyHz(), t.getMessage()));
            }
        });

        workerFutureRef.set(workerFuture);

        // If wrapper was cancelled before we set the reference, interrupt now.
        if(wrapper.isCancelled())
        {
            workerFuture.cancel(true);
        }

        return wrapper;
    }

    // -------------------------------------------------------------------------
    // Core probe logic (runs on executor thread)
    // -------------------------------------------------------------------------

    private ClassificationResult doClassify(ClassificationRequest request, AtomicBoolean cancelledFlag)
    {
        long freqHz = request.centerFrequencyHz();

        try
        {
            return doClassifyInternal(request, freqHz, cancelledFlag);
        }
        catch(Throwable t)
        {
            mLog.error("Classification failed unexpectedly at {} Hz", freqHz, t);
            return ClassificationResult.error(freqHz, t.getMessage());
        }
    }

    /**
     * Snapshot of LockWatcher data captured before the probe chain is torn down.
     * Needed because the watcher is not accessible after {@code dispose()}.
     */
    private record WatcherSnapshot(SignalKind kind, String summary, Map<String, String> metadata) {}

    private ClassificationResult doClassifyInternal(ClassificationRequest request,
                                                     long freqHz,
                                                     AtomicBoolean cancelledFlag)
    {
        Instant overallDeadline = Instant.now().plus(request.overallDeadline());

        // --- Step 1: Acquire source ------------------------------------------
        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(freqHz);

        // Use a conservative channel spec (12.5 kHz bandwidth) as default
        DecodeConfiguration tempConfig = DecoderFactory.getDecodeConfiguration(DecoderType.NBFM);
        ChannelSpecification channelSpec = tempConfig.getChannelSpecification();

        ComplexSource realSource;

        try
        {
            realSource = mSourceProvider.acquire(sourceConfig, channelSpec, "discovery-" + freqHz);
        }
        catch(SourceException e)
        {
            mLog.warn("SignalClassifier: error acquiring source for {} Hz: {}", freqHz, e.getMessage());
            return ClassificationResult.error(freqHz, "source error: " + e.getMessage());
        }

        if(realSource == null)
        {
            mLog.info("SignalClassifier: no tuner capacity available for {} Hz", freqHz);
            return ClassificationResult.error(freqHz, "no tuner capacity available");
        }

        // --- Step 2: Fan-out + energy gate ------------------------------------
        ComplexSampleFanout fanout = new ComplexSampleFanout(realSource);

        try(ClassificationSession session = new ClassificationSession(realSource, fanout))
        {
            // Set up the energy gate BEFORE starting the source so the subscriber
            // is registered when the first samples arrive.
            double signalPowerDb = runEnergyGateWithStart(fanout, realSource, cancelledFlag);

            if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
            {
                return ClassificationResult.cancelled(freqHz);
            }

            if(Double.isNaN(signalPowerDb))
            {
                return ClassificationResult.noSignal(freqHz, Double.NaN);
            }

            // --- Step 3: Probe loop with bounded concurrency -----------------
            List<DecoderType> ordered = CandidateOrdering.order(request.candidateDecoders(),
                request.approximateBandwidthHz());

            int maxConcurrent = Math.max(1, mDiscoveryPreference.getMaxConcurrentProbes());

            List<Candidate>        candidates    = new ArrayList<>();
            // Watcher snapshots keyed by DecoderType, captured before each chain is torn down.
            // Used so kind/metadata/summary survive chain disposal.
            Map<DecoderType, WatcherSnapshot> snapshots = new HashMap<>();

            // Active slots: at most maxConcurrent chains running simultaneously.
            List<ProbeChain> activeChains    = new ArrayList<>(maxConcurrent);
            List<Instant>    activeDeadlines = new ArrayList<>(maxConcurrent);
            int nextIndex = 0; // next decoder to launch from ordered list
            boolean shortCircuit = false; // set when a lock is confirmed

            // Seed the initial batch
            while(nextIndex < ordered.size() && activeChains.size() < maxConcurrent)
            {
                if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
                {
                    break;
                }
                if(Instant.now().isAfter(overallDeadline))
                {
                    break;
                }

                DecoderType decoderType = ordered.get(nextIndex++);
                ProbeChain pc = launchProbeChain(decoderType, fanout, session, freqHz, candidates);
                if(pc != null)
                {
                    activeChains.add(pc);
                    activeDeadlines.add(Instant.now().plus(mDiscoveryPreference.probeWindow(decoderType)));
                }
            }

            // Poll active chains; as one completes, record it and optionally start the next
            while(!activeChains.isEmpty())
            {
                if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
                {
                    break;
                }

                if(Instant.now().isAfter(overallDeadline))
                {
                    break;
                }

                boolean anyCompleted = false;

                for(int i = 0; i < activeChains.size(); i++)
                {
                    ProbeChain pc = activeChains.get(i);
                    Instant probeDeadline = activeDeadlines.get(i);
                    LockState state = pc.lockWatcher().getLockState();
                    boolean timedOut = Instant.now().isAfter(probeDeadline) || Instant.now().isAfter(overallDeadline);

                    if(state == LockState.LOCKED || state == LockState.ERROR || timedOut)
                    {
                        LockState finalState = (timedOut && state != LockState.LOCKED && state != LockState.ERROR)
                            ? pc.lockWatcher().getLockState()
                            : state;

                        // Capture watcher data BEFORE teardown
                        snapshots.put(pc.decoderType(), new WatcherSnapshot(
                            pc.lockWatcher().getKind(),
                            pc.lockWatcher().getSummary(),
                            pc.lockWatcher().getMetadata()
                        ));

                        candidates.add(new Candidate(
                            pc.decoderType(),
                            finalState,
                            pc.lockWatcher().getLockQuality(),
                            null
                        ));

                        tearDownChain(pc, session, fanout);
                        activeChains.remove(i);
                        activeDeadlines.remove(i);
                        anyCompleted = true;
                        i--;

                        if(finalState == LockState.LOCKED)
                        {
                            shortCircuit = true;
                        }
                        else if(!shortCircuit)
                        {
                            // Slot freed: start next candidate if any remain
                            while(nextIndex < ordered.size() && activeChains.size() < maxConcurrent)
                            {
                                if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
                                {
                                    break;
                                }
                                if(Instant.now().isAfter(overallDeadline))
                                {
                                    break;
                                }
                                DecoderType next = ordered.get(nextIndex++);
                                ProbeChain newPc = launchProbeChain(next, fanout, session, freqHz, candidates);
                                if(newPc != null)
                                {
                                    activeChains.add(newPc);
                                    activeDeadlines.add(Instant.now().plus(mDiscoveryPreference.probeWindow(next)));
                                }
                            }
                        }
                    }
                }

                if(!anyCompleted && !activeChains.isEmpty())
                {
                    try
                    {
                        Thread.sleep(POLL_INTERVAL_MS);
                    }
                    catch(InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        cancelledFlag.set(true);
                        break;
                    }
                }
            }

            // Tear down any remaining active chains (deadline or cancel)
            for(ProbeChain pc : activeChains)
            {
                snapshots.put(pc.decoderType(), new WatcherSnapshot(
                    pc.lockWatcher().getKind(),
                    pc.lockWatcher().getSummary(),
                    pc.lockWatcher().getMetadata()
                ));
                candidates.add(new Candidate(
                    pc.decoderType(),
                    pc.lockWatcher().getLockState(),
                    pc.lockWatcher().getLockQuality(),
                    null
                ));
                tearDownChain(pc, session, fanout);
            }

            // Check cancellation after cleanup
            if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
            {
                return ClassificationResult.cancelled(freqHz);
            }

            // --- Step 4: Build result -----------------------------------------
            // Find the best LOCKED candidate in CandidateOrdering priority order.
            // When multiple chains lock concurrently, the one with the lowest index in
            // 'ordered' wins (spec §5.3 step 4: highest-priority-by-bandwidth that locked).
            DecoderType winnerType = null;
            int winnerPriority = Integer.MAX_VALUE;

            for(Candidate c : candidates)
            {
                if(c.lockState() == LockState.LOCKED)
                {
                    int priorityIdx = ordered.indexOf(c.decoderType());
                    if(priorityIdx < winnerPriority
                        || (priorityIdx == winnerPriority
                            && winnerType != null
                            && c.lockQuality() > getQualityFor(c.decoderType(), candidates)))
                    {
                        winnerPriority = priorityIdx;
                        winnerType = c.decoderType();
                    }
                }
            }

            if(winnerType != null)
            {
                DecodeConfiguration bestConfig = DecoderFactory.getDecodeConfiguration(winnerType);
                WatcherSnapshot snap = snapshots.getOrDefault(winnerType,
                    new WatcherSnapshot(SignalKind.UNKNOWN, "", Map.of()));

                // Build summary: "P25 Phase 1 · control · NAC:0x293"
                String summary = winnerType.getDisplayString()
                    + (snap.summary().isBlank() ? "" : " · " + snap.summary());

                return ClassificationResult.identified(
                    freqHz,
                    candidates,
                    winnerType,
                    bestConfig,
                    snap.kind(),
                    summary,
                    snap.metadata(),
                    signalPowerDb
                );
            }
            else
            {
                return ClassificationResult.unidentified(freqHz, candidates, signalPowerDb);
            }
            // session.close() runs here via try-with-resources
        }
    }

    /** Helper to look up the lock quality for a given decoder type in the candidates list. */
    private static double getQualityFor(DecoderType type, List<Candidate> candidates)
    {
        for(Candidate c : candidates)
        {
            if(c.decoderType() == type)
            {
                return c.lockQuality();
            }
        }
        return 0.0;
    }

    // -------------------------------------------------------------------------
    // Probe chain launch / teardown helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a probe chain for the given decoder, wires it to the fanout, registers it with
     * the session, and starts it.  On any failure, records an ERROR candidate and returns null.
     */
    private ProbeChain launchProbeChain(DecoderType decoderType, ComplexSampleFanout fanout,
                                         ClassificationSession session, long freqHz,
                                         List<Candidate> candidates)
    {
        ProbeChain pc = null;
        ComplexSource subscriberSource = null;

        try
        {
            pc = mProbeChainFactory.build(decoderType);
            subscriberSource = fanout.newSubscriberSource();

            // Register with session immediately after build, before setSource/start,
            // so a mid-setup exception cannot leak a running chain.
            if(pc.chain() != null)
            {
                session.addProbeChain(pc.chain());
                pc.chain().setSource(subscriberSource);
                pc.chain().start();
            }

            return new ProbeChain(pc.decoderType(), pc.chain(), pc.lockWatcher(), subscriberSource);
        }
        catch(Exception e)
        {
            if(subscriberSource != null)
            {
                fanout.removeSubscriberSource(subscriberSource);
            }

            if(pc != null && pc.chain() != null)
            {
                session.removeProbeChain(pc.chain());
                try { pc.chain().stop(); } catch(Exception ex) { mLog.debug("Stop error for failed probe chain", ex); }
                try { pc.chain().dispose(); } catch(Exception ex) { mLog.debug("Dispose error for failed probe chain", ex); }
            }

            mLog.warn("SignalClassifier: error launching probe for {} at {} Hz: {}",
                decoderType, freqHz, e.getMessage());
            candidates.add(new Candidate(decoderType, LockState.ERROR, 0.0, e.getMessage()));
            return null;
        }
    }

    /**
     * Stops and disposes a probe chain and removes it from the fanout / session.
     */
    private void tearDownChain(ProbeChain pc, ClassificationSession session, ComplexSampleFanout fanout)
    {
        if(pc.source() != null)
        {
            fanout.removeSubscriberSource(pc.source());
        }

        if(pc.chain() != null)
        {
            session.removeProbeChain(pc.chain());
            try { pc.chain().stop(); } catch(Exception ex) { mLog.debug("Stop error for probe chain", ex); }
            try { pc.chain().dispose(); } catch(Exception ex) { mLog.debug("Dispose error for probe chain", ex); }
        }
    }

    // -------------------------------------------------------------------------
    // Energy gate
    // -------------------------------------------------------------------------

    /**
     * Attaches a {@link PowerMonitor} subscriber to the fanout, then starts the real
     * source, and waits up to {@link #ENERGY_GATE_WINDOW} collecting power readings.
     *
     * <p>Reads are floor-relative: we estimate the noise floor as the minimum reading
     * observed, and return a non-NaN power only when the peak is at least
     * {@link DiscoveryPreference#getEnergyThresholdDb()} dB above that floor.
     * If no readings arrive at all (zero samples), {@code Double.NaN} is returned.</p>
     *
     * <p>The subscriber is registered <em>before</em> the source is started so that
     * samples delivered synchronously during {@code start()} (as in tests) are captured.</p>
     *
     * @param fanout        the active fanout (real source not yet started)
     * @param realSource    the real source to start
     * @param cancelledFlag set to true if the caller has been cancelled
     * @return the measured peak power in dBm, or {@code Double.NaN} if no signal was detected
     */
    private double runEnergyGateWithStart(ComplexSampleFanout fanout, ComplexSource realSource,
                                           AtomicBoolean cancelledFlag)
    {
        // Collect all power readings over the gate window for floor estimation
        List<Double> powerReadings = new ArrayList<>();
        Object lock = new Object();
        AtomicBoolean hasNewReading = new AtomicBoolean(false);

        PowerMonitor pm = new PowerMonitor();
        pm.setSampleRate((int) realSource.getSampleRate());

        pm.setSourceEventListener(event -> {
            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_CHANNEL_POWER && event.hasValue())
            {
                double powerDb = event.getValue().doubleValue();

                synchronized(lock)
                {
                    powerReadings.add(powerDb);
                    hasNewReading.set(true);
                    lock.notifyAll();
                }
            }
        });

        // Register subscriber BEFORE starting the source
        ComplexSource energySub = fanout.newSubscriberSource();
        energySub.setListener(samples -> pm.process(samples.i(), samples.q()));

        // Now start the source — any synchronously pushed samples will be captured
        realSource.start();

        long gateEndMs = System.currentTimeMillis() + ENERGY_GATE_WINDOW.toMillis();

        synchronized(lock)
        {
            while(System.currentTimeMillis() < gateEndMs && !cancelledFlag.get()
                && !Thread.currentThread().isInterrupted())
            {
                long remaining = gateEndMs - System.currentTimeMillis();

                if(remaining <= 0)
                {
                    break;
                }

                try
                {
                    lock.wait(Math.min(remaining, 50));
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        fanout.removeSubscriberSource(energySub);

        // If cancelled, return NaN — caller will check cancelledFlag and return CANCELLED
        if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
        {
            return Double.NaN;
        }

        // No readings at all → no samples → NO_SIGNAL
        if(powerReadings.isEmpty())
        {
            return Double.NaN;
        }

        // With only one reading we have no floor estimate.  The spec says "err toward
        // proceeding to probe" when genuinely ambiguous — so treat a single reading as
        // sufficient evidence of energy and return it.
        if(powerReadings.size() == 1)
        {
            return powerReadings.get(0);
        }

        // With multiple readings: estimate noise floor as minimum, peak as maximum.
        // Apply the configured threshold as a floor-relative SNR check.
        double floor = Double.MAX_VALUE;
        double peak = -Double.MAX_VALUE;

        for(double r : powerReadings)
        {
            if(r < floor) floor = r;
            if(r > peak) peak = r;
        }

        double threshold = mDiscoveryPreference.getEnergyThresholdDb();
        double snr = peak - floor;

        if(snr < threshold)
        {
            // Peak is not sufficiently above the estimated noise floor
            return Double.NaN;
        }

        return peak;
    }
}
