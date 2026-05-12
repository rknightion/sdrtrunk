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
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.controller.channel.map.ChannelMapModel;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.PrimaryDecoder;
import io.github.dsheirer.module.log.EventLogger;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.binary.BinaryRecorder;
import io.github.dsheirer.record.wave.ComplexSamplesWaveRecorder;
import io.github.dsheirer.channel.state.AbstractChannelState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProbeChainFactory.
 */
class ProbeChainFactoryTest
{
    private ProbeChainFactory mFactory;

    @BeforeEach
    void setUp()
    {
        mFactory = new ProbeChainFactory(new AliasModel(), new ChannelMapModel(), new UserPreferences());
    }

    // -------------------------------------------------------------------------
    // NBFM probe chain
    // -------------------------------------------------------------------------

    @Test
    void nbfm_probeChainNotNull()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        assertNotNull(chain);
        chain.chain().dispose();
    }

    @Test
    void nbfm_containsPrimaryDecoder()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        assertTrue(chain.chain().getModules().stream().anyMatch(m -> m instanceof PrimaryDecoder),
            "NBFM probe chain should contain a primary decoder module");
        chain.chain().dispose();
    }

    @Test
    void nbfm_containsChannelState()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        assertTrue(chain.chain().getModules().stream().anyMatch(m -> m instanceof AbstractChannelState),
            "NBFM probe chain should contain a channel state module");
        chain.chain().dispose();
    }

    @Test
    void nbfm_noAudioModules()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        assertFalse(chain.chain().getModules().stream().anyMatch(m -> m instanceof AbstractAudioModule),
            "NBFM probe chain must not contain audio modules");
        chain.chain().dispose();
    }

    @Test
    void nbfm_noEventLoggers()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        assertFalse(chain.chain().getModules().stream().anyMatch(m -> m instanceof EventLogger),
            "NBFM probe chain must not contain event loggers");
        chain.chain().dispose();
    }

    @Test
    void nbfm_noRecorders()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        boolean hasRecorder = chain.chain().getModules().stream()
            .anyMatch(m -> m instanceof ComplexSamplesWaveRecorder || m instanceof BinaryRecorder);
        assertFalse(hasRecorder, "NBFM probe chain must not contain recorder modules");
        chain.chain().dispose();
    }

    @Test
    void nbfm_lockWatcherWired()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        assertNotNull(chain.lockWatcher(), "LockWatcher must not be null");
        chain.chain().dispose();
    }

    @Test
    void nbfm_chainNotStarted()
    {
        ProbeChain chain = mFactory.build(DecoderType.NBFM);
        assertFalse(chain.chain().isProcessing(), "Probe chain must not be started by the factory");
        chain.chain().dispose();
    }

    // -------------------------------------------------------------------------
    // DMR probe chain
    // -------------------------------------------------------------------------

    @Test
    void dmr_probeChainNotNull()
    {
        ProbeChain chain = mFactory.build(DecoderType.DMR);
        assertNotNull(chain);
        chain.chain().dispose();
    }

    @Test
    void dmr_containsPrimaryDecoder()
    {
        ProbeChain chain = mFactory.build(DecoderType.DMR);
        assertTrue(chain.chain().getModules().stream().anyMatch(m -> m instanceof PrimaryDecoder),
            "DMR probe chain should contain a primary decoder module");
        chain.chain().dispose();
    }

    @Test
    void dmr_noAudioModules()
    {
        ProbeChain chain = mFactory.build(DecoderType.DMR);
        assertFalse(chain.chain().getModules().stream().anyMatch(m -> m instanceof AbstractAudioModule),
            "DMR probe chain must not contain audio modules");
        chain.chain().dispose();
    }

    // -------------------------------------------------------------------------
    // P25 Phase 1 probe chain
    // -------------------------------------------------------------------------

    @Test
    void p25Phase1_probeChainNotNull()
    {
        ProbeChain chain = mFactory.build(DecoderType.P25_PHASE1);
        assertNotNull(chain);
        chain.chain().dispose();
    }

    @Test
    void p25Phase1_containsPrimaryDecoder()
    {
        ProbeChain chain = mFactory.build(DecoderType.P25_PHASE1);
        assertTrue(chain.chain().getModules().stream().anyMatch(m -> m instanceof PrimaryDecoder),
            "P25_PHASE1 probe chain should contain a primary decoder module");
        chain.chain().dispose();
    }

    @Test
    void p25Phase1_noAudioModules()
    {
        ProbeChain chain = mFactory.build(DecoderType.P25_PHASE1);
        assertFalse(chain.chain().getModules().stream().anyMatch(m -> m instanceof AbstractAudioModule),
            "P25_PHASE1 probe chain must not contain audio modules");
        chain.chain().dispose();
    }
}
