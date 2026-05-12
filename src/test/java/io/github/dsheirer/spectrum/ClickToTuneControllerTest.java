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
package io.github.dsheirer.spectrum;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.discovery.Candidate;
import io.github.dsheirer.module.discovery.ClassificationOutcome;
import io.github.dsheirer.module.discovery.ClassificationResult;
import io.github.dsheirer.module.discovery.DiscoveryChannelFactory;
import io.github.dsheirer.module.discovery.LockState;
import io.github.dsheirer.module.discovery.SignalClassifier;
import io.github.dsheirer.module.discovery.SignalKind;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClickToTuneController} using hand-fakes for dependencies.
 *
 * <p>Tests verify the logic paths, not the Swing painting.  Because the controller marshals
 * callbacks onto the Swing EDT, each test calls
 * {@code SwingUtilities.invokeAndWait(() -> {})} after triggering an action to let the EDT
 * drain before asserting results.</p>
 */
class ClickToTuneControllerTest
{
    private static final long FREQ = 154_920_000L;

    // --- fakes ----------------------------------------------------------------

    private FakeSignalClassifier mFakeClassifier;
    private FakeChannelModel mFakeChannelModel;
    private FakeChannelProcessingManager mFakeCpm;
    private FakeUICallbacks mFakeUI;
    private ClickToTuneController mController;

    // -------------------------------------------------------------------------
    // Fake / stub implementations
    // -------------------------------------------------------------------------

    /** Fake SignalClassifier that returns a pre-configured future. */
    static class FakeSignalClassifier extends SignalClassifier
    {
        private CompletableFuture<ClassificationResult> mNextResult;
        /** Every ClassificationRequest received by classify(), in call order. */
        private final List<io.github.dsheirer.module.discovery.ClassificationRequest> mReceivedRequests
            = new ArrayList<>();

        FakeSignalClassifier()
        {
            // Pass nulls; we override classify() and never call super.
            super(null, null, null, null);
        }

        void setNextResult(ClassificationResult result)
        {
            mNextResult = CompletableFuture.completedFuture(result);
        }

        void setNextResult(CompletableFuture<ClassificationResult> future)
        {
            mNextResult = future;
        }

        List<io.github.dsheirer.module.discovery.ClassificationRequest> getReceivedRequests()
        {
            return mReceivedRequests;
        }

        @Override
        public CompletableFuture<ClassificationResult> classify(
            io.github.dsheirer.module.discovery.ClassificationRequest request)
        {
            mReceivedRequests.add(request);
            return mNextResult != null ? mNextResult : CompletableFuture.completedFuture(
                ClassificationResult.error(request.centerFrequencyHz(), "no result configured"));
        }
    }

    /** Recording fake for ChannelModel that captures add/remove calls. */
    static class FakeChannelModel extends ChannelModel
    {
        final List<Channel> mAdded = new ArrayList<>();
        final List<Channel> mRemoved = new ArrayList<>();

        FakeChannelModel()
        {
            super(new AliasModel());
        }

        @Override
        public void addChannel(Channel channel)
        {
            mAdded.add(channel);
            super.addChannel(channel);
        }

        @Override
        public void removeChannel(Channel channel)
        {
            mRemoved.add(channel);
            super.removeChannel(channel);
        }

        /**
         * Simulates the user deleting a channel (e.g. via the playlist editor).
         * Calls {@link #removeChannel(Channel)} which triggers the underlying list-change
         * listener and broadcasts a NOTIFICATION_DELETE event to all registered listeners.
         */
        void simulateUserDelete(Channel channel)
        {
            removeChannel(channel);
        }
    }

    /** Recording fake for ChannelProcessingManager — throws on demand. */
    static class FakeChannelProcessingManager extends ChannelProcessingManager
    {
        final List<Channel> mStarted = new ArrayList<>();
        final List<Channel> mStopped = new ArrayList<>();
        boolean mThrowOnStart = false;

        FakeChannelProcessingManager()
        {
            super(null, null, null, null, null);
        }

        @Override
        public void start(Channel channel) throws ChannelException
        {
            if(mThrowOnStart)
            {
                throw new ChannelException("No Tuner Available");
            }

            mStarted.add(channel);
        }

        @Override
        public void stop(Channel channel) throws ChannelException
        {
            mStopped.add(channel);
        }
    }

    /** Recording fake for UICallbacks. */
    static class FakeUICallbacks implements ClickToTuneController.UICallbacks
    {
        boolean mPendingShown;
        boolean mPendingCleared;
        ClassificationResult mMissResult;
        boolean mStartFailureReported;
        Channel mFailedChannel;
        Runnable mRedetectCallback;
        Consumer<DecoderType> mTuneAsCallback;

        @Override
        public void showPending(long centerFreqHz, int widthHz)
        {
            mPendingShown = true;
        }

        @Override
        public void clearPending()
        {
            mPendingCleared = true;
        }

        @Override
        public void showMissPopup(ClassificationResult result, Runnable redetect, Consumer<DecoderType> tuneAs)
        {
            mMissResult = result;
            mRedetectCallback = redetect;
            mTuneAsCallback = tuneAs;
        }

        @Override
        public void reportStartFailure(Channel channel, String reason)
        {
            mStartFailureReported = true;
            mFailedChannel = channel;
        }

        void reset()
        {
            mPendingShown = false;
            mPendingCleared = false;
            mMissResult = null;
            mStartFailureReported = false;
            mFailedChannel = null;
            mRedetectCallback = null;
            mTuneAsCallback = null;
        }
    }

    // -------------------------------------------------------------------------
    // Set up
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp()
    {
        mFakeClassifier = new FakeSignalClassifier();
        mFakeChannelModel = new FakeChannelModel();
        mFakeCpm = new FakeChannelProcessingManager();
        mFakeUI = new FakeUICallbacks();

        mController = new ClickToTuneController(
            mFakeClassifier,
            mFakeChannelModel,
            mFakeCpm,
            new DiscoveryChannelFactory(),
            new UserPreferences(),
            mFakeUI
        );
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void classifyAndTune_identified_channelAddedAndStarted() throws Exception
    {
        ClassificationResult identified = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.95, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL,
            "NBFM",
            Map.of(),
            -80.0
        );

        mFakeClassifier.setNextResult(identified);

        mController.classifyAndTune(FREQ, 12_500);

        // Drain the EDT
        drainEdt();

        assertTrue(mFakeUI.mPendingShown, "Pending overlay should be shown");
        assertTrue(mFakeUI.mPendingCleared, "Pending overlay should be cleared after result");
        assertNull(mFakeUI.mMissResult, "No miss popup on IDENTIFIED");

        assertEquals(1, mFakeChannelModel.mAdded.size(), "Channel should be added to model");
        assertEquals(1, mFakeCpm.mStarted.size(), "Channel should be started");

        Channel channel = mFakeChannelModel.mAdded.get(0);
        assertEquals(DecoderType.NBFM, channel.getDecodeConfiguration().getDecoderType());
        assertEquals(FREQ, ((SourceConfigTuner) channel.getSourceConfiguration()).getFrequency());
        assertTrue(mController.getClickToTuneChannels().contains(channel));
    }

    @Test
    void classifyAndTune_noSignal_showsMissPopup() throws Exception
    {
        mFakeClassifier.setNextResult(ClassificationResult.noSignal(FREQ, Double.NaN));

        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertTrue(mFakeUI.mPendingCleared);
        assertNotNull(mFakeUI.mMissResult, "Miss popup should appear");
        assertEquals(ClassificationOutcome.NO_SIGNAL, mFakeUI.mMissResult.outcome());
        assertTrue(mFakeChannelModel.mAdded.isEmpty(), "No channel added on miss");
        assertTrue(mFakeCpm.mStarted.isEmpty());
    }

    @Test
    void classifyAndTune_unidentified_showsMissPopup() throws Exception
    {
        mFakeClassifier.setNextResult(ClassificationResult.unidentified(FREQ, List.of(), -70.0));

        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertNotNull(mFakeUI.mMissResult);
        assertEquals(ClassificationOutcome.UNIDENTIFIED, mFakeUI.mMissResult.outcome());
    }

    @Test
    void classifyAndTune_startFails_channelRemovedAndFailureReported() throws Exception
    {
        ClassificationResult identified = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.DMR, LockState.LOCKED, 0.9, null)),
            DecoderType.DMR,
            DecoderFactory.getDecodeConfiguration(DecoderType.DMR),
            SignalKind.CONTROL,
            "DMR control",
            Map.of(),
            -65.0
        );

        mFakeClassifier.setNextResult(identified);
        mFakeCpm.mThrowOnStart = true;

        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertTrue(mFakeUI.mStartFailureReported, "Failure should be reported to UI");
        assertEquals(1, mFakeChannelModel.mAdded.size(), "Channel should have been added...");
        assertEquals(1, mFakeChannelModel.mRemoved.size(), "...then removed on failure");
        assertSame(mFakeChannelModel.mAdded.get(0), mFakeChannelModel.mRemoved.get(0));
    }

    @Test
    void tuneAs_createsAndStartsChannelWithCorrectDecoder() throws Exception
    {
        mController.tuneAs(FREQ, DecoderType.P25_PHASE1);
        drainEdt();

        assertEquals(1, mFakeChannelModel.mAdded.size());
        assertEquals(1, mFakeCpm.mStarted.size());
        Channel channel = mFakeChannelModel.mAdded.get(0);
        assertEquals(DecoderType.P25_PHASE1, channel.getDecodeConfiguration().getDecoderType());
        assertEquals(FREQ, ((SourceConfigTuner) channel.getSourceConfiguration()).getFrequency());
    }

    @Test
    void missPopup_keepListeningCallback_reclassifies() throws Exception
    {
        // First call: no signal -> miss popup shown
        mFakeClassifier.setNextResult(ClassificationResult.noSignal(FREQ, Double.NaN));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertNotNull(mFakeUI.mRedetectCallback);

        // Capture the callback BEFORE reset(), then configure a successful result
        Runnable redetect = mFakeUI.mRedetectCallback;

        ClassificationResult identified = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        );
        mFakeClassifier.setNextResult(identified);

        redetect.run();
        drainEdt();

        assertEquals(1, mFakeChannelModel.mAdded.size());
        assertEquals(1, mFakeCpm.mStarted.size());
    }

    @Test
    void missPopup_tuneAsCallback_createsChannelWithChosenDecoder() throws Exception
    {
        mFakeClassifier.setNextResult(ClassificationResult.noSignal(FREQ, Double.NaN));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertNotNull(mFakeUI.mTuneAsCallback);

        mFakeUI.mTuneAsCallback.accept(DecoderType.DMR);
        drainEdt();

        assertEquals(1, mFakeChannelModel.mAdded.size());
        assertEquals(DecoderType.DMR, mFakeChannelModel.mAdded.get(0).getDecodeConfiguration().getDecoderType());
    }

    // -------------------------------------------------------------------------
    // Fix #1: changeDecoder must NOT re-add the channel to the model
    // -------------------------------------------------------------------------

    @Test
    void changeDecoder_doesNotReAddChannelToModel() throws Exception
    {
        // Create a click-to-tune channel via classifyAndTune
        mFakeClassifier.setNextResult(ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        ));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertEquals(1, mFakeChannelModel.mAdded.size(), "Initially one channel in model");
        Channel channel = mFakeChannelModel.mAdded.get(0);

        // Change decoder -- must NOT add to model again
        mController.changeDecoder(channel, DecoderType.DMR);

        assertEquals(1, mFakeChannelModel.mAdded.size(),
            "Model size must not grow on changeDecoder (no duplicate add)");
        assertEquals(0, mFakeChannelModel.mRemoved.size(),
            "Channel must not be removed from model on changeDecoder");
        assertEquals(1, mFakeCpm.mStopped.size(), "Channel should be stopped before decoder change");
        assertEquals(2, mFakeCpm.mStarted.size(), "Channel should be restarted (2 starts total)");
        assertEquals(DecoderType.DMR, channel.getDecodeConfiguration().getDecoderType(),
            "Decoder type should be updated");
        assertTrue(mController.getClickToTuneChannels().contains(channel),
            "Channel should remain in click-to-tune set");
    }

    @Test
    void changeDecoder_stopsAndRestartsWithNewDecoder() throws Exception
    {
        mFakeClassifier.setNextResult(ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        ));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        Channel channel = mFakeChannelModel.mAdded.get(0);

        mController.changeDecoder(channel, DecoderType.DMR);

        assertEquals(1, mFakeCpm.mStopped.size(), "Channel should be stopped before decoder change");
        assertEquals(2, mFakeCpm.mStarted.size(), "Channel should be restarted with new decoder");
        assertEquals(DecoderType.DMR, channel.getDecodeConfiguration().getDecoderType(),
            "Channel decode config should be updated to DMR");
    }

    // -------------------------------------------------------------------------
    // Fix #2: redetect must operate on the existing channel, not orphan it
    // -------------------------------------------------------------------------

    @Test
    void redetect_identified_swapsConfigAndRestartsExistingChannel() throws Exception
    {
        // Create a channel with NBFM
        mFakeClassifier.setNextResult(ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        ));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertEquals(1, mFakeChannelModel.mAdded.size());
        Channel channel = mFakeChannelModel.mAdded.get(0);
        assertEquals(DecoderType.NBFM, channel.getDecodeConfiguration().getDecoderType());

        // Re-detect: classifier now returns DMR
        mFakeClassifier.setNextResult(ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.DMR, LockState.LOCKED, 0.95, null)),
            DecoderType.DMR,
            DecoderFactory.getDecodeConfiguration(DecoderType.DMR),
            SignalKind.CONTROL, "", Map.of(), -65.0
        ));
        mController.redetect(channel);
        drainEdt();

        // Model must still have exactly 1 channel -- the original one
        assertEquals(1, mFakeChannelModel.mAdded.size(),
            "redetect must NOT add a new channel to the model");
        assertEquals(0, mFakeChannelModel.mRemoved.size(),
            "redetect must NOT remove the existing channel");
        assertSame(channel, mFakeChannelModel.mAdded.get(0),
            "The existing channel object must be reused");

        // Config should be updated to DMR
        assertEquals(DecoderType.DMR, channel.getDecodeConfiguration().getDecoderType(),
            "Channel decoder should be updated to DMR after successful re-detect");

        // Channel should have been restarted (1 start from classifyAndTune + 1 from redetect)
        assertEquals(2, mFakeCpm.mStarted.size(), "Channel should be restarted after re-detect");
        assertTrue(mController.getClickToTuneChannels().contains(channel));
    }

    @Test
    void redetect_miss_leavesChannelIntact() throws Exception
    {
        // Create a channel with NBFM
        mFakeClassifier.setNextResult(ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        ));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        Channel channel = mFakeChannelModel.mAdded.get(0);

        // Re-detect: no signal
        mFakeClassifier.setNextResult(ClassificationResult.noSignal(FREQ, Double.NaN));
        mController.redetect(channel);
        drainEdt();

        // Model must still have exactly 1 channel
        assertEquals(1, mFakeChannelModel.mAdded.size(),
            "redetect miss must NOT add a new channel");
        assertEquals(0, mFakeChannelModel.mRemoved.size(),
            "redetect miss must NOT remove the existing channel");
        assertSame(channel, mFakeChannelModel.mAdded.get(0));

        // The miss popup should be shown with the miss result
        assertNotNull(mFakeUI.mMissResult, "Miss popup should be shown after no-signal re-detect");
        assertEquals(ClassificationOutcome.NO_SIGNAL, mFakeUI.mMissResult.outcome());

        // Channel should have been restarted even on miss (restored to original running state)
        // 1 start from classifyAndTune + 1 restart from redetect miss path
        assertEquals(2, mFakeCpm.mStarted.size(), "Channel should be restarted after re-detect miss");
    }

    // -------------------------------------------------------------------------
    // Fix #3: overlapping classifications
    // -------------------------------------------------------------------------

    @Test
    void classifyAndTune_overlapping_onlyOneChannelCreated() throws Exception
    {
        // First call: use a never-completing future
        CompletableFuture<ClassificationResult> firstFuture = new CompletableFuture<>();
        mFakeClassifier.setNextResult(firstFuture);
        mController.classifyAndTune(FREQ, 12_500);

        // Second call before first completes -- should cancel the first
        ClassificationResult secondResult = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        );
        mFakeClassifier.setNextResult(secondResult);
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        // First future should have been cancelled
        assertTrue(firstFuture.isCancelled(), "First pending future should have been cancelled");

        // Exactly one channel should be created (from the second call)
        assertEquals(1, mFakeChannelModel.mAdded.size(),
            "Exactly one channel should be created when two calls overlap");
        assertEquals(1, mFakeCpm.mStarted.size());
    }

    @Test
    void classifyAndTune_overlapping_overlayEndsCleared() throws Exception
    {
        // First call: never-completing
        CompletableFuture<ClassificationResult> firstFuture = new CompletableFuture<>();
        mFakeClassifier.setNextResult(firstFuture);
        mController.classifyAndTune(FREQ, 12_500);

        // Second call: immediate result
        ClassificationResult noSignal = ClassificationResult.noSignal(FREQ, Double.NaN);
        mFakeClassifier.setNextResult(noSignal);
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        // The overlay should be cleared after the second call's result
        assertTrue(mFakeUI.mPendingCleared, "Pending overlay should be cleared after second call completes");
    }

    // -------------------------------------------------------------------------
    // Fix #6: pruning mClickToTuneChannels on channel removal
    // -------------------------------------------------------------------------

    @Test
    void channelRemoved_prunedFromClickToTuneSet() throws Exception
    {
        // Create a channel
        mFakeClassifier.setNextResult(ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        ));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        Channel channel = mFakeChannelModel.mAdded.get(0);
        assertTrue(mController.getClickToTuneChannels().contains(channel),
            "Channel should be in click-to-tune set after creation");

        // Simulate deletion via ChannelModel (e.g. user removes channel in playlist editor)
        mFakeChannelModel.simulateUserDelete(channel);

        assertFalse(mController.getClickToTuneChannels().contains(channel),
            "Channel should be removed from click-to-tune set when deleted from model");
    }

    @Test
    void cancelPending_cancelsInProgressFuture() throws Exception
    {
        CompletableFuture<ClassificationResult> pending = new CompletableFuture<>();
        mFakeClassifier.setNextResult(pending);

        mController.classifyAndTune(FREQ, 12_500);

        assertTrue(mFakeUI.mPendingShown);

        mController.cancelPending();

        assertTrue(pending.isCancelled(), "Future should be cancelled");
    }

    // -------------------------------------------------------------------------
    // Task 5.3: keep-listening uses extended deadline, not seconds-as-bandwidth
    // -------------------------------------------------------------------------

    /**
     * Verifies that invoking the "keep listening" callback from the miss popup
     * causes the next {@link ClassificationRequest} to use the keep-listening duration
     * from preferences as its {@code overallDeadline} rather than the default 12 s.
     *
     * <p>The bug this guards against: the old code passed
     * {@code keepListeningDuration().toSeconds()} as the bandwidth parameter (approxBwHz),
     * which had no effect on the probing window at all.</p>
     */
    @Test
    void keepListeningCallback_usesExtendedDeadline() throws Exception
    {
        // First call: no signal -> miss popup shown
        mFakeClassifier.setNextResult(ClassificationResult.noSignal(FREQ, Double.NaN));
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertNotNull(mFakeUI.mRedetectCallback, "Miss popup should have been shown");

        // Configure a second result for the keep-listening re-classify
        mFakeClassifier.setNextResult(ClassificationResult.noSignal(FREQ, Double.NaN));
        mFakeUI.mRedetectCallback.run();
        drainEdt();

        // There should be exactly 2 classify() calls: one for classifyAndTune, one for keep-listening
        List<io.github.dsheirer.module.discovery.ClassificationRequest> requests =
            mFakeClassifier.getReceivedRequests();
        assertEquals(2, requests.size(), "Exactly 2 classify calls expected");

        io.github.dsheirer.module.discovery.ClassificationRequest keepListeningRequest = requests.get(1);

        // The keep-listening deadline should be >= default (12 s) and match prefs
        Duration defaultDeadline = io.github.dsheirer.module.discovery.ClassificationRequest.DEFAULT_DEADLINE;
        Duration expectedDeadline = new UserPreferences().getDiscoveryPreference().getKeepListeningDuration();

        assertTrue(
            keepListeningRequest.overallDeadline().compareTo(defaultDeadline) >= 0,
            "Keep-listening request deadline (" + keepListeningRequest.overallDeadline()
                + ") should be >= default deadline (" + defaultDeadline + ")");

        assertEquals(expectedDeadline, keepListeningRequest.overallDeadline(),
            "Keep-listening request should use the preference keep-listening duration as its deadline");

        // The bandwidth should NOT be the duration-as-seconds (that was the old bug)
        long badBandwidth = expectedDeadline.getSeconds();
        assertNotEquals((int) badBandwidth, keepListeningRequest.approximateBandwidthHz(),
            "Keep-listening request must NOT pass keep-listening seconds as bandwidth");
    }

    // -------------------------------------------------------------------------
    // Task 5.3: PARTIAL candidates are visible in the miss-popup result
    // -------------------------------------------------------------------------

    /**
     * Verifies that when the classifier returns UNIDENTIFIED with PARTIAL candidates,
     * the {@code result} passed to {@link ClickToTuneController.UICallbacks#showMissPopup}
     * contains those candidates so the UI layer can offer "start it anyway as X?" actions.
     */
    @Test
    void unidentifiedWithPartialCandidates_resultPassedToMissPopup() throws Exception
    {
        List<Candidate> partials = List.of(
            new Candidate(DecoderType.NBFM,     LockState.PARTIAL, 0.4, "brief sync"),
            new Candidate(DecoderType.P25_PHASE1, LockState.NONE,  0.0, null)
        );
        ClassificationResult unidentified =
            ClassificationResult.unidentified(FREQ, partials, -75.0);

        mFakeClassifier.setNextResult(unidentified);
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertNotNull(mFakeUI.mMissResult, "Miss popup should be shown for UNIDENTIFIED");
        assertEquals(ClassificationOutcome.UNIDENTIFIED, mFakeUI.mMissResult.outcome());

        // The full result including partial candidates must be passed through
        long partialCount = mFakeUI.mMissResult.candidates().stream()
            .filter(c -> c.lockState() == LockState.PARTIAL)
            .count();
        assertEquals(1, partialCount,
            "One PARTIAL candidate should be present in the miss-popup result");

        // Verify the specific decoder
        DecoderType partialDecoder = mFakeUI.mMissResult.candidates().stream()
            .filter(c -> c.lockState() == LockState.PARTIAL)
            .map(Candidate::decoderType)
            .findFirst().orElse(null);
        assertEquals(DecoderType.NBFM, partialDecoder,
            "NBFM should be the PARTIAL candidate passed to miss popup");
    }

    /**
     * Verifies that a result with no PARTIAL candidates contains an empty PARTIAL set
     * so the UI layer correctly shows no "start it anyway as X?" buttons.
     */
    @Test
    void unidentifiedWithNoCandidates_missPopupShowsNoPartials() throws Exception
    {
        ClassificationResult unidentified = ClassificationResult.unidentified(FREQ, List.of(), -75.0);

        mFakeClassifier.setNextResult(unidentified);
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        assertNotNull(mFakeUI.mMissResult);
        long partialCount = mFakeUI.mMissResult.candidates().stream()
            .filter(c -> c.lockState() == LockState.PARTIAL)
            .count();
        assertEquals(0, partialCount, "No PARTIAL candidates when none were returned");
    }

    // -------------------------------------------------------------------------
    // EDT drain helper
    // -------------------------------------------------------------------------

    /**
     * Drains the Swing EDT queue by submitting a no-op task and waiting for it.
     * This ensures any {@code SwingUtilities.invokeLater} callbacks from the controller
     * have executed before we assert.
     */
    private static void drainEdt() throws Exception
    {
        SwingUtilities.invokeAndWait(() -> {
        });
    }
}
