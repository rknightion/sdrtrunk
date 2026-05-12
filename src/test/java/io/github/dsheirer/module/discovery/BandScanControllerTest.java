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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 */
@Timeout(30)
class BandScanControllerTest
{
    private static final long MIN_HZ = 150_000_000L;
    private static final long MAX_HZ = 160_000_000L;
    private static final long FREQ_A = 154_025_000L;
    private static final long FREQ_B = 155_000_000L;

    private ExecutorService mExecutor;
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

        FakeSurvey(List<EnergyPeak> peaks)
        {
            mPeaks = peaks;
        }

        @Override
        public CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                                           double thresholdDb,
                                                           SpectralSurvey.ProgressListener progress)
        {
            if(progress != null)
            {
                progress.onProgress(1.0);
            }
            return CompletableFuture.completedFuture(Collections.unmodifiableList(mPeaks));
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
        // Clear any DiscoveryPreference state persisted from prior test runs (the ignore list
        // in particular is written to the real OS-level java.util.prefs store and bleeds
        // between test methods/runs if not explicitly reset).
        Preferences.userNodeForPackage(DiscoveryPreference.class).clear();

        mExecutor = Executors.newCachedThreadPool(r ->
        {
            Thread t = new Thread(r, "test-discovery-worker");
            t.setDaemon(true);
            return t;
        });

        mUserPreferences = new UserPreferences();
        mModel = new DiscoveryModel();
        mChannelModel = new ChannelModel(new AliasModel());
        mChannelProcessingManager = new RecordingChannelProcessingManager();
        mChannelFactory = new DiscoveryChannelFactory();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        mExecutor.shutdownNow();
        // Clean up preferences written during this test so subsequent runs start clean.
        Preferences.userNodeForPackage(DiscoveryPreference.class).clear();
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
            mExecutor);
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
    // ENERGY_DETECTED rows added before probing
    // -------------------------------------------------------------------------

    @Test
    void energyDetectedRowsAddedBeforeProbing() throws InterruptedException
    {
        // Classifier blocks momentarily so we can see the ENERGY_DETECTED rows
        List<Long> energyDetectedFreqs = new ArrayList<>();

        Classifier blockingClassifier = req ->
        {
            // Capture the state of the model at the start of each classify call
            for(Discovery d : mModel.getDiscoveries())
            {
                if(d.getState() == DiscoveryState.ENERGY_DETECTED || d.getState() == DiscoveryState.PROBING)
                {
                    // We can see the row exists before the classify completes
                }
            }
            return CompletableFuture.completedFuture(
                ClassificationResult.unidentified(req.centerFrequencyHz(), List.of(), Double.NaN));
        };

        List<EnergyPeak> peaks = List.of(makePeak(FREQ_A), makePeak(FREQ_B));
        BandScanController ctrl = makeController(new FakeSurvey(peaks), blockingClassifier);

        // Subscribe to ADDED events before starting the scan
        List<DiscoveryEvent> addedEvents = new ArrayList<>();
        mModel.addListener(e -> {
            if(e.type() == DiscoveryEvent.Type.ADDED)
            {
                addedEvents.add(e);
            }
        });

        ctrl.startScan(simpleScan());
        awaitState(ctrl, ScanState.DONE, 5_000);

        // Both peaks should have generated ADDED events
        assertEquals(2, addedEvents.size(), "Expected 2 ADDED events, one per peak");
        List<Long> addedFreqs = addedEvents.stream()
            .map(e -> e.discovery().getCenterFrequencyHz())
            .toList();
        assertTrue(addedFreqs.contains(FREQ_A));
        assertTrue(addedFreqs.contains(FREQ_B));
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
            mChannelFactory, mUserPreferences, mExecutor);

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
    // stop()
    // -------------------------------------------------------------------------

    @Test
    void stopSetsCancelledState() throws InterruptedException
    {
        // Use a slow survey to ensure we can stop before it completes
        SpectralSurveyApi slowSurvey = (minHz, maxHz, dwell, thresholdDb, progress) ->
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
}
