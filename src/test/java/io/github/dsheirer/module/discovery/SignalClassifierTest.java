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

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.map.ChannelMapModel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SignalClassifier happy-path scenarios.
 */
class SignalClassifierTest
{
    private static final long FREQ = 154_025_000L;

    private ScheduledExecutorService mExecutor;
    private UserPreferences mUserPreferences;

    @BeforeEach
    void setUp()
    {
        mExecutor = Executors.newScheduledThreadPool(4);
        mUserPreferences = new UserPreferences();
    }

    @AfterEach
    void tearDown()
    {
        mExecutor.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Minimal ProcessingChain stub — counts stop/dispose; no real modules
    // -------------------------------------------------------------------------

    static class CountingChain extends ProcessingChain
    {
        final AtomicInteger stopCount = new AtomicInteger();
        final AtomicInteger disposeCount = new AtomicInteger();

        CountingChain()
        {
            super(new Channel(), new AliasModel());
        }

        @Override public void setSource(io.github.dsheirer.source.Source source) { /* no-op for testing */ }
        @Override public void start() { /* no-op for testing */ }
        @Override public void stop() { stopCount.incrementAndGet(); }
        @Override public void dispose() { disposeCount.incrementAndGet(); }
    }

    // -------------------------------------------------------------------------
    // Fake ComplexSource — delivers high-power samples to trigger energy gate
    // -------------------------------------------------------------------------

    /**
     * Delivers high-power samples when start() is called, so the energy gate passes.
     */
    static class FakeComplexSource extends ComplexSource
    {
        private Listener<ComplexSamples> mSampleListener;
        private Listener<SourceEvent> mSourceEventListener;
        final AtomicBoolean mStopped = new AtomicBoolean(false);

        @Override public SampleType getSampleType() { return SampleType.COMPLEX; }
        @Override public double getSampleRate() { return 25_000.0; }
        @Override public long getFrequency() { return FREQ; }
        @Override public void setListener(Listener<ComplexSamples> listener) { mSampleListener = listener; }
        @Override public Listener<SourceEvent> getSourceEventListener() { return mSourceEventListener; }
        @Override public void setSourceEventListener(Listener<SourceEvent> listener) { mSourceEventListener = listener; }
        @Override public void removeSourceEventListener() { mSourceEventListener = null; }
        @Override public void reset() {}
        @Override public void dispose() { stop(); }

        @Override
        public void stop()
        {
            mStopped.set(true);
        }

        @Override
        public void start()
        {
            pushHighPowerSamples();
        }

        /**
         * Push enough samples to exceed PowerMonitor's broadcast threshold (sampleRate/2 = 12500 samples)
         * and trigger a CHANNEL_POWER source event.
         */
        void pushHighPowerSamples()
        {
            if(mSampleListener == null)
            {
                return;
            }

            int size = 13_500; // > 12500 threshold for 25kHz sample rate
            float[] i = new float[size];
            float[] q = new float[size];
            for(int x = 0; x < size; x++)
            {
                i[x] = 1.0f;
                q[x] = 1.0f;
            }
            mSampleListener.receive(new ComplexSamples(i, q, System.currentTimeMillis()));
        }
    }

    /**
     * A ComplexSource that never delivers samples (simulates no energy).
     */
    static class NoSignalComplexSource extends ComplexSource
    {
        private Listener<ComplexSamples> mSampleListener;
        private Listener<SourceEvent> mSourceEventListener;
        final AtomicBoolean mStopped = new AtomicBoolean(false);

        @Override public SampleType getSampleType() { return SampleType.COMPLEX; }
        @Override public double getSampleRate() { return 25_000.0; }
        @Override public long getFrequency() { return FREQ; }
        @Override public void setListener(Listener<ComplexSamples> listener) { mSampleListener = listener; }
        @Override public Listener<SourceEvent> getSourceEventListener() { return mSourceEventListener; }
        @Override public void setSourceEventListener(Listener<SourceEvent> listener) { mSourceEventListener = listener; }
        @Override public void removeSourceEventListener() { mSourceEventListener = null; }
        @Override public void reset() {}
        @Override public void dispose() { stop(); }
        @Override public void stop() { mStopped.set(true); }
        @Override public void start() {} // deliver nothing
    }

    // -------------------------------------------------------------------------
    // Fake ProbeChainFactory — scripted lock states
    // -------------------------------------------------------------------------

    /**
     * A {@link ProbeChainFactory} that returns probe chains with pre-scripted lock states.
     * The LockWatcher is pre-fed events synchronously so {@code waitForLock()} sees the
     * result immediately.
     */
    static class FakeProbeChainFactory extends ProbeChainFactory
    {
        private final Map<DecoderType, LockState> mLockStates;
        private final Map<DecoderType, SignalKind> mKinds;
        private final List<ProbeChain> mBuilt = new ArrayList<>();

        FakeProbeChainFactory(Map<DecoderType, LockState> lockStates, Map<DecoderType, SignalKind> kinds)
        {
            super(new AliasModel(), new ChannelMapModel(), new UserPreferences());
            mLockStates = lockStates;
            mKinds = kinds;
        }

        @Override
        public ProbeChain build(DecoderType decoderType)
        {
            LockState targetState = mLockStates.getOrDefault(decoderType, LockState.NONE);
            SignalKind kind = mKinds.getOrDefault(decoderType, SignalKind.UNKNOWN);

            LockWatcher watcher = new LockWatcher();
            CountingChain chain = new CountingChain();

            // Pre-feed events so the watcher already has the scripted state
            if(targetState == LockState.LOCKED)
            {
                State reportState = (kind == SignalKind.CONTROL) ? State.CONTROL : State.CALL;
                for(int i = 0; i < LockWatcher.LOCK_DEBOUNCE_COUNT + 1; i++)
                {
                    watcher.getDecoderStateListener().receive(
                        new DecoderStateEvent(this, DecoderStateEvent.Event.NOTIFICATION_CHANNEL_STATE, reportState));
                }
            }
            else if(targetState == LockState.PARTIAL)
            {
                watcher.getDecoderStateListener().receive(
                    new DecoderStateEvent(this, DecoderStateEvent.Event.NOTIFICATION_CHANNEL_STATE, State.CALL));
            }

            ProbeChain pc = new ProbeChain(decoderType, chain, watcher);
            mBuilt.add(pc);
            return pc;
        }

        List<ProbeChain> getBuiltChains()
        {
            return mBuilt;
        }
    }

    // -------------------------------------------------------------------------
    // Helper: build a SignalClassifier with injected fakes
    // -------------------------------------------------------------------------

    private SignalClassifier buildClassifier(SourceProvider sourceProvider, ProbeChainFactory factory)
    {
        return new SignalClassifier(
            sourceProvider,
            factory,
            mUserPreferences.getDiscoveryPreference(),
            mExecutor
        );
    }

    // -------------------------------------------------------------------------
    // Test 1: P25_PHASE1 locks → IDENTIFIED
    // -------------------------------------------------------------------------

    @Test
    @Timeout(15)
    void classify_whenP25Phase1Locks_returnsIdentified() throws Exception
    {
        FakeComplexSource fakeSource = new FakeComplexSource();

        Map<DecoderType, LockState> locks = new HashMap<>();
        locks.put(DecoderType.P25_PHASE1, LockState.LOCKED);

        Map<DecoderType, SignalKind> kinds = new HashMap<>();
        kinds.put(DecoderType.P25_PHASE1, SignalKind.CONTROL);

        FakeProbeChainFactory factory = new FakeProbeChainFactory(locks, kinds);
        SourceProvider provider = (config, spec, name) -> fakeSource;

        ClassificationRequest request = new ClassificationRequest(
            FREQ, 12500,
            EnumSet.of(DecoderType.P25_PHASE1),
            Duration.ofSeconds(10),
            false,
            "test-p25"
        );

        ClassificationResult result = buildClassifier(provider, factory).classify(request).get();

        assertEquals(ClassificationOutcome.IDENTIFIED, result.outcome());
        assertEquals(DecoderType.P25_PHASE1, result.bestDecoder());
        assertNotNull(result.bestDecodeConfig(), "bestDecodeConfig should be non-null for IDENTIFIED");
        assertEquals(SignalKind.CONTROL, result.kind());
        assertFalse(result.candidates().isEmpty(), "candidates list must not be empty");

        Candidate best = result.candidates().stream()
            .filter(c -> c.decoderType() == DecoderType.P25_PHASE1)
            .findFirst()
            .orElse(null);
        assertNotNull(best);
        assertEquals(LockState.LOCKED, best.lockState());

        assertTrue(fakeSource.mStopped.get(), "session close must stop the underlying source");
        assertNull(result.liveChain(), "v1 liveChain must always be null");
    }

    // -------------------------------------------------------------------------
    // Test 2: all probes NONE → UNIDENTIFIED
    // -------------------------------------------------------------------------

    @Test
    @Timeout(15)
    void classify_allProbesNone_returnsUnidentified() throws Exception
    {
        FakeComplexSource fakeSource = new FakeComplexSource();
        FakeProbeChainFactory factory = new FakeProbeChainFactory(new HashMap<>(), new HashMap<>());
        SourceProvider provider = (config, spec, name) -> fakeSource;

        ClassificationRequest request = new ClassificationRequest(
            FREQ, 12500,
            EnumSet.of(DecoderType.NBFM, DecoderType.DMR),
            Duration.ofSeconds(10),
            false,
            "test-unid"
        );

        ClassificationResult result = buildClassifier(provider, factory).classify(request).get();

        assertEquals(ClassificationOutcome.UNIDENTIFIED, result.outcome());
        assertEquals(2, result.candidates().size(), "one Candidate per tried decoder");
        assertTrue(fakeSource.mStopped.get(), "source must be stopped for UNIDENTIFIED");
    }

    // -------------------------------------------------------------------------
    // Test 3: no samples → NO_SIGNAL; no probe chains built
    // -------------------------------------------------------------------------

    @Test
    @Timeout(10)
    void classify_noSamples_returnsNoSignal() throws Exception
    {
        NoSignalComplexSource noSignalSource = new NoSignalComplexSource();
        FakeProbeChainFactory factory = new FakeProbeChainFactory(new HashMap<>(), new HashMap<>());
        SourceProvider provider = (config, spec, name) -> noSignalSource;

        ClassificationRequest request = new ClassificationRequest(
            FREQ, 0,
            EnumSet.of(DecoderType.NBFM),
            Duration.ofSeconds(5),
            false,
            "test-nosig"
        );

        ClassificationResult result = buildClassifier(provider, factory).classify(request).get();

        assertEquals(ClassificationOutcome.NO_SIGNAL, result.outcome());
        assertTrue(factory.getBuiltChains().isEmpty(),
            "no probe chains should be built when no energy is detected");
        assertTrue(noSignalSource.mStopped.get(), "source must be stopped even for NO_SIGNAL");
    }

    // -------------------------------------------------------------------------
    // Test 4: SourceProvider returns null → ERROR
    // -------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void classify_sourceProviderReturnsNull_returnsError() throws Exception
    {
        SourceProvider provider = (config, spec, name) -> null;
        FakeProbeChainFactory factory = new FakeProbeChainFactory(new HashMap<>(), new HashMap<>());

        ClassificationResult result = buildClassifier(provider, factory)
            .classify(ClassificationRequest.forFrequency(FREQ)).get();

        assertEquals(ClassificationOutcome.ERROR, result.outcome());
        assertFalse(result.summary().isBlank(), "error summary should not be blank");
    }

    // -------------------------------------------------------------------------
    // Test 5: SourceProvider throws SourceException → ERROR
    // -------------------------------------------------------------------------

    @Test
    @Timeout(5)
    void classify_sourceProviderThrows_returnsError() throws Exception
    {
        SourceProvider provider = (config, spec, name) -> { throw new SourceException("test error"); };
        FakeProbeChainFactory factory = new FakeProbeChainFactory(new HashMap<>(), new HashMap<>());

        ClassificationResult result = buildClassifier(provider, factory)
            .classify(ClassificationRequest.forFrequency(FREQ)).get();

        assertEquals(ClassificationOutcome.ERROR, result.outcome());
    }
}
