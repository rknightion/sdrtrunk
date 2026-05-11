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
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LockWatcher state machine.
 */
class LockWatcherTest
{
    private LockWatcher mWatcher;

    @BeforeEach
    void setUp()
    {
        mWatcher = new LockWatcher();
    }

    private void sendState(State state)
    {
        mWatcher.getDecoderStateListener().receive(
            new DecoderStateEvent(this, DecoderStateEvent.Event.NOTIFICATION_CHANNEL_STATE, state));
    }

    // -------------------------------------------------------------------------
    // (a) Only IDLE events → NONE
    // -------------------------------------------------------------------------

    @Test
    void onlyIdleEvents_lockStateIsNone()
    {
        sendState(State.IDLE);
        sendState(State.IDLE);
        assertEquals(LockState.NONE, mWatcher.getLockState());
    }

    @Test
    void noEvents_lockStateIsNone()
    {
        assertEquals(LockState.NONE, mWatcher.getLockState());
    }

    // -------------------------------------------------------------------------
    // (b) One ACTIVE then back to IDLE quickly → PARTIAL
    // -------------------------------------------------------------------------

    @Test
    void singleActiveEventThenIdle_isPartial()
    {
        sendState(State.ACTIVE);
        sendState(State.IDLE);
        assertEquals(LockState.PARTIAL, mWatcher.getLockState());
    }

    @Test
    void singleCallEventThenIdle_isPartial()
    {
        sendState(State.CALL);
        sendState(State.IDLE);
        assertEquals(LockState.PARTIAL, mWatcher.getLockState());
    }

    // -------------------------------------------------------------------------
    // (c) Several consecutive CONTROL events past the debounce → LOCKED + CONTROL
    // -------------------------------------------------------------------------

    @Test
    void sustainedControlEvents_isLocked()
    {
        for(int i = 0; i < LockWatcher.LOCK_DEBOUNCE_COUNT; i++)
        {
            sendState(State.CONTROL);
        }
        assertEquals(LockState.LOCKED, mWatcher.getLockState());
    }

    @Test
    void sustainedControlEvents_kindIsControl()
    {
        for(int i = 0; i < LockWatcher.LOCK_DEBOUNCE_COUNT; i++)
        {
            sendState(State.CONTROL);
        }
        assertEquals(SignalKind.CONTROL, mWatcher.getKind());
    }

    @Test
    void sustainedCallEvents_isLocked()
    {
        for(int i = 0; i < LockWatcher.LOCK_DEBOUNCE_COUNT; i++)
        {
            sendState(State.CALL);
        }
        assertEquals(LockState.LOCKED, mWatcher.getLockState());
    }

    @Test
    void sustainedActiveEvents_isLocked()
    {
        for(int i = 0; i < LockWatcher.LOCK_DEBOUNCE_COUNT; i++)
        {
            sendState(State.ACTIVE);
        }
        assertEquals(LockState.LOCKED, mWatcher.getLockState());
    }

    // -------------------------------------------------------------------------
    // (d) Fast lock has higher quality than slow / partial lock
    // -------------------------------------------------------------------------

    @Test
    void fastLockQualityHigherThanPartialQuality()
    {
        // Fast lock watcher — lock immediately
        LockWatcher fastWatcher = new LockWatcher();
        for(int i = 0; i < LockWatcher.LOCK_DEBOUNCE_COUNT; i++)
        {
            fastWatcher.getDecoderStateListener().receive(
                new DecoderStateEvent(this, DecoderStateEvent.Event.NOTIFICATION_CHANNEL_STATE, State.CONTROL));
        }

        // Partial watcher
        LockWatcher partialWatcher = new LockWatcher();
        partialWatcher.getDecoderStateListener().receive(
            new DecoderStateEvent(this, DecoderStateEvent.Event.NOTIFICATION_CHANNEL_STATE, State.ACTIVE));
        partialWatcher.getDecoderStateListener().receive(
            new DecoderStateEvent(this, DecoderStateEvent.Event.NOTIFICATION_CHANNEL_STATE, State.IDLE));

        assertTrue(fastWatcher.getLockQuality() > partialWatcher.getLockQuality(),
            "Fast lock should have higher quality than partial");
    }

    // -------------------------------------------------------------------------
    // (e) markError() → ERROR
    // -------------------------------------------------------------------------

    @Test
    void markError_setsErrorState()
    {
        mWatcher.markError("test exception");
        assertEquals(LockState.ERROR, mWatcher.getLockState());
    }

    // -------------------------------------------------------------------------
    // Identifier / metadata collection
    // -------------------------------------------------------------------------

    @Test
    void identifierUpdate_populatesMetadata()
    {
        // Simulate an identifier update notification with a System.NAC-like identifier
        Identifier<Integer> nacId = new Identifier<>(0x293,
            IdentifierClass.NETWORK,
            Form.NETWORK_ACCESS_CODE,
            Role.ANY) {
            @Override public Protocol getProtocol() { return Protocol.APCO25; }
        };

        mWatcher.getIdentifierUpdateListener().receive(
            new IdentifierUpdateNotification(nacId, IdentifierUpdateNotification.Operation.ADD, 0));

        Map<String, String> meta = mWatcher.getMetadata();
        assertFalse(meta.isEmpty(), "Metadata should be populated after identifier update");
    }

    // -------------------------------------------------------------------------
    // reset()
    // -------------------------------------------------------------------------

    @Test
    void reset_clearsState()
    {
        for(int i = 0; i < LockWatcher.LOCK_DEBOUNCE_COUNT; i++)
        {
            sendState(State.CONTROL);
        }
        assertEquals(LockState.LOCKED, mWatcher.getLockState());

        mWatcher.reset();
        assertEquals(LockState.NONE, mWatcher.getLockState());
        assertEquals(SignalKind.UNKNOWN, mWatcher.getKind());
    }
}
