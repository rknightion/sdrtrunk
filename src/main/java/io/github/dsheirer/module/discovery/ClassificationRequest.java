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
import java.time.Duration;
import java.util.EnumSet;

/**
 * A request to classify (auto-detect the protocol of) a signal at a given frequency.
 *
 * @param centerFrequencyHz        target frequency in Hz
 * @param approximateBandwidthHz   operator's estimated signal width in Hz; 0 = unknown
 * @param candidateDecoders        set of decoders to try; defaults to all {@link DecoderType#PRIMARY_DECODERS}
 * @param overallDeadline          hard cap on the total probing time; defaults to ~12 s
 * @param keepWinningChainRunning  if {@code true}, the winning probe chain is kept live and returned
 *                                 in the result; v1 always returns {@code liveChain == null} regardless
 * @param label                    short human-readable label for logging / thread naming
 */
public record ClassificationRequest(
    long centerFrequencyHz,
    int approximateBandwidthHz,
    EnumSet<DecoderType> candidateDecoders,
    Duration overallDeadline,
    boolean keepWinningChainRunning,
    String label)
{
    /** Default total time budget for a full classification attempt. */
    public static final Duration DEFAULT_DEADLINE = Duration.ofSeconds(12);

    /** Default bandwidth when the operator has not specified one. */
    public static final int DEFAULT_BANDWIDTH_HZ = 0;

    /**
     * Compact constructor — validates required fields and applies defaults.
     */
    public ClassificationRequest
    {
        if(centerFrequencyHz <= 0)
        {
            throw new IllegalArgumentException("centerFrequencyHz must be positive, got: " + centerFrequencyHz);
        }

        if(approximateBandwidthHz < 0)
        {
            throw new IllegalArgumentException("approximateBandwidthHz must not be negative, got: " + approximateBandwidthHz);
        }

        if(candidateDecoders == null || candidateDecoders.isEmpty())
        {
            candidateDecoders = EnumSet.copyOf(DecoderType.PRIMARY_DECODERS);
        }

        if(overallDeadline == null || overallDeadline.isNegative() || overallDeadline.isZero())
        {
            overallDeadline = DEFAULT_DEADLINE;
        }

        if(label == null)
        {
            label = Long.toString(centerFrequencyHz);
        }
    }

    // -------------------------------------------------------------------------
    // Factory / builder methods
    // -------------------------------------------------------------------------

    /**
     * Creates a classification request for the given frequency using all default settings.
     *
     * @param frequencyHz center frequency to probe
     * @return a new {@link ClassificationRequest}
     */
    public static ClassificationRequest forFrequency(long frequencyHz)
    {
        return new ClassificationRequest(
            frequencyHz,
            DEFAULT_BANDWIDTH_HZ,
            EnumSet.copyOf(DecoderType.PRIMARY_DECODERS),
            DEFAULT_DEADLINE,
            false,
            Long.toString(frequencyHz)
        );
    }

    /**
     * Creates a classification request for the given frequency and approximate bandwidth.
     *
     * @param frequencyHz  center frequency to probe
     * @param bandwidthHz  approximate occupied bandwidth of the signal
     * @param label        short label for logging
     * @return a new {@link ClassificationRequest}
     */
    public static ClassificationRequest forFrequency(long frequencyHz, int bandwidthHz, String label)
    {
        return new ClassificationRequest(
            frequencyHz,
            bandwidthHz,
            EnumSet.copyOf(DecoderType.PRIMARY_DECODERS),
            DEFAULT_DEADLINE,
            false,
            label
        );
    }
}
