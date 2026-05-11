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

import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The result of a {@link SignalClassifier} classification attempt.
 *
 * <p>Use the static factory methods ({@link #identified}, {@link #unidentified},
 * {@link #noSignal}, {@link #error}, {@link #cancelled}) to construct instances.</p>
 *
 * @param centerFrequencyHz  the frequency that was probed
 * @param outcome            high-level outcome of the classification
 * @param candidates         every decoder that was tried, with its lock state — best first
 * @param bestDecoder        the winning decoder type, or {@code null} if not {@code IDENTIFIED}
 * @param bestDecodeConfig   ready-to-use config for the winning decoder, or {@code null}
 * @param kind               whether the signal is a control / conventional / traffic channel
 * @param summary            short human-readable description (e.g. "P25 Phase 1 · control · NAC 0x293")
 * @param metadata           key/value pairs harvested during probing (NAC, color code, system/site, …)
 * @param signalPowerDb      measured signal power during probing (may be NaN if not measured)
 * @param liveChain          kept-alive winning chain (v1: always {@code null})
 */
public record ClassificationResult(
    long centerFrequencyHz,
    ClassificationOutcome outcome,
    List<Candidate> candidates,
    DecoderType bestDecoder,
    DecodeConfiguration bestDecodeConfig,
    SignalKind kind,
    String summary,
    Map<String, String> metadata,
    double signalPowerDb,
    ProcessingChain liveChain)
{
    // -------------------------------------------------------------------------
    // Static factory methods
    // -------------------------------------------------------------------------

    /**
     * Creates an IDENTIFIED result for a successfully classified signal.
     *
     * @param frequencyHz  probed center frequency
     * @param candidates   all candidates tried (best first)
     * @param bestDecoder  the winning decoder type
     * @param decodeConfig ready-to-use default config for the winning decoder
     * @param kind         signal kind inferred from decoded messages
     * @param summary      brief human-readable summary string
     * @param metadata     metadata map harvested during probing
     * @param powerDb      measured signal power
     * @return an IDENTIFIED result
     */
    public static ClassificationResult identified(
        long frequencyHz,
        List<Candidate> candidates,
        DecoderType bestDecoder,
        DecodeConfiguration decodeConfig,
        SignalKind kind,
        String summary,
        Map<String, String> metadata,
        double powerDb)
    {
        return new ClassificationResult(
            frequencyHz,
            ClassificationOutcome.IDENTIFIED,
            candidates != null ? Collections.unmodifiableList(candidates) : List.of(),
            bestDecoder,
            decodeConfig,
            kind != null ? kind : SignalKind.UNKNOWN,
            summary != null ? summary : "",
            metadata != null ? Collections.unmodifiableMap(metadata) : Map.of(),
            powerDb,
            null // v1: always null
        );
    }

    /**
     * Creates an UNIDENTIFIED result where energy was present but no decoder locked.
     *
     * @param frequencyHz probed center frequency
     * @param candidates  all candidates tried (with their partial/none states)
     * @param powerDb     measured signal power
     * @return an UNIDENTIFIED result
     */
    public static ClassificationResult unidentified(
        long frequencyHz,
        List<Candidate> candidates,
        double powerDb)
    {
        return new ClassificationResult(
            frequencyHz,
            ClassificationOutcome.UNIDENTIFIED,
            candidates != null ? Collections.unmodifiableList(candidates) : List.of(),
            null,
            null,
            SignalKind.UNKNOWN,
            "no match",
            Map.of(),
            powerDb,
            null
        );
    }

    /**
     * Creates a NO_SIGNAL result where no meaningful energy was detected.
     *
     * @param frequencyHz probed center frequency
     * @param powerDb     measured power (should be below the energy gate threshold)
     * @return a NO_SIGNAL result
     */
    public static ClassificationResult noSignal(long frequencyHz, double powerDb)
    {
        return new ClassificationResult(
            frequencyHz,
            ClassificationOutcome.NO_SIGNAL,
            List.of(),
            null,
            null,
            SignalKind.UNKNOWN,
            "no signal",
            Map.of(),
            powerDb,
            null
        );
    }

    /**
     * Creates an ERROR result for a classification that could not be completed.
     *
     * @param frequencyHz probed center frequency
     * @param reason      description of why the classification failed
     * @return an ERROR result
     */
    public static ClassificationResult error(long frequencyHz, String reason)
    {
        return new ClassificationResult(
            frequencyHz,
            ClassificationOutcome.ERROR,
            List.of(),
            null,
            null,
            SignalKind.UNKNOWN,
            reason != null ? reason : "error",
            Map.of(),
            Double.NaN,
            null
        );
    }

    /**
     * Creates a CANCELLED result.
     *
     * @param frequencyHz probed center frequency
     * @return a CANCELLED result
     */
    public static ClassificationResult cancelled(long frequencyHz)
    {
        return new ClassificationResult(
            frequencyHz,
            ClassificationOutcome.CANCELLED,
            List.of(),
            null,
            null,
            SignalKind.UNKNOWN,
            "cancelled",
            Map.of(),
            Double.NaN,
            null
        );
    }

    // -------------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------------

    /** Returns {@code true} if a decoder successfully locked. */
    public boolean isIdentified()
    {
        return outcome == ClassificationOutcome.IDENTIFIED;
    }

    /** Returns {@code true} if the signal could be probed but no decoder locked. */
    public boolean isUnidentified()
    {
        return outcome == ClassificationOutcome.UNIDENTIFIED;
    }

    /** Returns {@code true} if no energy was detected at the probed frequency. */
    public boolean isNoSignal()
    {
        return outcome == ClassificationOutcome.NO_SIGNAL;
    }
}
