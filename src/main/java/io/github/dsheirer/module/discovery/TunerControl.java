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

import io.github.dsheirer.source.SourceException;

/**
 * Narrow seam for the tuner operations needed by the stepped-sweep survey.
 *
 * <p>The production binding is {@link TunerControlImpl}, which wraps a
 * {@link io.github.dsheirer.source.tuner.TunerController}.
 * Tests inject a {@link FakeTunerControl} that records calls without
 * touching hardware.</p>
 *
 * <h3>Why this seam exists</h3>
 * <p>The stepped sweep needs to: query the current center frequency and
 * instantaneous bandwidth; retune the hardware; restore the prior frequency
 * on completion (or on error/cancel).  Wrapping these in a single interface
 * keeps {@link SpectralSurvey} testable without a real SDR device.</p>
 */
public interface TunerControl
{
    /**
     * Returns the tuner's current center frequency in Hz.
     *
     * @return center frequency, Hz
     */
    long getCurrentCenterFreqHz();

    /**
     * Returns the tuner's current (instantaneous) usable bandwidth in Hz.
     *
     * <p>This is the span the polyphase channelizer can serve without retuning;
     * approximately {@code sampleRate * usableBandwidthPercentage}.</p>
     *
     * @return usable bandwidth, Hz
     */
    long getUsableBandwidthHz();

    /**
     * Returns the minimum frequency the tuner can be set to, in Hz.
     *
     * @return minimum tunable frequency, Hz
     */
    long getMinFrequencyHz();

    /**
     * Returns the maximum frequency the tuner can be set to, in Hz.
     *
     * @return maximum tunable frequency, Hz
     */
    long getMaxFrequencyHz();

    /**
     * Sets the tuner's center frequency.
     *
     * <p><b>Contract:</b> this method is disruptive — any active channel sources
     * on this tuner will see mistuned / corrupted samples until the caller
     * completes the sweep and calls {@link #setCenterFreqHz} again to restore
     * the prior frequency.  The caller is responsible for ensuring that the
     * operator has consented to this disruption before invoking the sweep.</p>
     *
     * @param frequencyHz desired center frequency in Hz
     * @throws SourceException if the hardware rejects the requested frequency
     */
    void setCenterFreqHz(long frequencyHz) throws SourceException;

    /**
     * Returns {@code true} if this control is connected to a real tuner that
     * can be commanded.  The stepped sweep checks this before attempting to
     * retune: if {@code false} the survey fails with a descriptive error rather
     * than silently doing nothing.
     *
     * @return {@code true} if the underlying hardware is available
     */
    boolean isAvailable();
}
