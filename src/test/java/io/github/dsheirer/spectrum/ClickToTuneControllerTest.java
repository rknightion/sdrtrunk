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
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.discovery.ClassificationOutcome;
import io.github.dsheirer.module.discovery.ClassificationResult;
import io.github.dsheirer.module.discovery.DiscoveryChannelFactory;
import io.github.dsheirer.module.discovery.LockState;
import io.github.dsheirer.module.discovery.SignalClassifier;
import io.github.dsheirer.module.discovery.SignalKind;
import io.github.dsheirer.module.discovery.Candidate;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
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

        @Override
        public CompletableFuture<ClassificationResult> classify(
            io.github.dsheirer.module.discovery.ClassificationRequest request)
        {
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
        // First call: no signal → miss popup shown
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

    @Test
    void changeDecoder_stopsAndRestartsWithNewDecoder() throws Exception
    {
        // First, create a click-to-tune channel
        ClassificationResult identified = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.9, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL, "", Map.of(), -80.0
        );
        mFakeClassifier.setNextResult(identified);
        mController.classifyAndTune(FREQ, 12_500);
        drainEdt();

        Channel channel = mFakeChannelModel.mAdded.get(0);

        // Now change decoder
        mController.changeDecoder(channel, DecoderType.DMR);

        assertEquals(1, mFakeCpm.mStopped.size(), "Channel should be stopped before decoder change");
        // After changeDecoder, start is called again → 2 starts total (1 from classifyAndTune, 1 from changeDecoder)
        assertEquals(2, mFakeCpm.mStarted.size(), "Channel should be restarted with new decoder");
        assertEquals(DecoderType.DMR, channel.getDecodeConfiguration().getDecoderType(),
            "Channel decode config should be updated to DMR");
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
