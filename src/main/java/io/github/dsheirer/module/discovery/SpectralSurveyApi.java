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
 *
 * <h3>Survey strategy</h3>
 * <p>The single {@link #survey} method decides upfront whether the requested span fits
 * within the tuner's current sample rate:
 * <ul>
 *   <li><b>In-band (non-disruptive)</b> — span ≤ sample rate: accumulates FFT frames at the
 *       tuner's current center without retuning.</li>
 *   <li><b>Stepped (disruptive)</b> — span &gt; sample rate: retunes across the span one
 *       stride at a time, restoring the original center on completion (or on error/cancel).</li>
 * </ul>
 * In both cases the survey taps the tuner's full-rate wideband I/Q buffer directly
 * (via {@link TunerControl#addWidebandSampleListener}), bypassing the polyphase channelizer.</p>
 */
public interface SpectralSurveyApi
{
    /**
     * Performs a spectral survey over the given frequency span.
     *
     * <p>Requires a non-null, available {@link TunerControl}; completes exceptionally with a
     * descriptive message if the tuner is unavailable.</p>
     *
     * @param minHz        lower bound of the span in Hz (inclusive)
     * @param maxHz        upper bound of the span in Hz (inclusive)
     * @param dwell        how long to accumulate FFT frames (total dwell; divided across steps for swept)
     * @param thresholdDb  SNR threshold in dB above estimated noise floor
     * @param progress     progress listener (0.0..1.0); may be null
     * @param tunerControl control seam for the active tuner; must be non-null and available
     * @return cancellable future resolving to the list of detected peaks (never null, may be empty)
     */
    CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                               double thresholdDb,
                                               SpectralSurvey.ProgressListener progress,
                                               TunerControl tunerControl);
}
