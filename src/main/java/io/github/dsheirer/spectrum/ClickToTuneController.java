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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelException;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.ChannelModel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.discovery.ClassificationOutcome;
import io.github.dsheirer.module.discovery.ClassificationRequest;
import java.time.Duration;
import io.github.dsheirer.module.discovery.ClassificationResult;
import io.github.dsheirer.module.discovery.DiscoveryChannelFactory;
import io.github.dsheirer.module.discovery.SignalClassifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestrates a single click-to-tune action on the spectral display.
 *
 * <h3>Happy path</h3>
 * <ol>
 *   <li>{@link #classifyAndTune(long, int)} → show pending overlay, call
 *       {@link SignalClassifier#classify(ClassificationRequest)}</li>
 *   <li>On {@link ClassificationOutcome#IDENTIFIED}: build a {@link Channel} via
 *       {@link DiscoveryChannelFactory}, add it to the {@link ChannelModel}, start it via
 *       {@link ChannelProcessingManager}.</li>
 *   <li>On {@code UNIDENTIFIED} / {@code NO_SIGNAL} / {@code ERROR}: invoke the miss-popup
 *       callback with the result and a set of offered actions.</li>
 * </ol>
 *
 * <h3>UI callbacks</h3>
 * The controller intentionally has no direct Swing dependency beyond the
 * {@code SwingUtilities.invokeLater} calls that marshal results onto the EDT.
 * UI concerns (pending overlay, miss popup) are delegated to the {@link UICallbacks}
 * interface so the logic can be tested against fakes.
 *
 * <h3>Thread safety</h3>
 * {@link #classifyAndTune} / {@link #tuneAs} / {@link #redetect} may be called from the EDT;
 * the callbacks are always delivered on the EDT.
 */
public class ClickToTuneController
{
    private static final Logger mLog = LoggerFactory.getLogger(ClickToTuneController.class);

    private final SignalClassifier mSignalClassifier;
    private final ChannelModel mChannelModel;
    private final ChannelProcessingManager mChannelProcessingManager;
    private final DiscoveryChannelFactory mChannelFactory;
    private final UserPreferences mUserPreferences;
    private final UICallbacks mUICallbacks;

    /**
     * Channels created by this controller (used by the override-chip / re-detect feature
     * to know which channels are "ours").
     */
    private final Set<Channel> mClickToTuneChannels = ConcurrentHashMap.newKeySet();

    /**
     * Reference to the currently in-progress classification future, so the pending overlay
     * cancel button can call {@code future.cancel(true)}.
     *
     * <p>Guards against overlapping classifications: if a new call to {@link #classifyAndTune}
     * arrives while one is in flight, the prior future is cancelled first.</p>
     */
    private final AtomicReference<CompletableFuture<ClassificationResult>> mPendingFuture =
        new AtomicReference<>();

    // -------------------------------------------------------------------------
    // UICallbacks interface
    // -------------------------------------------------------------------------

    /**
     * UI-side callbacks that {@link ClickToTuneController} invokes.
     *
     * <p>All methods are called on the Swing EDT.</p>
     */
    public interface UICallbacks
    {
        /**
         * Show the pending/in-progress overlay at the given span.
         *
         * @param centerFreqHz frequency of the signal being probed
         * @param widthHz      estimated bandwidth of the probed span
         */
        void showPending(long centerFreqHz, int widthHz);

        /**
         * Clear the pending overlay (classification finished, regardless of outcome).
         */
        void clearPending();

        /**
         * Show the "no match" popup after a miss.
         *
         * @param result   the classification result (UNIDENTIFIED / NO_SIGNAL / ERROR / CANCELLED)
         * @param redetect callback the popup should invoke when the user chooses "Keep listening"
         * @param tuneAs   callback the popup should invoke when the user picks a decoder manually;
         *                 the {@code Consumer<DecoderType>} is called with the chosen type
         */
        void showMissPopup(ClassificationResult result, Runnable redetect, Consumer<DecoderType> tuneAs);

        /**
         * Report that a channel failed to start (e.g. no tuner available).
         *
         * @param channel the channel that failed
         * @param reason  why it failed
         */
        void reportStartFailure(Channel channel, String reason);
    }

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs the controller.
     *
     * @param signalClassifier        the auto-detection engine
     * @param channelModel            model to which created channels are added
     * @param channelProcessingManager manager that starts/stops channels
     * @param channelFactory          factory that builds {@link Channel} instances
     * @param userPreferences         preference bag (for click default bandwidth, etc.)
     * @param uiCallbacks             Swing UI callbacks; must not be null
     */
    public ClickToTuneController(SignalClassifier signalClassifier,
                                  ChannelModel channelModel,
                                  ChannelProcessingManager channelProcessingManager,
                                  DiscoveryChannelFactory channelFactory,
                                  UserPreferences userPreferences,
                                  UICallbacks uiCallbacks)
    {
        mSignalClassifier = signalClassifier;
        mChannelModel = channelModel;
        mChannelProcessingManager = channelProcessingManager;
        mChannelFactory = channelFactory;
        mUserPreferences = userPreferences;
        mUICallbacks = uiCallbacks;

        // Subscribe to channel-removal events so we prune mClickToTuneChannels
        // and avoid unbounded growth if the user deletes a click-to-tune channel
        // via the playlist editor.
        mChannelModel.addListener(this::onChannelEvent);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts an auto-detection classification at the given frequency, shows the pending overlay,
     * and on success creates and starts a channel.
     *
     * <p>If a classification is already in flight it is cancelled before the new one begins.</p>
     *
     * <p>May be called from the EDT; all result handling is marshalled onto the EDT via
     * {@code SwingUtilities.invokeLater}.</p>
     *
     * @param centerFreqHz  centre frequency of the signal, in Hz
     * @param approxBwHz    operator-estimated bandwidth, in Hz (0 = unknown → default used)
     */
    public void classifyAndTune(long centerFreqHz, int approxBwHz)
    {
        int bwHz = (approxBwHz > 0) ? approxBwHz
            : mUserPreferences.getDiscoveryPreference().getClickDefaultBandwidthHz();

        // Cancel any in-progress classification before starting a new one
        CompletableFuture<ClassificationResult> prior = mPendingFuture.getAndSet(null);
        if(prior != null)
        {
            prior.cancel(true);
        }

        mUICallbacks.showPending(centerFreqHz, bwHz);

        ClassificationRequest request = ClassificationRequest.forFrequency(centerFreqHz, bwHz,
            "click-to-tune@" + centerFreqHz);

        CompletableFuture<ClassificationResult> future = mSignalClassifier.classify(request);

        // Only proceed with this result if this is still the current pending future
        mPendingFuture.set(future);

        future.whenComplete((result, ex) -> SwingUtilities.invokeLater(() ->
        {
            // Guard: only handle this result if it's still the current future
            if(!mPendingFuture.compareAndSet(future, null))
            {
                // A newer classification replaced us; silently discard this result
                return;
            }

            handleResult(result, ex);
        }));
    }

    /**
     * Cancels any in-progress classification.  Called by the pending overlay's cancel button.
     */
    public void cancelPending()
    {
        CompletableFuture<ClassificationResult> f = mPendingFuture.getAndSet(null);

        if(f != null)
        {
            f.cancel(true);
        }
    }

    /**
     * Skips the classifier and immediately creates a channel with the chosen decoder.
     *
     * <p>Used by the "Decode here as X" context-menu items.</p>
     *
     * @param freqHz    centre frequency, in Hz
     * @param type      decoder type to use
     */
    public void tuneAs(long freqHz, DecoderType type)
    {
        String aliasList = defaultAliasListName();
        Channel channel = mChannelFactory.createChannel(freqHz, type, aliasList);
        startNewChannel(channel);
    }

    /**
     * Changes the decoder on an existing click-to-tune channel: stops it, swaps the decode
     * config, preserves frequency / name / alias list, and restarts it.
     *
     * <p>This is the <em>restart</em> path — the channel is already in the model and must
     * NOT be re-added.  Only {@link ChannelProcessingManager#start(Channel)} is called;
     * {@link ChannelModel#addChannel(Channel)} is never called here.</p>
     *
     * @param channel   a click-to-tune channel (must be in {@link #mClickToTuneChannels})
     * @param newType   the desired decoder
     */
    public void changeDecoder(Channel channel, DecoderType newType)
    {
        if(channel == null || newType == null)
        {
            return;
        }

        mLog.info("Changing decoder for channel '{}' to {}", channel.getName(), newType);

        try
        {
            mChannelProcessingManager.stop(channel);
        }
        catch(ChannelException e)
        {
            mLog.warn("Error stopping channel '{}' before decoder change: {}", channel.getName(), e.getMessage());
        }

        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(newType));

        // Restart the existing channel — do NOT add it to the model again
        restartExistingChannel(channel);
    }

    /**
     * Re-classifies the signal for an existing click-to-tune channel.
     *
     * <p>Stops the channel and runs the classifier at its frequency.  On
     * {@link ClassificationOutcome#IDENTIFIED} the existing channel's decode configuration
     * is swapped to the winning decoder and the channel is restarted (no model mutation).
     * On any other outcome the channel is left in its pre-redetect state (decode config
     * unchanged) and the miss popup is shown.</p>
     *
     * @param channel a click-to-tune channel
     */
    public void redetect(Channel channel)
    {
        if(channel == null)
        {
            return;
        }

        long freqHz = channelFrequency(channel);

        if(freqHz <= 0)
        {
            mLog.warn("Cannot re-detect channel '{}': could not determine frequency", channel.getName());
            return;
        }

        mLog.info("Re-detecting channel '{}' at {} Hz", channel.getName(), freqHz);

        try
        {
            mChannelProcessingManager.stop(channel);
        }
        catch(ChannelException e)
        {
            mLog.warn("Error stopping channel '{}' before re-detect: {}", channel.getName(), e.getMessage());
        }

        // Cancel any prior pending classification and start a new one for this channel
        CompletableFuture<ClassificationResult> prior = mPendingFuture.getAndSet(null);
        if(prior != null)
        {
            prior.cancel(true);
        }

        int bwHz = mUserPreferences.getDiscoveryPreference().getClickDefaultBandwidthHz();
        mUICallbacks.showPending(freqHz, bwHz);

        ClassificationRequest request = ClassificationRequest.forFrequency(freqHz, bwHz,
            "redetect@" + freqHz);

        CompletableFuture<ClassificationResult> future = mSignalClassifier.classify(request);
        mPendingFuture.set(future);

        future.whenComplete((result, ex) -> SwingUtilities.invokeLater(() ->
        {
            if(!mPendingFuture.compareAndSet(future, null))
            {
                return;
            }

            mUICallbacks.clearPending();

            if(ex instanceof CancellationException)
            {
                // User cancelled — restore the channel with its original config
                restartExistingChannel(channel);
                return;
            }

            if(ex != null)
            {
                mLog.error("Unexpected exception during re-detect classification", ex);
                restartExistingChannel(channel);
                return;
            }

            if(result == null)
            {
                mLog.warn("Re-detect classification completed with null result");
                restartExistingChannel(channel);
                return;
            }

            if(result.outcome() == ClassificationOutcome.IDENTIFIED)
            {
                // Swap the existing channel's config and restart it — no model change
                channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(result.bestDecoder()));
                mClickToTuneChannels.add(channel); // defensive, should already be there
                restartExistingChannel(channel);
            }
            else
            {
                // Miss — restore the channel and show the miss popup
                restartExistingChannel(channel);
                mUICallbacks.showMissPopup(
                    result,
                    () -> redetect(channel),
                    type ->
                    {
                        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(type));
                        restartExistingChannel(channel);
                    }
                );
            }
        }));
    }

    /**
     * Returns an unmodifiable view of the channels created by this controller.
     * The decoder-override context-menu logic uses this to know which channels are "ours".
     *
     * @return set of click-to-tune channels
     */
    public Set<Channel> getClickToTuneChannels()
    {
        return Collections.unmodifiableSet(mClickToTuneChannels);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Handles a completed classification result for {@link #classifyAndTune}.
     * Always called on the EDT.
     */
    private void handleResult(ClassificationResult result, Throwable ex)
    {
        mUICallbacks.clearPending();

        if(ex instanceof CancellationException)
        {
            // User-initiated cancel — no popup, no error log
            return;
        }

        if(ex != null)
        {
            mLog.error("Unexpected exception completing classification", ex);
            return;
        }

        if(result == null)
        {
            mLog.warn("Classification completed with null result");
            return;
        }

        if(result.outcome() == ClassificationOutcome.IDENTIFIED)
        {
            handleIdentified(result);
        }
        else
        {
            // UNIDENTIFIED, NO_SIGNAL, ERROR, CANCELLED — show the miss popup.
            // The "keep listening" callback re-runs classification with a longer
            // overallDeadline sourced from DiscoveryPreference.getKeepListeningDuration();
            // it does NOT change the bandwidth.
            Duration keepListeningDeadline =
                mUserPreferences.getDiscoveryPreference().getKeepListeningDuration();
            int bwHz = mUserPreferences.getDiscoveryPreference().getClickDefaultBandwidthHz();

            mUICallbacks.showMissPopup(
                result,
                () -> classifyWithDeadline(result.centerFrequencyHz(), bwHz, keepListeningDeadline),
                type -> tuneAs(result.centerFrequencyHz(), type)
            );
        }
    }

    /**
     * Like {@link #classifyAndTune(long, int)} but uses an explicit {@code overallDeadline}
     * for the classification request.
     *
     * <p>Used by the "Keep listening" miss-popup action so that the user gets a longer
     * probing window than the default 12-second deadline.</p>
     *
     * @param centerFreqHz  centre frequency of the signal, in Hz
     * @param approxBwHz    operator-estimated bandwidth, in Hz
     * @param deadline      overallDeadline to use for the {@link ClassificationRequest}
     */
    private void classifyWithDeadline(long centerFreqHz, int approxBwHz, Duration deadline)
    {
        int bwHz = (approxBwHz > 0) ? approxBwHz
            : mUserPreferences.getDiscoveryPreference().getClickDefaultBandwidthHz();

        // Cancel any in-progress classification before starting a new one
        CompletableFuture<ClassificationResult> prior = mPendingFuture.getAndSet(null);
        if(prior != null)
        {
            prior.cancel(true);
        }

        mUICallbacks.showPending(centerFreqHz, bwHz);

        ClassificationRequest request = ClassificationRequest.forFrequency(
            centerFreqHz, bwHz, null,
            "keep-listening@" + centerFreqHz, deadline);

        CompletableFuture<ClassificationResult> future = mSignalClassifier.classify(request);
        mPendingFuture.set(future);

        future.whenComplete((result, ex) -> SwingUtilities.invokeLater(() ->
        {
            if(!mPendingFuture.compareAndSet(future, null))
            {
                return;
            }
            handleResult(result, ex);
        }));
    }

    /**
     * Builds and starts a brand-new channel from a successful classification.
     * Called on the EDT.
     */
    private void handleIdentified(ClassificationResult result)
    {
        String aliasList = defaultAliasListName();

        Channel channel = mChannelFactory.createChannel(result, aliasList);
        startNewChannel(channel);
    }

    /**
     * Adds a brand-new channel to the model and starts it.
     *
     * <p>This is the <em>new channel</em> path: the channel is added to
     * {@link ChannelModel} AND to {@link #mClickToTuneChannels}, then started.
     * On {@link ChannelException} the channel is removed from both and the failure
     * is reported via {@link UICallbacks#reportStartFailure}.</p>
     *
     * @param channel the new channel to add and start
     */
    private void startNewChannel(Channel channel)
    {
        mChannelModel.addChannel(channel);
        mClickToTuneChannels.add(channel);

        try
        {
            mChannelProcessingManager.start(channel);
        }
        catch(ChannelException ce)
        {
            mLog.warn("Failed to start click-to-tune channel '{}': {}", channel.getName(), ce.getMessage());
            mClickToTuneChannels.remove(channel);
            mChannelModel.removeChannel(channel);
            mUICallbacks.reportStartFailure(channel,
                java.util.Objects.requireNonNullElse(ce.getMessage(), ce.toString()));
        }
    }

    /**
     * Restarts an <em>existing</em> channel that is already present in the model.
     *
     * <p>This is the <em>restart</em> path used by {@link #changeDecoder} and
     * {@link #redetect}: the channel is NOT re-added to the model; only
     * {@link ChannelProcessingManager#start(Channel)} is called.</p>
     *
     * @param channel the existing channel to restart
     */
    private void restartExistingChannel(Channel channel)
    {
        mClickToTuneChannels.add(channel); // defensive

        try
        {
            mChannelProcessingManager.start(channel);
        }
        catch(ChannelException ce)
        {
            mLog.warn("Failed to restart click-to-tune channel '{}': {}", channel.getName(), ce.getMessage());
            mUICallbacks.reportStartFailure(channel,
                java.util.Objects.requireNonNullElse(ce.getMessage(), ce.toString()));
        }
    }

    /**
     * Receives channel events from the model and prunes channels that have been
     * deleted (e.g. by the user via the playlist editor) from {@link #mClickToTuneChannels}.
     *
     * @param event the channel event
     */
    private void onChannelEvent(ChannelEvent event)
    {
        if(event.getEvent() == ChannelEvent.Event.NOTIFICATION_DELETE)
        {
            mClickToTuneChannels.remove(event.getChannel());
        }
    }

    /**
     * Returns the first alias list name from the model, or null if none exist.
     * Used as a default for newly-created channels.
     */
    private String defaultAliasListName()
    {
        List<String> names = mChannelModel.getAliasListNames();
        return (names != null && !names.isEmpty()) ? names.get(0) : null;
    }

    /**
     * Extracts the centre frequency from a channel's source configuration.
     *
     * @param channel the channel
     * @return frequency in Hz, or {@code -1} if not determinable
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
