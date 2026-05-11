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

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Testability seam for the spectral survey operation used by {@link BandScanController}.
 *
 * <p>The production binding is {@link SpectralSurvey}, which implements this interface.
 * Unit tests inject a fake implementation that returns canned {@link EnergyPeak} lists
 * without requiring a real tuner.</p>
 */
public interface SpectralSurveyApi
{
    /**
     * Performs an in-band spectral survey over the given frequency span.
     *
     * @param minHz       lower bound of the span in Hz (inclusive)
     * @param maxHz       upper bound of the span in Hz (inclusive)
     * @param dwell       how long to accumulate FFT frames
     * @param thresholdDb SNR threshold in dB above estimated noise floor
     * @param progress    progress listener (0.0..1.0); may be null
     * @return cancellable future resolving to the list of detected peaks (never null, may be empty)
     */
    CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                               double thresholdDb,
                                               SpectralSurvey.ProgressListener progress);
}
