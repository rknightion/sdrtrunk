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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ClassificationResult static factories and ClassificationRequest record.
 */
class ClassificationResultTest
{
    private static final long FREQ = 154_025_000L;

    // -------------------------------------------------------------------------
    // ClassificationResult.identified(...)
    // -------------------------------------------------------------------------

    @Test
    void identified_hasCorrectOutcome()
    {
        ClassificationResult r = ClassificationResult.identified(
            FREQ,
            List.of(Candidate.of(DecoderType.NBFM, LockState.LOCKED, 0.9)),
            DecoderType.NBFM,
            null,
            SignalKind.CONVENTIONAL,
            "NBFM · conventional",
            Map.of("key", "value"),
            -72.5
        );

        assertEquals(ClassificationOutcome.IDENTIFIED, r.outcome());
        assertTrue(r.isIdentified());
        assertFalse(r.isUnidentified());
        assertFalse(r.isNoSignal());
    }

    @Test
    void identified_bestDecoderAndPowerPreserved()
    {
        ClassificationResult r = ClassificationResult.identified(
            FREQ,
            List.of(Candidate.of(DecoderType.P25_PHASE1, LockState.LOCKED, 1.0)),
            DecoderType.P25_PHASE1,
            null,
            SignalKind.CONTROL,
            "P25P1 control",
            Map.of(),
            -55.0
        );

        assertEquals(DecoderType.P25_PHASE1, r.bestDecoder());
        assertEquals(-55.0, r.signalPowerDb(), 0.001);
        assertEquals(FREQ, r.centerFrequencyHz());
        assertNull(r.liveChain(), "v1 liveChain must always be null");
    }

    @Test
    void identified_nullCandidatesBecomesEmptyList()
    {
        ClassificationResult r = ClassificationResult.identified(
            FREQ, null, DecoderType.NBFM, null, null, null, null, 0.0
        );

        assertNotNull(r.candidates());
        assertTrue(r.candidates().isEmpty());
        assertNotNull(r.metadata());
        assertTrue(r.metadata().isEmpty());
        assertEquals(SignalKind.UNKNOWN, r.kind());
        assertEquals("", r.summary());
    }

    @Test
    void identified_returnsUnmodifiableCollections()
    {
        List<Candidate> mutableCandidates = new java.util.ArrayList<>();
        mutableCandidates.add(Candidate.of(DecoderType.DMR, LockState.LOCKED, 0.8));
        Map<String, String> mutableMeta = new java.util.HashMap<>();
        mutableMeta.put("k", "v");

        ClassificationResult r = ClassificationResult.identified(
            FREQ, mutableCandidates, DecoderType.DMR, null, SignalKind.CONVENTIONAL, "DMR", mutableMeta, -60.0
        );

        assertThrows(UnsupportedOperationException.class, () -> r.candidates().add(null));
        assertThrows(UnsupportedOperationException.class, () -> r.metadata().put("x", "y"));
    }

    // -------------------------------------------------------------------------
    // ClassificationResult.unidentified(...)
    // -------------------------------------------------------------------------

    @Test
    void unidentified_hasCorrectOutcome()
    {
        ClassificationResult r = ClassificationResult.unidentified(FREQ, List.of(), -80.0);

        assertEquals(ClassificationOutcome.UNIDENTIFIED, r.outcome());
        assertTrue(r.isUnidentified());
        assertFalse(r.isIdentified());
        assertNull(r.bestDecoder());
        assertNull(r.bestDecodeConfig());
        assertEquals("no match", r.summary());
        assertEquals(-80.0, r.signalPowerDb(), 0.001);
    }

    // -------------------------------------------------------------------------
    // ClassificationResult.noSignal(...)
    // -------------------------------------------------------------------------

    @Test
    void noSignal_hasCorrectOutcome()
    {
        ClassificationResult r = ClassificationResult.noSignal(FREQ, -110.0);

        assertEquals(ClassificationOutcome.NO_SIGNAL, r.outcome());
        assertTrue(r.isNoSignal());
        assertFalse(r.isIdentified());
        assertTrue(r.candidates().isEmpty());
        assertEquals("no signal", r.summary());
        assertEquals(-110.0, r.signalPowerDb(), 0.001);
    }

    // -------------------------------------------------------------------------
    // ClassificationResult.error(...)
    // -------------------------------------------------------------------------

    @Test
    void error_summaryContainsReason()
    {
        ClassificationResult r = ClassificationResult.error(FREQ, "tuner busy");

        assertEquals(ClassificationOutcome.ERROR, r.outcome());
        assertTrue(r.summary().contains("tuner busy"), "summary should contain the reason string");
        assertTrue(Double.isNaN(r.signalPowerDb()));
    }

    @Test
    void error_nullReasonUsesDefaultSummary()
    {
        ClassificationResult r = ClassificationResult.error(FREQ, null);
        assertFalse(r.summary().isEmpty(), "summary should not be empty for null reason");
    }

    // -------------------------------------------------------------------------
    // ClassificationResult.cancelled(...)
    // -------------------------------------------------------------------------

    @Test
    void cancelled_hasCorrectOutcome()
    {
        ClassificationResult r = ClassificationResult.cancelled(FREQ);

        assertEquals(ClassificationOutcome.CANCELLED, r.outcome());
        assertTrue(Double.isNaN(r.signalPowerDb()));
        assertEquals("cancelled", r.summary());
        assertEquals(FREQ, r.centerFrequencyHz());
    }

    // -------------------------------------------------------------------------
    // ClassificationRequest
    // -------------------------------------------------------------------------

    @Test
    void request_forFrequency_defaultsApplied()
    {
        ClassificationRequest req = ClassificationRequest.forFrequency(FREQ);

        assertEquals(FREQ, req.centerFrequencyHz());
        assertEquals(0, req.approximateBandwidthHz());
        assertFalse(req.keepWinningChainRunning());
        assertNotNull(req.candidateDecoders());
        assertFalse(req.candidateDecoders().isEmpty());
        assertNotNull(req.overallDeadline());
        assertTrue(req.overallDeadline().getSeconds() >= 1, "deadline should be positive");
        assertEquals(ClassificationRequest.DEFAULT_DEADLINE, req.overallDeadline());
    }

    @Test
    void request_defaultDeadlineIs12Seconds()
    {
        assertEquals(Duration.ofSeconds(12), ClassificationRequest.DEFAULT_DEADLINE);
    }

    @Test
    void request_candidateDecodersDefaultToAllPrimaries()
    {
        ClassificationRequest req = ClassificationRequest.forFrequency(FREQ);
        EnumSet<DecoderType> primaries = EnumSet.copyOf(DecoderType.PRIMARY_DECODERS);
        assertEquals(primaries, req.candidateDecoders());
    }

    @Test
    void request_labelDefaultsToFrequencyString()
    {
        ClassificationRequest req = ClassificationRequest.forFrequency(FREQ);
        assertEquals(Long.toString(FREQ), req.label());
    }

    @Test
    void request_nullCandidateDecodersDefaultsToPrimaries()
    {
        ClassificationRequest req = new ClassificationRequest(
            FREQ, 0, null, Duration.ofSeconds(5), false, "test"
        );
        assertNotNull(req.candidateDecoders());
        assertFalse(req.candidateDecoders().isEmpty());
    }

    @Test
    void request_nullOrZeroDeadlineDefaultsToDefault()
    {
        ClassificationRequest req = new ClassificationRequest(
            FREQ, 0, EnumSet.copyOf(DecoderType.PRIMARY_DECODERS), null, false, "test"
        );
        assertEquals(ClassificationRequest.DEFAULT_DEADLINE, req.overallDeadline());

        ClassificationRequest req2 = new ClassificationRequest(
            FREQ, 0, EnumSet.copyOf(DecoderType.PRIMARY_DECODERS), Duration.ZERO, false, "test2"
        );
        assertEquals(ClassificationRequest.DEFAULT_DEADLINE, req2.overallDeadline());
    }

    @Test
    void request_negativeFrequencyThrows()
    {
        assertThrows(IllegalArgumentException.class, () -> ClassificationRequest.forFrequency(-1L));
    }

    @Test
    void request_negativeBandwidthThrows()
    {
        assertThrows(IllegalArgumentException.class, () ->
            new ClassificationRequest(FREQ, -1, null, null, false, null)
        );
    }

    @Test
    void request_forFrequencyWithBandwidthAndLabel()
    {
        ClassificationRequest req = ClassificationRequest.forFrequency(FREQ, 12500, "chan-1");
        assertEquals(FREQ, req.centerFrequencyHz());
        assertEquals(12500, req.approximateBandwidthHz());
        assertEquals("chan-1", req.label());
    }
}
