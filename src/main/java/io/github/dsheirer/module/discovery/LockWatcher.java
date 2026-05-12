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

import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventListener;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.sample.Listener;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a probe chain's decoder-state and identifier-update streams to determine
 * whether the decoder has locked onto the probed signal.
 *
 * <p>State machine:</p>
 * <ul>
 *   <li>NONE — no non-IDLE state has ever been seen</li>
 *   <li>PARTIAL — at least one non-IDLE state was observed, but the lock has not
 *       been sustained past the debounce threshold</li>
 *   <li>LOCKED — a non-IDLE, non-FADE state was observed on {@code LOCK_DEBOUNCE_COUNT}
 *       or more consecutive events <em>and</em> the non-IDLE streak has lasted at least
 *       {@link #LOCK_DEBOUNCE_MS} milliseconds</li>
 *   <li>ERROR — {@link #markError(String)} was called (decoder threw an exception)</li>
 * </ul>
 *
 * <p>Thread-safety: all public methods and callbacks are {@code synchronized} on {@code this}.
 * External callers may call {@link #getLockState()} from any thread at any time.</p>
 */
public class LockWatcher implements IDecoderStateEventListener, IdentifierUpdateListener
{
    private static final Logger mLog = LoggerFactory.getLogger(LockWatcher.class);

    /**
     * Number of consecutive non-IDLE events required to declare a lock.
     */
    public static final int LOCK_DEBOUNCE_COUNT = 3;

    /**
     * Minimum duration (ms) that a non-IDLE streak must be sustained before declaring LOCKED.
     * Works together with {@link #LOCK_DEBOUNCE_COUNT}: both conditions must be satisfied.
     */
    public static final long LOCK_DEBOUNCE_MS = 250;

    /**
     * Identifier forms that carry meaningful metadata about the signal.
     * Only these forms are stored in the metadata map; noisy forms like STATE,
     * CHANNEL, CHANNEL_FREQUENCY, SCRAMBLE_PARAMETERS, and DECODER_TYPE are excluded.
     */
    private static final Set<Form> METADATA_FORMS = Collections.unmodifiableSet(EnumSet.of(
        Form.NETWORK_ACCESS_CODE,       // P25 NAC
        Form.NETWORK,                   // DMR color code / network ID
        Form.SYSTEM,                    // system ID
        Form.SITE,                      // site ID
        Form.TALKGROUP,                 // talkgroup glimpse
        Form.WACN,                      // P25 WACN
        Form.RF_SUBSYSTEM,              // RFSS
        Form.LOCATION_REGISTRATION_AREA // LRA
    ));

    private final Listener<DecoderStateEvent> mDecoderStateListener = this::onDecoderStateEvent;
    private final Listener<IdentifierUpdateNotification> mIdentifierUpdateListener = this::onIdentifierUpdate;

    private LockState mLockState = LockState.NONE;
    private SignalKind mKind = SignalKind.UNKNOWN;

    /** Insertion-ordered so metadata summary output is deterministic. */
    private final Map<String, String> mMetadata = new LinkedHashMap<>();

    /** Running count of consecutive non-IDLE active events. */
    private int mConsecutiveActiveCount = 0;

    /** Total non-IDLE events ever observed (used for quality calculation). */
    private int mTotalActiveEvents = 0;

    /** Total events ever observed (used for quality calculation). */
    private int mTotalEvents = 0;

    /** Wall-clock time (ms) when the current non-IDLE streak began; 0 if not in a streak. */
    private long mStreakStartMs = 0;

    /** Optional error message when state == ERROR. */
    private String mErrorNote;

    // -------------------------------------------------------------------------
    // IDecoderStateEventListener
    // -------------------------------------------------------------------------

    @Override
    public Listener<DecoderStateEvent> getDecoderStateListener()
    {
        return mDecoderStateListener;
    }

    private synchronized void onDecoderStateEvent(DecoderStateEvent event)
    {
        if(mLockState == LockState.ERROR)
        {
            return; // Error is terminal
        }

        State state = event.getState();
        mTotalEvents++;

        boolean isActive = isActiveState(state);

        if(isActive)
        {
            mTotalActiveEvents++;
            mConsecutiveActiveCount++;

            // Track streak start time for the time-gate component
            if(mConsecutiveActiveCount == 1)
            {
                mStreakStartMs = System.currentTimeMillis();
            }

            // Update kind
            if(state == State.CONTROL)
            {
                mKind = SignalKind.CONTROL;
            }
            else if(mKind != SignalKind.CONTROL)
            {
                // Only update kind if we haven't confirmed CONTROL
                if(state == State.DATA)
                {
                    mKind = SignalKind.DATA;
                }
                else if(mKind != SignalKind.DATA && (state == State.CALL || state == State.ACTIVE))
                {
                    mKind = SignalKind.CONVENTIONAL;
                }
                else if(mKind != SignalKind.DATA && state == State.ENCRYPTED)
                {
                    mKind = SignalKind.CONVENTIONAL; // encrypted conventional
                }
            }

            // Transition state machine: require both count debounce AND time debounce
            if(mConsecutiveActiveCount >= LOCK_DEBOUNCE_COUNT
                && (System.currentTimeMillis() - mStreakStartMs) >= LOCK_DEBOUNCE_MS)
            {
                mLockState = LockState.LOCKED;
            }
            else if(mLockState == LockState.NONE)
            {
                mLockState = LockState.PARTIAL;
            }
        }
        else
        {
            // IDLE / FADE / RESET / TEARDOWN reset the consecutive count
            mConsecutiveActiveCount = 0;
            mStreakStartMs = 0;
            // Do NOT downgrade from LOCKED or PARTIAL — once seen, keep the best
        }
    }

    // -------------------------------------------------------------------------
    // IdentifierUpdateListener — harvest metadata (NAC, color code, system/site)
    // -------------------------------------------------------------------------

    @Override
    public Listener<IdentifierUpdateNotification> getIdentifierUpdateListener()
    {
        return mIdentifierUpdateListener;
    }

    private synchronized void onIdentifierUpdate(IdentifierUpdateNotification notification)
    {
        if(!notification.isAdd() && !notification.isSilentAdd())
        {
            return;
        }

        var identifier = notification.getIdentifier();

        if(identifier == null || identifier.getValue() == null)
        {
            return;
        }

        // Only store forms that carry meaningful protocol metadata
        Form form = identifier.getForm();

        if(!METADATA_FORMS.contains(form))
        {
            return;
        }

        String key = form.name();
        String value = identifier.getValue().toString();
        mMetadata.put(key, value);
    }

    // -------------------------------------------------------------------------
    // Public state accessors
    // -------------------------------------------------------------------------

    /**
     * Current lock state.
     */
    public synchronized LockState getLockState()
    {
        return mLockState;
    }

    /**
     * Returns a quality metric in the range [0.0, 1.0].
     *
     * <ul>
     *   <li>LOCKED with many active events → near 1.0</li>
     *   <li>PARTIAL → low (around 0.2–0.4)</li>
     *   <li>NONE / ERROR → 0.0</li>
     * </ul>
     */
    public synchronized double getLockQuality()
    {
        return switch(mLockState)
        {
            case LOCKED ->
            {
                // Quality based on how quickly and cleanly we locked.
                // More total events before first lock = noisier decode.
                double ratio = mTotalEvents > 0 ? (double) mTotalActiveEvents / mTotalEvents : 0.0;
                yield 0.5 + (ratio * 0.5); // 0.5 .. 1.0
            }
            case PARTIAL -> 0.2;
            default -> 0.0;
        };
    }

    /**
     * Detected signal kind.
     */
    public synchronized SignalKind getKind()
    {
        return mKind;
    }

    /**
     * Unmodifiable copy of the collected metadata map (insertion-ordered).
     */
    public synchronized Map<String, String> getMetadata()
    {
        return Collections.unmodifiableMap(new LinkedHashMap<>(mMetadata));
    }

    /**
     * Builds a brief human-readable summary string from the collected metadata.
     * The format is {@code kind [· key:value …]} — the caller in
     * {@code SignalClassifier} prepends the decoder name.
     */
    public synchronized String getSummary()
    {
        if(mMetadata.isEmpty())
        {
            return mKind != SignalKind.UNKNOWN ? mKind.name().toLowerCase() : "";
        }

        StringBuilder sb = new StringBuilder();

        if(mKind != SignalKind.UNKNOWN)
        {
            sb.append(mKind.name().toLowerCase());
        }

        for(Map.Entry<String, String> entry : mMetadata.entrySet())
        {
            if(!sb.isEmpty())
            {
                sb.append(' ');
            }
            sb.append(entry.getKey().toLowerCase()).append(':').append(entry.getValue());
        }

        return sb.toString();
    }

    /**
     * Marks this watcher as ERROR, overriding any previous lock state.
     *
     * @param note description of the error (may be null)
     */
    public synchronized void markError(String note)
    {
        mLockState = LockState.ERROR;
        mErrorNote = note;
    }

    /**
     * Resets all state to the initial NONE state.  Useful if the watcher is reused.
     */
    public synchronized void reset()
    {
        mLockState = LockState.NONE;
        mKind = SignalKind.UNKNOWN;
        mMetadata.clear();
        mConsecutiveActiveCount = 0;
        mTotalActiveEvents = 0;
        mTotalEvents = 0;
        mStreakStartMs = 0;
        mErrorNote = null;
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Returns true for states that indicate real decode activity (as opposed to
     * IDLE, FADE, RESET, or TEARDOWN which are housekeeping states).
     */
    private static boolean isActiveState(State state)
    {
        return state == State.ACTIVE ||
               state == State.CALL ||
               state == State.CONTROL ||
               state == State.DATA ||
               state == State.ENCRYPTED;
    }
}
