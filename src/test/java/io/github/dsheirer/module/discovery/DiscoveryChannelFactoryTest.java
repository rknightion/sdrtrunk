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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DiscoveryChannelFactory}.
 */
class DiscoveryChannelFactoryTest
{
    private static final long FREQ = 154_920_000L; // 154.920 MHz

    private DiscoveryChannelFactory mFactory;

    @BeforeEach
    void setUp()
    {
        mFactory = new DiscoveryChannelFactory();
    }

    // -------------------------------------------------------------------------
    // createChannel(ClassificationResult, String) — from auto-detect
    // -------------------------------------------------------------------------

    @Test
    void createFromResult_identifiedConventional_hasCorrectNameAndConfig()
    {
        ClassificationResult result = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.NBFM, LockState.LOCKED, 0.95, null)),
            DecoderType.NBFM,
            DecoderFactory.getDecodeConfiguration(DecoderType.NBFM),
            SignalKind.CONVENTIONAL,
            "NBFM",
            Map.of(),
            -80.0
        );

        Channel channel = mFactory.createChannel(result, "TestAlias");

        assertNotNull(channel);
        assertTrue(channel.getName().startsWith("Discovered "), "Name should start with 'Discovered '");
        assertTrue(channel.getName().contains("154.920"), "Name should contain formatted MHz");
        assertFalse(channel.getName().contains("(control)"), "Non-CONTROL should not have (control) suffix");
        assertEquals("TestAlias", channel.getAliasListName());
        assertEquals(DecoderType.NBFM, channel.getDecodeConfiguration().getDecoderType());
        assertTrue(channel.getSourceConfiguration() instanceof SourceConfigTuner);
        assertEquals(FREQ, ((SourceConfigTuner) channel.getSourceConfiguration()).getFrequency());
        assertTrue(channel.isTemporaryLive(), "Auto-detected channels should start as temporary live channels");
    }

    @Test
    void createFromResult_identifiedControl_appendsControlSuffix()
    {
        ClassificationResult result = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.P25_PHASE1, LockState.LOCKED, 0.99, null)),
            DecoderType.P25_PHASE1,
            DecoderFactory.getDecodeConfiguration(DecoderType.P25_PHASE1),
            SignalKind.CONTROL,
            "P25 Phase 1 control",
            Map.of(),
            -70.0
        );

        Channel channel = mFactory.createChannel(result, null);

        assertTrue(channel.getName().endsWith("(control)"),
            "CONTROL signal should have (control) suffix, was: " + channel.getName());
        assertNull(channel.getAliasListName(), "null alias list should remain null");
    }

    @Test
    void createFromResult_configIsFreshCopy_notSameInstance()
    {
        DecodeConfiguration originalConfig = DecoderFactory.getDecodeConfiguration(DecoderType.DMR);

        ClassificationResult result = ClassificationResult.identified(
            FREQ,
            List.of(new Candidate(DecoderType.DMR, LockState.LOCKED, 0.9, null)),
            DecoderType.DMR,
            originalConfig,
            SignalKind.UNKNOWN,
            "",
            Map.of(),
            -75.0
        );

        Channel channel = mFactory.createChannel(result, null);

        // Factory creates a fresh config via DecoderFactory.getDecodeConfiguration,
        // so it must NOT be the same instance as the one in the result
        assertNotSame(originalConfig, channel.getDecodeConfiguration(),
            "DecodeConfiguration in channel must be a fresh instance, not the one from the result");
    }

    @Test
    void createFromResult_nonIdentifiedResult_throwsIllegalArgument()
    {
        ClassificationResult noSignal = ClassificationResult.noSignal(FREQ, Double.NaN);

        assertThrows(IllegalArgumentException.class,
            () -> mFactory.createChannel(noSignal, null),
            "Non-IDENTIFIED result should throw IllegalArgumentException");
    }

    @Test
    void createFromResult_nullResult_throwsIllegalArgument()
    {
        assertThrows(IllegalArgumentException.class,
            () -> mFactory.createChannel(null, null));
    }

    // -------------------------------------------------------------------------
    // createChannel(long, DecoderType, String) — manual override path
    // -------------------------------------------------------------------------

    @Test
    void createManual_nbfm_hasCorrectConfig()
    {
        Channel channel = mFactory.createChannel(FREQ, DecoderType.NBFM, "MyList");

        assertNotNull(channel);
        assertTrue(channel.getName().startsWith("Discovered "));
        assertEquals(DecoderType.NBFM, channel.getDecodeConfiguration().getDecoderType());
        assertEquals(FREQ, ((SourceConfigTuner) channel.getSourceConfiguration()).getFrequency());
        assertEquals("MyList", channel.getAliasListName());
        assertTrue(channel.isTemporaryLive(), "Manual decode-here channels should start as temporary live channels");
    }

    @Test
    void createManual_dmr_hasCorrectDecoder()
    {
        Channel channel = mFactory.createChannel(FREQ, DecoderType.DMR, null);

        assertEquals(DecoderType.DMR, channel.getDecodeConfiguration().getDecoderType());
        assertNull(channel.getAliasListName());
    }

    @Test
    void createManual_negativeFrequency_throwsIllegalArgument()
    {
        assertThrows(IllegalArgumentException.class,
            () -> mFactory.createChannel(-1L, DecoderType.NBFM, null));
    }

    @Test
    void createManual_nullDecoder_throwsIllegalArgument()
    {
        assertThrows(IllegalArgumentException.class,
            () -> mFactory.createChannel(FREQ, null, null));
    }

    // -------------------------------------------------------------------------
    // buildChannelName (package-private static helper)
    // -------------------------------------------------------------------------

    @Test
    void buildChannelName_conventional_noControlSuffix()
    {
        String name = DiscoveryChannelFactory.buildChannelName(FREQ, SignalKind.CONVENTIONAL);
        assertEquals("Discovered 154.920", name);
    }

    @Test
    void buildChannelName_control_hasControlSuffix()
    {
        String name = DiscoveryChannelFactory.buildChannelName(FREQ, SignalKind.CONTROL);
        assertEquals("Discovered 154.920 (control)", name);
    }

    @Test
    void buildChannelName_data_hasDataSuffix()
    {
        String name = DiscoveryChannelFactory.buildChannelName(FREQ, SignalKind.DATA);
        assertEquals("Discovered 154.920 (data)", name);
    }

    @Test
    void buildChannelName_unknown_noControlSuffix()
    {
        String name = DiscoveryChannelFactory.buildChannelName(FREQ, SignalKind.UNKNOWN);
        assertEquals("Discovered 154.920", name);
    }

    @Test
    void buildChannelName_subMhz_formattedCorrectly()
    {
        // 850.100 MHz
        String name = DiscoveryChannelFactory.buildChannelName(850_100_000L, SignalKind.UNKNOWN);
        assertEquals("Discovered 850.100", name);
    }
}
