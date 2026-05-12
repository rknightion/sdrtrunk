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
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

/**
 * Parameters for a single band-scan operation driven by {@link BandScanController}.
 *
 * <p>Use {@link #defaults(long, long, DiscoveryPreference)} to build a request seeded
 * from the user's current preferences, or construct the record directly for full control.</p>
 *
 * <h3>In-band vs stepped sweep</h3>
 * <p>When {@code tunerControl} is {@code null}, the scan uses the non-disruptive in-band
 * survey.  When {@code tunerControl} is non-null, the scan uses the stepped sweep (
 * {@link SpectralSurveyApi#surveyWide}), which retunes the hardware across the span.
 * The {@link io.github.dsheirer.gui.playlist.discovery.ScanDialog} sets this field only
 * after the operator has confirmed the disruption warning.</p>
 *
 * @param minFrequencyHz        lower bound of the scan span in Hz
 * @param maxFrequencyHz        upper bound of the scan span in Hz
 * @param candidateDecoders     decoders to try during classification probing
 * @param surveyDwell           how long the spectral survey lingers at each step
 * @param thresholdDb           SNR threshold (dB above noise floor) for peak detection
 * @param maxSignalsToProbe     maximum number of energy peaks to classify (0 = unlimited)
 * @param continuous            whether to re-survey after {@code continuousInterval} and repeat
 * @param continuousInterval    delay between survey cycles when {@code continuous == true}
 * @param tunerControl          if non-null, the stepped sweep is used with this tuner control seam;
 *                              if null, the in-band (non-disruptive) survey is used
 */
public record ScanRequest(
    long minFrequencyHz,
    long maxFrequencyHz,
    EnumSet<DecoderType> candidateDecoders,
    Duration surveyDwell,
    double thresholdDb,
    int maxSignalsToProbe,
    boolean continuous,
    Duration continuousInterval,
    TunerControl tunerControl)
{
    /** Default maximum number of peaks to probe in a single scan cycle. */
    public static final int DEFAULT_MAX_SIGNALS_TO_PROBE = 200;

    /** Default continuous re-scan interval. */
    public static final Duration DEFAULT_CONTINUOUS_INTERVAL = Duration.ofMinutes(5);

    /**
     * Compact constructor that validates required fields.
     */
    public ScanRequest
    {
        if(minFrequencyHz >= maxFrequencyHz)
        {
            throw new IllegalArgumentException(
                "minFrequencyHz (" + minFrequencyHz + ") must be less than maxFrequencyHz (" + maxFrequencyHz + ")");
        }

        if(minFrequencyHz <= 0)
        {
            throw new IllegalArgumentException("minFrequencyHz must be positive, got: " + minFrequencyHz);
        }

        if(surveyDwell == null || surveyDwell.isNegative() || surveyDwell.isZero())
        {
            throw new IllegalArgumentException("surveyDwell must be a positive duration");
        }

        if(maxSignalsToProbe < 0)
        {
            throw new IllegalArgumentException("maxSignalsToProbe must not be negative, got: " + maxSignalsToProbe);
        }

        if(candidateDecoders == null || candidateDecoders.isEmpty())
        {
            candidateDecoders = EnumSet.copyOf(DecoderType.PRIMARY_DECODERS);
        }

        if(continuousInterval == null || continuousInterval.isNegative() || continuousInterval.isZero())
        {
            continuousInterval = DEFAULT_CONTINUOUS_INTERVAL;
        }

        // tunerControl may be null (in-band mode) — no validation required
    }

    /**
     * Returns {@code true} if this request requires a stepped sweep (i.e. a non-null
     * {@link TunerControl} was supplied).
     */
    public boolean requiresSteppedSweep()
    {
        return tunerControl != null;
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Creates a scan request seeded from the user's current discovery preferences.
     *
     * <p>The candidate decoder set is {@code getDefaultScanDecoders() minus getExcludedDecoders()}.
     * If the resulting set is empty, all {@link DecoderType#PRIMARY_DECODERS} are used.</p>
     *
     * @param minFrequencyHz lower bound of the scan span in Hz
     * @param maxFrequencyHz upper bound of the scan span in Hz
     * @param prefs          current user preferences
     * @return a scan request configured from prefs
     */
    public static ScanRequest defaults(long minFrequencyHz, long maxFrequencyHz, DiscoveryPreference prefs)
    {
        Set<DecoderType> candidates = prefs.getDefaultScanDecoders();
        candidates.removeAll(prefs.getExcludedDecoders());

        EnumSet<DecoderType> decoderSet = candidates.isEmpty()
            ? EnumSet.copyOf(DecoderType.PRIMARY_DECODERS)
            : EnumSet.copyOf(candidates);

        return new ScanRequest(
            minFrequencyHz,
            maxFrequencyHz,
            decoderSet,
            prefs.getSurveyDwell(),
            prefs.getEnergyThresholdDb(),
            DEFAULT_MAX_SIGNALS_TO_PROBE,
            false,
            DEFAULT_CONTINUOUS_INTERVAL,
            null   // in-band mode; caller sets a non-null TunerControl for stepped sweep
        );
    }
}
