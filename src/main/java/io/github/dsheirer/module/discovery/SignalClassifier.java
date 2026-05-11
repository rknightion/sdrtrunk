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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
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
 *       {@link PowerMonitor} to it.  Wait up to the <em>energy gate window</em> (~300 ms)
 *       for a power notification above {@link DiscoveryPreference#getEnergyThresholdDb()} dB.
 *       If none arrives → {@link ClassificationOutcome#NO_SIGNAL}.</li>
 *   <li>For each candidate decoder (in {@link CandidateOrdering} order), build a
 *       {@link ProbeChain} via the injected {@link ProbeChainFactory}, attach it to the
 *       fanout, start it, then wait up to
 *       {@link DiscoveryPreference#probeWindow(DecoderType)} for its {@link LockWatcher}
 *       to report {@link LockState#LOCKED} (short-circuit) or time out.</li>
 *   <li>Pick the best result (highest quality among any LOCKED candidates, then PARTIAL,
 *       then NONE).  Build the final {@link ClassificationResult}.</li>
 *   <li>Always close the {@link ClassificationSession} (stops fanout + all probe chains).</li>
 * </ol>
 *
 * <h3>Testability seams</h3>
 * <ul>
 *   <li>{@link SourceProvider} — injected; default binding wraps {@code TunerManager.getSource}</li>
 *   <li>{@link ProbeChainFactory} — injected; tests supply scripted fakes</li>
 * </ul>
 */
public class SignalClassifier
{
    private static final Logger mLog = LoggerFactory.getLogger(SignalClassifier.class);

    /**
     * How long the energy gate waits for a power-above-threshold event before declaring no signal.
     * Kept short so that the overall deadline is not dominated by the gate.
     */
    private static final Duration ENERGY_GATE_WINDOW = Duration.ofMillis(600);

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
     * encoded as {@link ClassificationOutcome#ERROR} results.</p>
     *
     * @param request classification parameters
     * @return a future that resolves to the classification result
     */
    public CompletableFuture<ClassificationResult> classify(ClassificationRequest request)
    {
        return CompletableFuture.supplyAsync(() -> doClassify(request), mExecutor);
    }

    // -------------------------------------------------------------------------
    // Core probe logic (runs on executor thread)
    // -------------------------------------------------------------------------

    private ClassificationResult doClassify(ClassificationRequest request)
    {
        long freqHz = request.centerFrequencyHz();
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
            double signalPowerDb = runEnergyGateWithStart(fanout, realSource);

            if(Double.isNaN(signalPowerDb))
            {
                return ClassificationResult.noSignal(freqHz, Double.NaN);
            }

            // --- Step 3: Probe loop ------------------------------------------
            List<DecoderType> ordered = CandidateOrdering.order(request.candidateDecoders(),
                request.approximateBandwidthHz());

            List<Candidate> candidates = new ArrayList<>();
            ProbeChain winner = null;

            for(DecoderType decoderType : ordered)
            {
                if(Instant.now().isAfter(overallDeadline))
                {
                    break;
                }

                if(Thread.currentThread().isInterrupted())
                {
                    return ClassificationResult.cancelled(freqHz);
                }

                ProbeChain pc = null;

                try
                {
                    pc = mProbeChainFactory.build(decoderType);
                    ComplexSource subscriberSource = fanout.newSubscriberSource();

                    if(pc.chain() != null)
                    {
                        pc.chain().setSource(subscriberSource);
                        session.addProbeChain(pc.chain());
                        pc.chain().start();
                    }

                    Duration window = mDiscoveryPreference.probeWindow(decoderType);
                    Instant probeDeadline = Instant.now().plus(window);

                    // Poll until locked, window expires, or overall deadline
                    LockState lockState = waitForLock(pc.lockWatcher(), probeDeadline, overallDeadline);

                    candidates.add(new Candidate(
                        decoderType,
                        lockState,
                        pc.lockWatcher().getLockQuality(),
                        null
                    ));

                    // Stop this probe chain before starting the next
                    if(pc.chain() != null)
                    {
                        session.removeProbeChain(pc.chain());
                        try { pc.chain().stop(); } catch(Exception ex) { /* log below */ }
                        try { pc.chain().dispose(); } catch(Exception ex) { /* log below */ }
                    }

                    fanout.removeSubscriberSource(subscriberSource);

                    if(lockState == LockState.LOCKED)
                    {
                        winner = pc;
                        break; // Fast path: first clear lock wins
                    }
                }
                catch(Exception e)
                {
                    mLog.warn("SignalClassifier: error probing {} at {} Hz: {}", decoderType, freqHz, e.getMessage());
                    candidates.add(new Candidate(decoderType, LockState.ERROR, 0.0, e.getMessage()));
                }
            }

            // --- Step 4: Build result -----------------------------------------
            if(winner != null)
            {
                DecoderType bestType = winner.decoderType();
                DecodeConfiguration bestConfig = DecoderFactory.getDecodeConfiguration(bestType);
                SignalKind kind = winner.lockWatcher().getKind();
                String summary = winner.lockWatcher().getSummary();

                return ClassificationResult.identified(
                    freqHz,
                    candidates,
                    bestType,
                    bestConfig,
                    kind,
                    summary,
                    winner.lockWatcher().getMetadata(),
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

    // -------------------------------------------------------------------------
    // Energy gate
    // -------------------------------------------------------------------------

    /**
     * Attaches a {@link PowerMonitor} subscriber to the fanout, then starts the real
     * source, and waits up to {@link #ENERGY_GATE_WINDOW} for a power notification
     * above the configured energy threshold.
     *
     * <p>The subscriber is registered <em>before</em> the source is started so that
     * samples delivered synchronously during {@code start()} (as in tests) are captured.</p>
     *
     * @param fanout     the active fanout (real source not yet started)
     * @param realSource the real source to start
     * @return the measured power in dBm, or {@code Double.NaN} if no signal was detected
     */
    private double runEnergyGateWithStart(ComplexSampleFanout fanout, ComplexSource realSource)
    {
        // Use a double[] as an atomic holder (written once under synchronized lock)
        double[] detectedPower = {Double.NaN};
        Object lock = new Object();
        AtomicReference<Boolean> signalFound = new AtomicReference<>(Boolean.FALSE);

        PowerMonitor pm = new PowerMonitor();
        pm.setSampleRate((int) realSource.getSampleRate());

        pm.setSourceEventListener(event -> {
            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_CHANNEL_POWER && event.hasValue())
            {
                double powerDb = event.getValue().doubleValue();

                // Any power reading above noise floor indicates a signal is present.
                // PowerMonitor produces dBFS readings; full-scale sine wave is ≈ 3 dB.
                // We check for any reading above -90 dBFS (arbitrary threshold well above
                // quantization noise) combined with the configured energy threshold.
                if(!signalFound.get())
                {
                    synchronized(lock)
                    {
                        detectedPower[0] = powerDb;
                        signalFound.set(Boolean.TRUE);
                        lock.notifyAll();
                    }
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
            while(!signalFound.get() && System.currentTimeMillis() < gateEndMs)
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

        // Apply the configured energy threshold: if the measured power is too low, treat as no signal.
        double threshold = mDiscoveryPreference.getEnergyThresholdDb();

        if(!signalFound.get() || detectedPower[0] < -100.0 + threshold)
        {
            return Double.NaN;
        }

        return detectedPower[0];
    }

    // -------------------------------------------------------------------------
    // Probe wait
    // -------------------------------------------------------------------------

    /**
     * Polls the given {@link LockWatcher} until it reports {@link LockState#LOCKED},
     * the probe deadline expires, the overall deadline expires, or the thread is interrupted.
     *
     * @param watcher       the watcher to poll
     * @param probeDeadline per-protocol deadline
     * @param overallDeadline overall classification deadline
     * @return the final lock state at the time of return
     */
    private LockState waitForLock(LockWatcher watcher, Instant probeDeadline, Instant overallDeadline)
    {
        while(true)
        {
            LockState state = watcher.getLockState();

            if(state == LockState.LOCKED || state == LockState.ERROR)
            {
                return state;
            }

            Instant now = Instant.now();

            if(now.isAfter(probeDeadline) || now.isAfter(overallDeadline))
            {
                return watcher.getLockState(); // final read
            }

            if(Thread.currentThread().isInterrupted())
            {
                return watcher.getLockState();
            }

            try
            {
                Thread.sleep(POLL_INTERVAL_MS);
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return watcher.getLockState();
            }
        }
    }
}
