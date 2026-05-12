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
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.controller.channel.map.ChannelMapModel;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BandScanController}.
 *
 * <p>All tests use fake implementations of {@link SpectralSurveyApi} and {@link Classifier}
 * so that no real RF hardware or tuner is required.  The fakes return scripted results
 * immediately (or with minimal delay) so tests complete in milliseconds.</p>
 *
 * <p>Tests that require scan completion use {@link #awaitState(BandScanController, ScanState, long)}
 * to spin-wait rather than fixed sleeps.</p>
 *
 * <p>Preferences are backed by a uniquely-named throwaway node per test so that the
 * developer's real OS-level preference store is never touched.</p>
 */
@Timeout(30)
class BandScanControllerTest
{
    private static final long MIN_HZ = 150_000_000L;
    private static final long MAX_HZ = 160_000_000L;
    private static final long FREQ_A = 154_025_000L;
    private static final long FREQ_B = 155_000_000L;

    private ExecutorService mExecutor;
    private Preferences mTestPrefsNode;
    private UserPreferences mUserPreferences;
    private DiscoveryModel mModel;
    private ChannelModel mChannelModel;
    private RecordingChannelProcessingManager mChannelProcessingManager;
    private DiscoveryChannelFactory mChannelFactory;

    // -------------------------------------------------------------------------
    // Fake survey — returns a scripted list of EnergyPeaks
    // -------------------------------------------------------------------------

    private static class FakeSurvey implements SpectralSurveyApi
    {
        private final List<EnergyPeak> mPeaks;
        private final AtomicBoolean mStopped = new AtomicBoolean(false);

        FakeSurvey(List<EnergyPeak> peaks)
        {
            mPeaks = peaks;
        }

        boolean wasStopped()
        {
            return mStopped.get();
        }

        @Override
        public CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                                           double thresholdDb,
                                                           SpectralSurvey.ProgressListener progress,
                                                           TunerControl tunerControl)
        {
            if(progress != null)
            {
                progress.onProgress(1.0);
            }
            return CompletableFuture.completedFuture(Collections.unmodifiableList(mPeaks));
        }
    }

    /**
     * A survey that blocks indefinitely until its future is cancelled.
     * Tracks whether the underlying "source" (stop/dispose) was released on cancel.
     */
    private static class BlockingSurvey implements SpectralSurveyApi
    {
        private final AtomicBoolean mSourceReleased = new AtomicBoolean(false);

        boolean wasSourceReleased()
        {
            return mSourceReleased.get();
        }

        @Override
        public CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                                           double thresholdDb,
                                                           SpectralSurvey.ProgressListener progress,
                                                           TunerControl tunerControl)
        {
            // Return a CompletableFuture whose cancel() releases the simulated source
            CompletableFuture<List<EnergyPeak>> future = new CompletableFuture<List<EnergyPeak>>()
            {
                @Override
                public boolean cancel(boolean mayInterruptIfRunning)
                {
                    // Simulate releasing the tuner source on cancellation
                    mSourceReleased.set(true);
                    return super.cancel(mayInterruptIfRunning);
                }
            };
            return future;
        }
    }

    // -------------------------------------------------------------------------
    // Fake classifier — returns scripted results keyed by frequency
    // -------------------------------------------------------------------------

    private static class FakeClassifier implements Classifier
    {
        private final Map<Long, ClassificationResult> mResults;
        private final AtomicInteger mCallCount = new AtomicInteger(0);
        private final AtomicInteger mMaxConcurrent = new AtomicInteger(0);
        private final AtomicInteger mCurrentConcurrent = new AtomicInteger(0);
        /** Captures the ClassificationRequest passed to classify() on each call. */
        private final List<ClassificationRequest> mReceivedRequests =
            Collections.synchronizedList(new ArrayList<>());

        FakeClassifier(Map<Long, ClassificationResult> results)
        {
            mResults = results;
        }

        @Override
        public CompletableFuture<ClassificationResult> classify(ClassificationRequest request)
        {
            int concurrent = mCurrentConcurrent.incrementAndGet();
            mMaxConcurrent.accumulateAndGet(concurrent, Math::max);

            mCallCount.incrementAndGet();
            mReceivedRequests.add(request);

            ClassificationResult result = mResults.getOrDefault(
                request.centerFrequencyHz(),
                ClassificationResult.unidentified(request.centerFrequencyHz(), List.of(), Double.NaN));

            mCurrentConcurrent.decrementAndGet();
            return CompletableFuture.completedFuture(result);
        }

        int getCallCount()
        {
            return mCallCount.get();
        }

        int getMaxConcurrent()
        {
            return mMaxConcurrent.get();
        }

        List<ClassificationRequest> getReceivedRequests()
        {
            return Collections.unmodifiableList(mReceivedRequests);
        }
    }

    /**
     * A classifier that blocks until its future is explicitly cancelled.
     * Useful for testing stop() behaviour mid-classification.
     */
    private static class BlockingClassifier implements Classifier
    {
        @Override
        public CompletableFuture<ClassificationResult> classify(ClassificationRequest request)
        {
            // Return a future that never completes normally — only via cancel()
            CompletableFuture<ClassificationResult> future = new CompletableFuture<>();
            return future;
        }
    }

    // -------------------------------------------------------------------------
    // Recording ChannelProcessingManager stub
    // -------------------------------------------------------------------------

    private static class RecordingChannelProcessingManager
    {
        private final List<Channel> mStarted = new ArrayList<>();
        private boolean mThrowOnStart = false;

        void setThrowOnStart(boolean throwOnStart)
        {
            mThrowOnStart = throwOnStart;
        }

        void start(Channel channel) throws ChannelException
        {
            if(mThrowOnStart)
            {
                throw new ChannelException("test-induced start failure");
            }
            mStarted.add(channel);
        }

        void stop(Channel channel)
        {
            // no-op in tests
        }

        List<Channel> getStarted()
        {
            return Collections.unmodifiableList(mStarted);
        }
    }

    // -------------------------------------------------------------------------
    // Test setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws Exception
    {
        // Each test gets its own isolated Preferences node — never touches real user prefs
        mTestPrefsNode = Preferences.userRoot().node("sdrtrunk-test-" + UUID.randomUUID());

        mExecutor = Executors.newCachedThreadPool(r ->
        {
            Thread t = new Thread(r, "test-discovery-worker");
            t.setDaemon(true);
            return t;
        });

        // Build a UserPreferences-like object but with the discovery prefs backed by our test node
        mUserPreferences = new UserPreferencesWithTestDiscoveryPrefs(mTestPrefsNode);
        mModel = new DiscoveryModel();
        mChannelModel = new ChannelModel(new AliasModel());
        mChannelProcessingManager = new RecordingChannelProcessingManager();
        mChannelFactory = new DiscoveryChannelFactory();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        mExecutor.shutdownNow();
        // Remove the throwaway Preferences node
        try { mTestPrefsNode.removeNode(); } catch(Exception ignored) {}
    }

    /**
     * A {@link UserPreferences} subclass that overrides {@link #getDiscoveryPreference()} to
     * return a {@link DiscoveryPreference} backed by the test's isolated Preferences node.
     */
    private static class UserPreferencesWithTestDiscoveryPrefs extends UserPreferences
    {
        private final DiscoveryPreference mDiscoveryPreference;

        UserPreferencesWithTestDiscoveryPrefs(Preferences testNode)
        {
            mDiscoveryPreference = new DiscoveryPreference(t -> {}, testNode);
        }

        @Override
        public DiscoveryPreference getDiscoveryPreference()
        {
            return mDiscoveryPreference;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BandScanController makeController(SpectralSurveyApi survey, Classifier classifier)
    {
        return new BandScanController(
            classifier,
            survey,
            mModel,
            mChannelModel,
            new ChannelProcessingManagerAdapter(mChannelProcessingManager),
            mChannelFactory,
            mUserPreferences,
            mExecutor,
            new StubTunerControl()); // stub tuner — tests inject fake surveys; TunerControl is passed through
    }

    private static ScanRequest simpleScan()
    {
        return new ScanRequest(MIN_HZ, MAX_HZ,
            EnumSet.of(DecoderType.NBFM, DecoderType.P25_PHASE1),
            Duration.ofMillis(1),  // tiny dwell for fast tests
            6.0,
            200,
            false,
            Duration.ofSeconds(300));
    }

    private static EnergyPeak makePeak(long centerHz)
    {
        return new EnergyPeak(centerHz, 12_500, -70.0, 10.0);
    }

    /**
     * Spins (with Thread.sleep) until the controller reaches the target state or the timeout elapses.
     */
    private static void awaitState(BandScanController controller, ScanState expected, long timeoutMs)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while(controller.getScanState() != expected && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20);
        }
    }

    /** Wrapper around ChannelProcessingManager to avoid constructor complexity. */
    private static class ChannelProcessingManagerAdapter extends ChannelProcessingManager
    {
        private final RecordingChannelProcessingManager mRecorder;

        ChannelProcessingManagerAdapter(RecordingChannelProcessingManager recorder)
        {
            super(new ChannelMapModel(), null, null, new AliasModel(), new UserPreferences());
            mRecorder = recorder;
        }

        @Override
        public void start(Channel channel) throws ChannelException
        {
            mRecorder.start(channel);
        }

        @Override
        public void stop(Channel channel) throws ChannelException
        {
            mRecorder.stop(channel);
        }
    }

    // -------------------------------------------------------------------------
    // State transition tests
    // -------------------------------------------------------------------------

    @Test
    void initialStateIsIdle()
    {
        BandScanController ctrl = makeController(new FakeSurvey(List.of()), new FakeClassifier(Map.of()));
        assertEquals(ScanState.IDLE, ctrl.getScanState());
    }

    @Test
    void startScanTransitionsIdleSurveyingProbingDone() throws InterruptedException
    {
        List<ScanState> observed = new ArrayList<>();

        BandScanController ctrl = makeController(new FakeSurvey(List.of()), new FakeClassifier(Map.of()));
        ctrl.scanStateProperty().addListener((obs, oldVal, newVal) -> observed.add(newVal));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        // Must have transitioned through SURVEYING and PROBING at minimum, ending at DONE
        assertTrue(observed.contains(ScanState.SURVEYING), "Should have passed through SURVEYING");
        assertTrue(observed.contains(ScanState.PROBING) || observed.contains(ScanState.DONE),
            "Should have passed through PROBING or gone straight to DONE with no peaks");
        assertEquals(ScanState.DONE, ctrl.getScanState());
    }

    @Test
    void emptyPeakListGoesToDone() throws InterruptedException
    {
        BandScanController ctrl = makeController(new FakeSurvey(List.of()), new FakeClassifier(Map.of()));
        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);
        assertEquals(ScanState.DONE, ctrl.getScanState());
    }

    // -------------------------------------------------------------------------
    // ENERGY_DETECTED rows added before probing — #9 strengthened test
    // -------------------------------------------------------------------------

    /**
     * Verifies that all peaks are seeded into the model as rows before any probing begins.
     *
     * <p>The core invariant is: at the moment the first classify() call is made, the model
     * already contains rows for ALL peaks (both FREQ_A and FREQ_B).  The first row will be
     * in PROBING state (set by probeOne just before classify()), and the second row should
     * still be in ENERGY_DETECTED state (not yet reached by the sequential prober).</p>
     *
     * <p>Because {@link Discovery} is mutable, we snapshot both the frequency set AND the
     * per-row state at the moment of the first classify() call, before the future is returned
     * (and before inline-synchronous FakeClassifier execution unwinds).</p>
     */
    @Test
    void energyDetectedRowsAddedBeforeProbing() throws InterruptedException
    {
        List<EnergyPeak> peaks = List.of(makePeak(FREQ_A), makePeak(FREQ_B));

        // A latch that releases once the first classify() call is made
        CountDownLatch firstClassifyLatch = new CountDownLatch(1);
        // Snapshot of (freq → state) taken at the moment of the first classify() call
        AtomicReference<Map<Long, DiscoveryState>> statesAtFirstClassify = new AtomicReference<>();

        Classifier capturingClassifier = req ->
        {
            // On the very first call, snapshot freq→state for all model rows
            if(firstClassifyLatch.getCount() > 0)
            {
                Map<Long, DiscoveryState> states = new java.util.HashMap<>();
                for(Discovery d : mModel.getDiscoveries())
                {
                    // Capture the STATE value (enum), not the mutable Discovery object
                    states.put(d.getCenterFrequencyHz(), d.getState());
                }
                statesAtFirstClassify.set(states);
                firstClassifyLatch.countDown();
            }
            return CompletableFuture.completedFuture(
                ClassificationResult.unidentified(req.centerFrequencyHz(), List.of(), Double.NaN));
        };

        BandScanController ctrl = makeController(new FakeSurvey(peaks), capturingClassifier);
        ctrl.startScan(simpleScan());

        // Wait for the first classify() call
        assertTrue(firstClassifyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS),
            "First classify() should have been called within 5 seconds");

        // At the time of the first classify(), BOTH rows must already be in the model
        Map<Long, DiscoveryState> states = statesAtFirstClassify.get();
        assertNotNull(states, "State snapshot at first classify() must not be null");
        assertEquals(2, states.size(),
            "Both rows must be in the model before probing starts; got " + states.size());

        assertTrue(states.containsKey(FREQ_A), "FREQ_A row must be present before probing");
        assertTrue(states.containsKey(FREQ_B), "FREQ_B row must be present before probing");

        // FREQ_A is being probed right now — it will be PROBING
        // FREQ_B has been seeded but not yet probed — it will be ENERGY_DETECTED
        assertEquals(DiscoveryState.PROBING, states.get(FREQ_A),
            "FREQ_A should be PROBING (first to be classified)");
        assertEquals(DiscoveryState.ENERGY_DETECTED, states.get(FREQ_B),
            "FREQ_B should be ENERGY_DETECTED (seeded but not yet probed)");

        awaitState(ctrl, ScanState.DONE, 5_000);
    }

    // -------------------------------------------------------------------------
    // Ignore-list peaks not added
    // -------------------------------------------------------------------------

    @Test
    void ignoredPeaksNotAddedToModel() throws InterruptedException
    {
        // Add an ignore range covering FREQ_A
        long ignoreMin = FREQ_A - 10_000L;
        long ignoreMax = FREQ_A + 10_000L;
        mUserPreferences.getDiscoveryPreference().addIgnoreRange(
            IgnoreRange.of(ignoreMin, ignoreMax, "test ignore"));

        List<EnergyPeak> peaks = List.of(makePeak(FREQ_A), makePeak(FREQ_B));
        BandScanController ctrl = makeController(
            new FakeSurvey(peaks),
            new FakeClassifier(Map.of()));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        // Only FREQ_B should appear
        assertEquals(1, mModel.getDiscoveries().size());
        assertEquals(FREQ_B, mModel.getDiscoveries().get(0).getCenterFrequencyHz());
    }

    // -------------------------------------------------------------------------
    // Channel-overlapping peaks marked KNOWN and not probed
    // -------------------------------------------------------------------------

    @Test
    void channelOverlappingPeakMarkedKnownNotProbed() throws InterruptedException
    {
        // Add a channel at FREQ_A to the channel model
        Channel existing = new Channel("Existing");
        SourceConfigTuner config = new SourceConfigTuner();
        config.setFrequency(FREQ_A);
        existing.setSourceConfiguration(config);
        mChannelModel.addChannel(existing);

        FakeClassifier classifier = new FakeClassifier(Map.of());
        List<EnergyPeak> peaks = List.of(makePeak(FREQ_A), makePeak(FREQ_B));

        BandScanController ctrl = makeController(new FakeSurvey(peaks), classifier);
        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        // FREQ_A should be KNOWN and not classified
        Discovery knownDiscovery = mModel.getDiscoveries().stream()
            .filter(d -> d.getCenterFrequencyHz() == FREQ_A)
            .findFirst().orElse(null);
        assertNotNull(knownDiscovery);
        assertEquals(DiscoveryState.KNOWN, knownDiscovery.getState());

        // Classifier should only be called for FREQ_B
        assertEquals(1, classifier.getCallCount());
    }

    // -------------------------------------------------------------------------
    // Sequential probing (max concurrent = 1)
    // -------------------------------------------------------------------------

    @Test
    void probingIsSequential() throws InterruptedException
    {
        FakeClassifier classifier = new FakeClassifier(Map.of());
        List<EnergyPeak> peaks = List.of(
            makePeak(FREQ_A), makePeak(FREQ_B), makePeak(157_000_000L));

        BandScanController ctrl = makeController(new FakeSurvey(peaks), classifier);
        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        // Max concurrent must be 1 (sequential)
        assertEquals(1, classifier.getMaxConcurrent());
        assertEquals(3, classifier.getCallCount());
    }

    // -------------------------------------------------------------------------
    // maxSignalsToProbe honored
    // -------------------------------------------------------------------------

    @Test
    void maxSignalsToProbeLimitsClassifications() throws InterruptedException
    {
        FakeClassifier classifier = new FakeClassifier(Map.of());
        List<EnergyPeak> peaks = List.of(
            makePeak(FREQ_A), makePeak(FREQ_B), makePeak(157_000_000L));

        // Only probe 1 signal
        ScanRequest req = new ScanRequest(MIN_HZ, MAX_HZ,
            EnumSet.of(DecoderType.NBFM),
            Duration.ofMillis(1), 6.0, 1, false, Duration.ofSeconds(300));

        BandScanController ctrl = makeController(new FakeSurvey(peaks), classifier);
        ctrl.startScan(req);
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(1, classifier.getCallCount());
    }

    // -------------------------------------------------------------------------
    // Per-result state updates and confidence bucketing
    // -------------------------------------------------------------------------

    @Test
    void identifiedPeakSetsCorrectState() throws InterruptedException
    {
        ClassificationResult identified = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            null,
            SignalKind.CONVENTIONAL,
            "NBFM",
            Map.of(),
            -70.0);

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, identified));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        Discovery d = mModel.getDiscoveries().get(0);
        assertEquals(DiscoveryState.IDENTIFIED, d.getState());
        assertEquals(DecoderType.NBFM, d.getDetectedDecoder());
        assertEquals(SignalKind.CONVENTIONAL, d.getKind());
        assertTrue(d.getConfidence() >= 3, "LOCKED + quality 0.9 should give confidence >= 3");
    }

    @Test
    void unidentifiedPeakSetsCorrectState() throws InterruptedException
    {
        ClassificationResult unidentified = ClassificationResult.unidentified(
            FREQ_A, List.of(), Double.NaN);

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, unidentified));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(DiscoveryState.UNIDENTIFIED, mModel.getDiscoveries().get(0).getState());
    }

    @Test
    void errorResultSetsErrorState() throws InterruptedException
    {
        ClassificationResult error = ClassificationResult.error(FREQ_A, "test error");

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, error));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(DiscoveryState.ERROR, mModel.getDiscoveries().get(0).getState());
    }

    @Test
    void confidenceBucketingLockedHighQuality() throws InterruptedException
    {
        // LOCKED + quality 0.8 → confidence 4
        ClassificationResult result = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.8, null)),
            DecoderType.NBFM, null, SignalKind.UNKNOWN, "", Map.of(), -60.0);

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, result));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))), classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(4, mModel.getDiscoveries().get(0).getConfidence());
    }

    @Test
    void confidenceBucketingLockedLowQuality() throws InterruptedException
    {
        // LOCKED + quality 0.5 → confidence 3
        ClassificationResult result = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.5, null)),
            DecoderType.NBFM, null, SignalKind.UNKNOWN, "", Map.of(), -60.0);

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, result));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))), classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(3, mModel.getDiscoveries().get(0).getConfidence());
    }

    @Test
    void confidenceBucketingPartialHighQuality() throws InterruptedException
    {
        // PARTIAL + quality 0.6 → confidence 2
        ClassificationResult result = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.PARTIAL, 0.6, null)),
            DecoderType.NBFM, null, SignalKind.UNKNOWN, "", Map.of(), -60.0);

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, result));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))), classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(2, mModel.getDiscoveries().get(0).getConfidence());
    }

    @Test
    void confidenceBucketingPartialLowQuality() throws InterruptedException
    {
        // PARTIAL + quality 0.3 → confidence 1
        ClassificationResult result = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.PARTIAL, 0.3, null)),
            DecoderType.NBFM, null, SignalKind.UNKNOWN, "", Map.of(), -60.0);

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, result));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))), classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(1, mModel.getDiscoveries().get(0).getConfidence());
    }

    // -------------------------------------------------------------------------
    // Fix #2: candidateDecoders from ScanRequest passed through to classifier
    // -------------------------------------------------------------------------

    @Test
    void candidateDecodersFromScanRequestPassedToClassifier() throws InterruptedException
    {
        // Request with a specific (non-default) decoder set
        EnumSet<DecoderType> requestedDecoders = EnumSet.of(DecoderType.NBFM, DecoderType.DMR);

        ScanRequest req = new ScanRequest(MIN_HZ, MAX_HZ,
            requestedDecoders,
            Duration.ofMillis(1), 6.0, 200, false, Duration.ofSeconds(300));

        FakeClassifier classifier = new FakeClassifier(Map.of());
        BandScanController ctrl = makeController(new FakeSurvey(List.of(makePeak(FREQ_A))), classifier);

        ctrl.startScan(req);
        awaitState(ctrl, ScanState.DONE, 5_000);

        // The classifier should have been called exactly once
        assertEquals(1, classifier.getCallCount());

        // The ClassificationRequest passed to the classifier must carry the same decoder set
        ClassificationRequest received = classifier.getReceivedRequests().get(0);
        assertEquals(requestedDecoders, received.candidateDecoders(),
            "ClassificationRequest.candidateDecoders() must equal ScanRequest.candidateDecoders()");
    }

    // -------------------------------------------------------------------------
    // addAsChannel
    // -------------------------------------------------------------------------

    @Test
    void addAsChannelCreatesChannelAndStartsIt() throws InterruptedException
    {
        ClassificationResult identified = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM, null, SignalKind.CONVENTIONAL, "NBFM", Map.of(), -70.0);

        FakeClassifier classifier = new FakeClassifier(Map.of(FREQ_A, identified));
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            classifier);

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        Discovery d = mModel.getDiscoveries().get(0);
        assertEquals(DiscoveryState.IDENTIFIED, d.getState());
        assertNull(d.getCreatedChannel());

        Channel channel = ctrl.addAsChannel(d);

        assertNotNull(channel);
        assertEquals(channel, d.getCreatedChannel());
        assertTrue(mChannelProcessingManager.getStarted().contains(channel));
    }

    @Test
    void addAsChannelIsIdempotent() throws InterruptedException
    {
        ClassificationResult identified = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM, null, SignalKind.CONVENTIONAL, "NBFM", Map.of(), -70.0);

        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            new FakeClassifier(Map.of(FREQ_A, identified)));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        Discovery d = mModel.getDiscoveries().get(0);
        Channel first = ctrl.addAsChannel(d);
        Channel second = ctrl.addAsChannel(d);

        assertNotNull(first);
        assertEquals(first, second);  // returns existing channel on second call
        assertEquals(1, mChannelProcessingManager.getStarted().size());
    }

    @Test
    void addAsChannelNoOpForUnidentified() throws InterruptedException
    {
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            new FakeClassifier(Map.of()));  // defaults to UNIDENTIFIED

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        Discovery d = mModel.getDiscoveries().get(0);
        Channel result = ctrl.addAsChannel(d);

        assertNull(result);
        assertEquals(0, mChannelProcessingManager.getStarted().size());
    }

    @Test
    void addAsChannelRemovesChannelOnStartFailure() throws InterruptedException
    {
        ClassificationResult identified = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM, null, SignalKind.CONVENTIONAL, "NBFM", Map.of(), -70.0);

        mChannelProcessingManager.setThrowOnStart(true);

        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            new FakeClassifier(Map.of(FREQ_A, identified)));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        Discovery d = mModel.getDiscoveries().get(0);
        Channel result = ctrl.addAsChannel(d);

        assertNull(result);
        assertNull(d.getCreatedChannel());
        // Channel was removed from model on failure
        assertEquals(0, mChannelModel.getChannels().size());
    }

    // -------------------------------------------------------------------------
    // addAllAtLeast
    // -------------------------------------------------------------------------

    @Test
    void addAllAtLeastFiltersOnConfidence() throws InterruptedException
    {
        ClassificationResult highConf = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM, null, SignalKind.CONVENTIONAL, "NBFM", Map.of(), -70.0);

        ClassificationResult lowConf = ClassificationResult.identified(
            FREQ_B,
            List.of(new Candidate(DecoderType.NBFM, LockState.PARTIAL, 0.3, null)),
            DecoderType.NBFM, null, SignalKind.CONVENTIONAL, "NBFM", Map.of(), -70.0);

        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A), makePeak(FREQ_B))),
            new FakeClassifier(Map.of(FREQ_A, highConf, FREQ_B, lowConf)));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        // Only add discoveries with confidence >= 3
        ctrl.addAllAtLeast(3);

        assertEquals(1, mChannelProcessingManager.getStarted().size());
        assertEquals(FREQ_A, ((SourceConfigTuner)
            mChannelProcessingManager.getStarted().get(0).getSourceConfiguration()).getFrequency());
    }

    // -------------------------------------------------------------------------
    // ignore()
    // -------------------------------------------------------------------------

    @Test
    void ignoreAddsToPrefsAndRemovesRow() throws InterruptedException
    {
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            new FakeClassifier(Map.of()));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(1, mModel.getDiscoveries().size());
        Discovery d = mModel.getDiscoveries().get(0);

        ctrl.ignore(d);

        assertEquals(0, mModel.getDiscoveries().size());
        assertFalse(mUserPreferences.getDiscoveryPreference().getIgnoreList().isEmpty(),
            "Ignore list should have an entry after ignore()");
    }

    // -------------------------------------------------------------------------
    // setWatched
    // -------------------------------------------------------------------------

    @Test
    void setWatchedUpdatesFlag() throws InterruptedException
    {
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            new FakeClassifier(Map.of()));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        Discovery d = mModel.getDiscoveries().get(0);
        assertFalse(d.isWatched());

        ctrl.setWatched(d, true);

        assertTrue(d.isWatched());
    }

    // -------------------------------------------------------------------------
    // reprobe
    // -------------------------------------------------------------------------

    @Test
    void reprobe() throws InterruptedException
    {
        // Start with UNIDENTIFIED
        BandScanController ctrl = makeController(
            new FakeSurvey(List.of(makePeak(FREQ_A))),
            new FakeClassifier(Map.of()));

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        Discovery d = mModel.getDiscoveries().get(0);
        assertEquals(DiscoveryState.UNIDENTIFIED, d.getState());

        // Now set up the classifier to return IDENTIFIED on the next call
        ClassificationResult identified = ClassificationResult.identified(
            FREQ_A,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM, null, SignalKind.CONVENTIONAL, "NBFM", Map.of(), -70.0);

        AtomicReference<ClassificationResult> nextResult = new AtomicReference<>(identified);
        Classifier reprober = req -> CompletableFuture.completedFuture(nextResult.get());

        BandScanController ctrl2 = new BandScanController(
            reprober, new FakeSurvey(List.of()), mModel,
            mChannelModel,
            new ChannelProcessingManagerAdapter(mChannelProcessingManager),
            mChannelFactory, mUserPreferences, mExecutor, null);

        ctrl2.reprobe(d);

        // Wait for reprobe to complete
        long deadline = System.currentTimeMillis() + 5_000;
        while(d.getState() == DiscoveryState.PROBING && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20);
        }

        assertEquals(DiscoveryState.IDENTIFIED, d.getState());
        assertEquals(DecoderType.NBFM, d.getDetectedDecoder());
    }

    // -------------------------------------------------------------------------
    // Fix #1: stop() mid-PROBING must yield CANCELLED, not ERROR
    // -------------------------------------------------------------------------

    /**
     * Starts a scan, lets the survey produce peaks, then calls stop() while a classification
     * is in flight (using a BlockingClassifier whose future never completes normally).
     * The final state must be CANCELLED or IDLE (not ERROR).
     */
    @Test
    void stopMidProbingYieldsCancelledNotError() throws InterruptedException
    {
        // The blocking classifier's future is a never-completing CompletableFuture
        // that will be cancelled by stopInternal()
        BlockingClassifier blockingClassifier = new BlockingClassifier();

        List<EnergyPeak> peaks = List.of(makePeak(FREQ_A));
        BandScanController ctrl = makeController(new FakeSurvey(peaks), blockingClassifier);

        ctrl.startScan(simpleScan());

        // Wait for PROBING state (the survey completes instantly, probing blocks)
        awaitState(ctrl, ScanState.PROBING, 3_000);

        // Now stop mid-classify
        ctrl.stop();

        // State must be CANCELLED, not ERROR
        assertEquals(ScanState.CANCELLED, ctrl.getScanState(),
            "stop() mid-PROBING should yield CANCELLED, not ERROR");
    }

    // -------------------------------------------------------------------------
    // Fix #3: stop() must promptly release the survey's tuner source
    // -------------------------------------------------------------------------

    /**
     * Verifies that calling stop() while the survey is blocking promptly cancels the
     * survey future (simulating release of the tuner source), rather than waiting for
     * the full dwell to expire.
     */
    @Test
    void stopCancelsSurveyFuturePromptly() throws InterruptedException
    {
        BlockingSurvey blockingSurvey = new BlockingSurvey();

        BandScanController ctrl = makeController(blockingSurvey, new FakeClassifier(Map.of()));
        ctrl.startScan(simpleScan());

        // Wait for SURVEYING state — by design mActiveSurveyFuture is set BEFORE
        // setState(SURVEYING) so there is no race: the future is always available.
        awaitState(ctrl, ScanState.SURVEYING, 2_000);

        long before = System.currentTimeMillis();
        ctrl.stop();
        long elapsed = System.currentTimeMillis() - before;

        // stop() should return quickly (not wait for the full 3-second dwell)
        assertTrue(elapsed < 1_000,
            "stop() should return quickly when survey is blocked, but took " + elapsed + " ms");

        assertEquals(ScanState.CANCELLED, ctrl.getScanState());

        // The survey future's cancel() should have been called (simulating source release)
        assertTrue(blockingSurvey.wasSourceReleased(),
            "Survey future cancel() must be called on stop() so the tuner source is released promptly");
    }

    // -------------------------------------------------------------------------
    // Fix #4: double startScan is coherent (epoch prevents old scan clobbering new)
    // -------------------------------------------------------------------------

    /**
     * Calls startScan() twice in quick succession with a slow first scan (blocking survey).
     * Asserts that:
     * 1. The final state is DONE (from the second scan).
     * 2. Only one scan's worth of rows appears in the model (no double-add from the stale first scan).
     * 3. No exceptions are thrown.
     */
    @Test
    void doubleStartScanIsCoherent() throws InterruptedException
    {
        // First survey blocks until cancelled
        BlockingSurvey slowSurvey = new BlockingSurvey();

        // Second survey returns one peak immediately
        FakeSurvey fastSurvey = new FakeSurvey(List.of(makePeak(FREQ_A)));

        // We need the controller to switch surveys. We use an AtomicReference to simulate
        // switching: the first call returns the blocking survey, the second returns the fast one.
        AtomicInteger callCount = new AtomicInteger(0);

        SpectralSurveyApi switchingSurvey = (minHz, maxHz, dwell, threshold, progress, tunerControl) ->
        {
            int call = callCount.incrementAndGet();

            if(call == 1)
            {
                return slowSurvey.survey(minHz, maxHz, dwell, threshold, progress, tunerControl);
            }
            else
            {
                return fastSurvey.survey(minHz, maxHz, dwell, threshold, progress, tunerControl);
            }
        };

        FakeClassifier classifier = new FakeClassifier(Map.of());
        BandScanController ctrl = makeController(switchingSurvey, classifier);

        // Start first scan (will block in survey).
        // Wait for SURVEYING so we know the blocking survey is active and its future
        // is stored in mActiveSurveyFuture before we start the second scan.
        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.SURVEYING, 2_000);

        // Now start the second scan — cancels the first and submits a new scan body
        // that will call the fast survey (callCount == 2 → fastSurvey path)
        ctrl.startScan(simpleScan());

        // Wait for the second scan to complete
        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(ScanState.DONE, ctrl.getScanState(), "Second scan should end with DONE");

        // Only the discoveries from the second scan should appear (epoch guard prevents
        // the stale first scan from adding rows)
        long freqACount = mModel.getDiscoveries().stream()
            .filter(d -> d.getCenterFrequencyHz() == FREQ_A)
            .count();
        assertEquals(1, freqACount,
            "Only one row for FREQ_A (from second scan); stale first scan should not add rows");
    }

    // -------------------------------------------------------------------------
    // stop() sets CANCELLED state
    // -------------------------------------------------------------------------

    @Test
    void stopSetsCancelledState() throws InterruptedException
    {
        // Use a slow survey to ensure we can stop before it completes
        SpectralSurveyApi slowSurvey = (minHz, maxHz, dwell, thresholdDb, progress, tunerControl) ->
        {
            CompletableFuture<List<EnergyPeak>> future = new CompletableFuture<>();
            // Never complete — the test will call stop()
            return future;
        };

        BandScanController ctrl = makeController(slowSurvey, new FakeClassifier(Map.of()));
        ctrl.startScan(simpleScan());

        // Wait for SURVEYING state
        awaitState(ctrl, ScanState.SURVEYING, 2_000);

        ctrl.stop();

        assertEquals(ScanState.CANCELLED, ctrl.getScanState());
    }

    // -------------------------------------------------------------------------
    // shutdown() rejects new scans
    // -------------------------------------------------------------------------

    @Test
    void shutdownRejectsNewScans() throws InterruptedException
    {
        BandScanController ctrl = makeController(new FakeSurvey(List.of()), new FakeClassifier(Map.of()));
        ctrl.shutdown();

        ctrl.startScan(simpleScan());

        // State should remain at CANCELLED (set by shutdown → stop), not transition to SURVEYING
        Thread.sleep(200);
        assertFalse(ctrl.getScanState() == ScanState.SURVEYING,
            "Should not start a scan after shutdown");
    }

    // -------------------------------------------------------------------------
    // continuous re-survey refreshes rather than duplicates
    // -------------------------------------------------------------------------

    @Test
    void continuousScanRefreshesExistingRowsRatherThanDuplicating() throws InterruptedException
    {
        FakeClassifier classifier = new FakeClassifier(Map.of());
        ScanRequest continuous = new ScanRequest(MIN_HZ, MAX_HZ,
            EnumSet.of(DecoderType.NBFM),
            Duration.ofMillis(1), 6.0, 200,
            true, Duration.ofMillis(50));  // short interval for fast test

        BandScanController ctrl = makeController(new FakeSurvey(List.of(makePeak(FREQ_A))), classifier);
        ctrl.startScan(continuous);

        // Wait for at least one complete cycle (DONE appears as IDLE_CONTINUOUS)
        awaitState(ctrl, ScanState.IDLE_CONTINUOUS, 5_000);

        // Wait for the second cycle to start and complete
        awaitState(ctrl, ScanState.SURVEYING, 5_000);
        awaitState(ctrl, ScanState.IDLE_CONTINUOUS, 5_000);

        ctrl.stop();

        // Should still have exactly 1 discovery (no duplication)
        long discoveryCount = mModel.getDiscoveries().stream()
            .filter(d -> d.getCenterFrequencyHz() == FREQ_A)
            .count();
        assertEquals(1, discoveryCount, "Continuous scan should refresh, not duplicate rows");
    }

    @Test
    void continuousScanReprobesWatchedUnidentified() throws InterruptedException
    {
        AtomicInteger classifyCallCount = new AtomicInteger(0);

        Classifier countingClassifier = req ->
        {
            classifyCallCount.incrementAndGet();
            return CompletableFuture.completedFuture(
                ClassificationResult.unidentified(req.centerFrequencyHz(), List.of(), Double.NaN));
        };

        ScanRequest continuous = new ScanRequest(MIN_HZ, MAX_HZ,
            EnumSet.of(DecoderType.NBFM),
            Duration.ofMillis(1), 6.0, 200,
            true, Duration.ofMillis(50));

        BandScanController ctrl = makeController(new FakeSurvey(List.of(makePeak(FREQ_A))), countingClassifier);
        ctrl.startScan(continuous);

        // Wait for first cycle to finish probing
        awaitState(ctrl, ScanState.IDLE_CONTINUOUS, 5_000);

        // Get the discovery and mark it as watched
        Discovery d = mModel.getDiscoveries().stream()
            .filter(x -> x.getCenterFrequencyHz() == FREQ_A)
            .findFirst().orElseThrow();
        ctrl.setWatched(d, true);

        int callCountAfterFirstCycle = classifyCallCount.get();

        // Wait for a second cycle
        awaitState(ctrl, ScanState.SURVEYING, 5_000);
        awaitState(ctrl, ScanState.IDLE_CONTINUOUS, 5_000);

        ctrl.stop();

        // Watched unidentified should have been re-probed
        assertTrue(classifyCallCount.get() > callCountAfterFirstCycle,
            "Watched unidentified discovery should be re-probed in continuous cycle");
    }

    /**
     * Verifies that a watched+UNIDENTIFIED discovery is re-probed in continuous mode even
     * when the survey returns no energy at its frequency in the rescan cycle.
     */
    @Test
    void continuousScanReprobesWatchedUnidentifiedEvenWithNoEnergy() throws InterruptedException
    {
        AtomicInteger classifyCallCount = new AtomicInteger(0);

        Classifier countingClassifier = req ->
        {
            classifyCallCount.incrementAndGet();
            return CompletableFuture.completedFuture(
                ClassificationResult.unidentified(req.centerFrequencyHz(), List.of(), Double.NaN));
        };

        // First cycle: survey returns FREQ_A; subsequent cycles: survey returns nothing
        AtomicInteger surveyCallCount = new AtomicInteger(0);
        SpectralSurveyApi switchingSurvey = (minHz, maxHz, dwell, threshold, progress, tunerControl) ->
        {
            int call = surveyCallCount.incrementAndGet();
            List<EnergyPeak> peaks = (call == 1) ? List.of(makePeak(FREQ_A)) : List.of();
            if(progress != null) progress.onProgress(1.0);
            return CompletableFuture.completedFuture(peaks);
        };

        ScanRequest continuous = new ScanRequest(MIN_HZ, MAX_HZ,
            EnumSet.of(DecoderType.NBFM),
            Duration.ofMillis(1), 6.0, 200,
            true, Duration.ofMillis(50));

        BandScanController ctrl = makeController(switchingSurvey, countingClassifier);
        ctrl.startScan(continuous);

        // Wait for first cycle to finish probing (FREQ_A is added and probed)
        awaitState(ctrl, ScanState.IDLE_CONTINUOUS, 5_000);

        // Mark the discovery as watched
        Discovery d = mModel.getDiscoveries().stream()
            .filter(x -> x.getCenterFrequencyHz() == FREQ_A)
            .findFirst().orElseThrow();
        ctrl.setWatched(d, true);

        int callCountAfterFirstCycle = classifyCallCount.get();

        // Wait for at least one more cycle (survey returns nothing, but watched+UNIDENTIFIED should be re-probed)
        awaitState(ctrl, ScanState.SURVEYING, 5_000);
        awaitState(ctrl, ScanState.IDLE_CONTINUOUS, 5_000);

        ctrl.stop();

        // Must have probed again even though the survey returned no energy at FREQ_A
        assertTrue(classifyCallCount.get() > callCountAfterFirstCycle,
            "Watched UNIDENTIFIED should be re-probed even when survey returns no peak at that frequency");
    }

    // -------------------------------------------------------------------------
    // Task 5.2: stepped-sweep path in BandScanController
    // -------------------------------------------------------------------------

    /**
     * A {@link SpectralSurveyApi} fake that records calls to {@code survey} and captures
     * the {@link TunerControl} passed to it.
     *
     * <p>When {@code failSurvey} is {@code true}, calls to {@link #survey} complete
     * exceptionally with a descriptive error message (simulating e.g. tuner unavailable).</p>
     */
    private static class RecordingWideSurvey implements SpectralSurveyApi
    {
        private final List<EnergyPeak> mPeaks;
        private final boolean mFailSurvey;
        private final AtomicInteger mSurveyCallCount = new AtomicInteger(0);
        private final AtomicReference<TunerControl> mLastTunerControl = new AtomicReference<>(null);

        RecordingWideSurvey(List<EnergyPeak> peaks)
        {
            this(peaks, false);
        }

        RecordingWideSurvey(List<EnergyPeak> peaks, boolean failSurvey)
        {
            mPeaks = peaks;
            mFailSurvey = failSurvey;
        }

        /** Legacy 3-arg constructor kept for call-site compatibility. */
        RecordingWideSurvey(List<EnergyPeak> ignoredInBandPeaks, List<EnergyPeak> peaks, boolean failSurvey)
        {
            this(failSurvey ? List.of() : peaks, failSurvey);
        }

        @Override
        public CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                                           double thresholdDb,
                                                           SpectralSurvey.ProgressListener progress,
                                                           TunerControl tunerControl)
        {
            mSurveyCallCount.incrementAndGet();
            mLastTunerControl.set(tunerControl);

            if(mFailSurvey)
            {
                CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException(
                    "Band scan requires the spectral display to be showing a tuner."));
                return failed;
            }

            if(progress != null) progress.onProgress(1.0);
            return CompletableFuture.completedFuture(mPeaks);
        }

        int getSurveyCallCount()           { return mSurveyCallCount.get(); }
        TunerControl getLastTunerControl() { return mLastTunerControl.get(); }
    }

    /**
     * Minimal {@link TunerControl} stub for tests — no actual hardware.
     */
    private static class StubTunerControl implements TunerControl
    {
        @Override public long getCurrentCenterFreqHz()  { return MIN_HZ + (MAX_HZ - MIN_HZ) / 2; }
        @Override public long getUsableBandwidthHz()    { return 2_000_000L; }
        @Override public long getMinFrequencyHz()       { return MIN_HZ; }
        @Override public long getMaxFrequencyHz()       { return MAX_HZ; }
        @Override public void setCenterFreqHz(long hz)  { /* no-op for test */ }
        @Override public boolean isAvailable()          { return true; }
        @Override public double getCurrentSampleRateHz() { return 2_000_000.0; }
        @Override public void addWidebandSampleListener(
            io.github.dsheirer.sample.Listener<io.github.dsheirer.sample.complex.ComplexSamples> listener) { /* no-op */ }
        @Override public void removeWidebandSampleListener(
            io.github.dsheirer.sample.Listener<io.github.dsheirer.sample.complex.ComplexSamples> listener) { /* no-op */ }
    }

    /** Creates a controller with an explicit {@link TunerControl} for stepped-sweep tests. */
    private BandScanController makeControllerWithTuner(SpectralSurveyApi survey,
                                                        Classifier classifier,
                                                        TunerControl tunerControl)
    {
        return new BandScanController(
            classifier,
            survey,
            mModel,
            mChannelModel,
            new ChannelProcessingManagerAdapter(mChannelProcessingManager),
            mChannelFactory,
            mUserPreferences,
            mExecutor,
            tunerControl);
    }

    @Test
    void surveyReceivesTunerControlFromController() throws InterruptedException
    {
        // The TunerControl from the controller should be forwarded to survey().
        TunerControl tuner = new StubTunerControl();
        List<EnergyPeak> peaks = List.of(makePeak(FREQ_A), makePeak(FREQ_B));

        RecordingWideSurvey survey = new RecordingWideSurvey(peaks, false);
        FakeClassifier classifier = new FakeClassifier(Map.of());

        BandScanController ctrl = makeControllerWithTuner(survey, classifier, tuner);
        ctrl.startScan(simpleScan());

        awaitState(ctrl, ScanState.DONE, 5_000);

        // survey() is called once and receives the controller's TunerControl
        assertEquals(1, survey.getSurveyCallCount(),
            "survey should be called exactly once");

        assertNotNull(survey.getLastTunerControl(),
            "TunerControl from the controller should be forwarded to survey()");

        // Both peaks should be discovered
        assertEquals(2, mModel.getDiscoveries().size(),
            "Both peaks returned by survey() should be probed and discovered");
    }

    @Test
    void surveyPeaksAreDiscoveredWhenSurveySucceeds() throws InterruptedException
    {
        // The survey succeeds and returns one peak; it should be discovered.
        List<EnergyPeak> peaks = List.of(makePeak(FREQ_A));
        RecordingWideSurvey survey = new RecordingWideSurvey(peaks, false);
        FakeClassifier classifier = new FakeClassifier(Map.of());

        BandScanController ctrl = makeControllerWithTuner(survey, classifier, new StubTunerControl());
        ctrl.startScan(simpleScan());

        awaitState(ctrl, ScanState.DONE, 5_000);

        assertEquals(1, survey.getSurveyCallCount(),
            "survey should be called exactly once");
        assertEquals(1, mModel.getDiscoveries().size(),
            "Peak from survey() should be discovered");
    }

    @Test
    void noSurveyCalledWhenTunerControlIsNull() throws InterruptedException
    {
        // When TunerControl is null, BandScanController fails immediately with ERROR
        // without calling survey at all.
        RecordingWideSurvey survey = new RecordingWideSurvey(List.of(), false);
        FakeClassifier classifier = new FakeClassifier(Map.of());

        // Build controller with null TunerControl explicitly
        BandScanController ctrl = new BandScanController(
            classifier, survey, mModel, mChannelModel,
            new ChannelProcessingManagerAdapter(mChannelProcessingManager),
            mChannelFactory, mUserPreferences, mExecutor,
            null); // null tunerControl → immediate ERROR

        ctrl.startScan(simpleScan());

        awaitState(ctrl, ScanState.ERROR, 5_000);

        assertEquals(0, survey.getSurveyCallCount(),
            "survey should NOT be called when TunerControl is null");
        assertEquals(ScanState.ERROR, ctrl.getScanState(),
            "State should be ERROR when TunerControl is null");
        assertNotNull(ctrl.getLastErrorMessage(),
            "Error message should be set when TunerControl is null");
    }
}
