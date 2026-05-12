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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, stateless utility for ordering candidate decoders by bandwidth-derived priority.
 *
 * <p>The ordering heuristic front-loads the decoder(s) most likely to match the
 * observed signal bandwidth so that a confident lock can short-circuit the rest of
 * the probe loop.  All decoders in the input set are returned — ordering is advisory,
 * not a filter.</p>
 *
 * <p>Bandwidth buckets (approximate):</p>
 * <ul>
 *   <li>≤ 8 kHz — P25_PHASE2 (6.25 kHz), then 12.5 kHz group, then wideband</li>
 *   <li>8–18 kHz — P25_PHASE1, DMR, NBFM, then P25_PHASE2, LTR, LTR_NET, MPT1327, PASSPORT, AM</li>
 *   <li>18–35 kHz — NBFM (wide), AM, then 12.5 kHz group</li>
 *   <li>&gt; 35 kHz or unknown (0) — NBFM, AM, then everything else</li>
 * </ul>
 */
public class CandidateOrdering
{
    /**
     * Priority lists for each bandwidth bucket.  Lower index = tried first.
     *
     * <p>Any decoder in the input set but absent from the bucket list is appended
     * at the end (stable relative order).</p>
     */
    private static final List<DecoderType> NARROW_BAND_PRIORITY = List.of(
        DecoderType.P25_PHASE2,
        DecoderType.P25_PHASE1,
        DecoderType.DMR,
        DecoderType.NBFM,
        DecoderType.LTR,
        DecoderType.LTR_NET,
        DecoderType.MPT1327,
        DecoderType.PASSPORT,
        DecoderType.AM
    );

    private static final List<DecoderType> STANDARD_BAND_PRIORITY = List.of(
        DecoderType.P25_PHASE1,
        DecoderType.DMR,
        DecoderType.NBFM,
        DecoderType.P25_PHASE2,
        DecoderType.LTR,
        DecoderType.LTR_NET,
        DecoderType.MPT1327,
        DecoderType.PASSPORT,
        DecoderType.AM
    );

    private static final List<DecoderType> WIDE_BAND_PRIORITY = List.of(
        DecoderType.NBFM,
        DecoderType.AM,
        DecoderType.P25_PHASE1,
        DecoderType.DMR,
        DecoderType.P25_PHASE2,
        DecoderType.LTR,
        DecoderType.LTR_NET,
        DecoderType.MPT1327,
        DecoderType.PASSPORT
    );

    private static final List<DecoderType> UNKNOWN_BAND_PRIORITY = List.of(
        DecoderType.NBFM,
        DecoderType.AM,
        DecoderType.P25_PHASE1,
        DecoderType.DMR,
        DecoderType.P25_PHASE2,
        DecoderType.LTR,
        DecoderType.LTR_NET,
        DecoderType.MPT1327,
        DecoderType.PASSPORT
    );

    /**
     * Returns a new ordered list of the supplied candidate decoders, front-loading
     * the decoder(s) most likely to match {@code approximateBandwidthHz}.
     *
     * @param candidates          the set of decoders to order (not modified)
     * @param approximateBandwidthHz signal bandwidth estimate in Hz; 0 means unknown
     * @return ordered list containing every element of {@code candidates}, exactly once
     */
    public static List<DecoderType> order(EnumSet<DecoderType> candidates, int approximateBandwidthHz)
    {
        List<DecoderType> priority = selectPriorityList(approximateBandwidthHz);
        return applyPriority(candidates, priority);
    }

    /**
     * Selects the appropriate priority list for the given bandwidth estimate.
     */
    private static List<DecoderType> selectPriorityList(int bandwidthHz)
    {
        if(bandwidthHz <= 0)
        {
            return UNKNOWN_BAND_PRIORITY;
        }
        else if(bandwidthHz <= 8_000)
        {
            return NARROW_BAND_PRIORITY;
        }
        else if(bandwidthHz <= 18_000)
        {
            return STANDARD_BAND_PRIORITY;
        }
        else if(bandwidthHz <= 35_000)
        {
            return WIDE_BAND_PRIORITY;
        }
        else
        {
            return UNKNOWN_BAND_PRIORITY;
        }
    }

    /**
     * Stable-sorts {@code candidates} according to the position each decoder occupies
     * in {@code priority}.  Decoders not appearing in {@code priority} are appended
     * at the end in their natural enum order.
     */
    private static List<DecoderType> applyPriority(EnumSet<DecoderType> candidates, List<DecoderType> priority)
    {
        // Build a rank map: lower rank = higher priority
        Map<DecoderType, Integer> rank = new HashMap<>();
        for(int i = 0; i < priority.size(); i++)
        {
            rank.put(priority.get(i), i);
        }

        // Assign a large rank to decoders not in the priority list
        int defaultRank = priority.size();

        List<DecoderType> result = new ArrayList<>(candidates);
        result.sort((a, b) -> {
            int ra = rank.getOrDefault(a, defaultRank);
            int rb = rank.getOrDefault(b, defaultRank);
            if(ra != rb)
            {
                return Integer.compare(ra, rb);
            }
            // Stable tie-break: use enum ordinal so the output is deterministic
            return Integer.compare(a.ordinal(), b.ordinal());
        });
        return result;
    }

    private CandidateOrdering() {}  // utility class
}
