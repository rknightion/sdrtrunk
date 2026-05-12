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
 * <h3>In-band vs stepped survey</h3>
 * <ul>
 *   <li>{@link #survey} — non-disruptive in-band survey; the span must fit within the
 *       tuner's instantaneous bandwidth.  Returns a failed future if the span is too wide.</li>
 *   <li>{@link #surveyWide} — stepped sweep; retunes the tuner across the span one
 *       stride at a time, restoring the original center frequency on completion (or on
 *       error/cancel).  Disruptive — only called after the operator confirms the dialog
 *       warning.</li>
 * </ul>
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

    /**
     * Performs a wide stepped-sweep survey over a span that exceeds the tuner's
     * instantaneous bandwidth.
     *
     * <p>Steps the tuner's center frequency across the span at ~80% sample-rate
     * strides (so adjacent steps overlap at the edges), runs an in-band mini-survey at
     * each step, accumulates peaks, and restores the original center frequency in a
     * {@code finally} block (even on cancel or error).</p>
     *
     * <p>The per-step dwell is {@code totalDwell / stepCount} (with a floor of 500 ms)
     * so the aggregate observation time equals the caller-specified dwell regardless of
     * how many steps are required.</p>
     *
     * <p>The default implementation throws {@link UnsupportedOperationException}.
     * Test fakes that only exercise the in-band path need not override this method.
     * {@link SpectralSurvey} provides the full implementation.</p>
     *
     * @param minHz        lower bound of the span in Hz (inclusive)
     * @param maxHz        upper bound of the span in Hz (inclusive)
     * @param dwell        total dwell budget for the whole sweep (divided equally across steps)
     * @param thresholdDb  SNR threshold in dB above estimated noise floor
     * @param tunerControl control seam used to read and set the tuner's center frequency
     * @param progress     progress listener (0.0..1.0); may be null
     * @return cancellable future resolving to the merged list of detected peaks (never null, may be empty)
     */
    default CompletableFuture<List<EnergyPeak>> surveyWide(long minHz, long maxHz, Duration dwell,
                                                            double thresholdDb,
                                                            TunerControl tunerControl,
                                                            SpectralSurvey.ProgressListener progress)
    {
        CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new UnsupportedOperationException(
            "surveyWide not implemented by this SpectralSurveyApi instance"));
        return failed;
    }
}
