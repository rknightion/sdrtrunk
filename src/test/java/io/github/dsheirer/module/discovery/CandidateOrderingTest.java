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

import io.github.dsheirer.module.decode.DecoderType;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CandidateOrdering bandwidth-to-decoder priority.
 */
class CandidateOrderingTest
{
    private static final EnumSet<DecoderType> ALL_PRIMARY = EnumSet.copyOf(DecoderType.PRIMARY_DECODERS);

    // -------------------------------------------------------------------------
    // Narrow band (≤8 kHz) — P25_PHASE2 first
    // -------------------------------------------------------------------------

    @Test
    void narrowBand_p25Phase2First()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 6_250);
        assertEquals(DecoderType.P25_PHASE2, ordered.get(0));
    }

    @Test
    void narrowBand_allNinePresentInOutputForFullInput()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 6_250);
        assertEquals(9, ordered.size());
        assertTrue(ordered.containsAll(ALL_PRIMARY));
    }

    // -------------------------------------------------------------------------
    // Standard band (~12.5 kHz) — P25_PHASE1 early, AM last
    // -------------------------------------------------------------------------

    @Test
    void standardBand_p25Phase1BeforeAm()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 12_500);
        assertTrue(ordered.indexOf(DecoderType.P25_PHASE1) < ordered.indexOf(DecoderType.AM));
    }

    @Test
    void standardBand_allNinePresent()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 12_500);
        assertEquals(9, ordered.size());
        assertTrue(ordered.containsAll(ALL_PRIMARY));
    }

    // -------------------------------------------------------------------------
    // Wide band (~25 kHz) — NBFM before P25_PHASE2
    // -------------------------------------------------------------------------

    @Test
    void wideBand_nbfmBeforeP25Phase2()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 25_000);
        assertTrue(ordered.indexOf(DecoderType.NBFM) < ordered.indexOf(DecoderType.P25_PHASE2));
    }

    @Test
    void wideBand_allNinePresent()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 25_000);
        assertEquals(9, ordered.size());
    }

    // -------------------------------------------------------------------------
    // Unknown / very wide (0) — NBFM first
    // -------------------------------------------------------------------------

    @Test
    void unknownBandwidth_nbfmFirst()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 0);
        assertEquals(DecoderType.NBFM, ordered.get(0));
    }

    @Test
    void unknownBandwidth_allNinePresent()
    {
        List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, 0);
        assertEquals(9, ordered.size());
    }

    // -------------------------------------------------------------------------
    // Subset input — output is exactly that subset, reordered
    // -------------------------------------------------------------------------

    @Test
    void subsetInput_outputContainsOnlyInputDecoders()
    {
        EnumSet<DecoderType> subset = EnumSet.of(DecoderType.P25_PHASE1, DecoderType.NBFM, DecoderType.AM);
        List<DecoderType> ordered = CandidateOrdering.order(subset, 12_500);
        assertEquals(3, ordered.size());
        assertTrue(ordered.containsAll(subset));
    }

    @Test
    void subsetInput_standardBand_p25Phase1BeforeAm()
    {
        EnumSet<DecoderType> subset = EnumSet.of(DecoderType.P25_PHASE1, DecoderType.NBFM, DecoderType.AM);
        List<DecoderType> ordered = CandidateOrdering.order(subset, 12_500);
        assertTrue(ordered.indexOf(DecoderType.P25_PHASE1) < ordered.indexOf(DecoderType.AM));
    }

    // -------------------------------------------------------------------------
    // AM always ordered last or near-last for standard/narrow/unknown bands
    // -------------------------------------------------------------------------

    @Test
    void am_notFirstForAnyCommonBandwidth()
    {
        for(int bw : new int[]{0, 6_250, 12_500, 25_000})
        {
            List<DecoderType> ordered = CandidateOrdering.order(ALL_PRIMARY, bw);
            assertNotEquals(DecoderType.AM, ordered.get(0),
                "AM should not be first for bandwidth=" + bw);
        }
    }
}
