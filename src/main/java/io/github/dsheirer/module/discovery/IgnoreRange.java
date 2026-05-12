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

import java.time.Instant;

/**
 * A frequency range that the operator has asked to ignore during discovery scanning.
 *
 * @param minHz    inclusive lower bound of the ignored range in Hz
 * @param maxHz    inclusive upper bound of the ignored range in Hz
 * @param note     optional operator note explaining why the range is ignored
 * @param addedAt  when the operator added this entry
 */
public record IgnoreRange(long minHz, long maxHz, String note, Instant addedAt)
{
    /**
     * Compact constructor validating the range boundaries.
     */
    public IgnoreRange
    {
        if(minHz > maxHz)
        {
            throw new IllegalArgumentException("minHz (" + minHz + ") must not exceed maxHz (" + maxHz + ")");
        }
    }

    /**
     * Convenience factory with a current timestamp.
     */
    public static IgnoreRange of(long minHz, long maxHz, String note)
    {
        return new IgnoreRange(minHz, maxHz, note, Instant.now());
    }

    /**
     * Convenience factory with a current timestamp and no note.
     */
    public static IgnoreRange of(long minHz, long maxHz)
    {
        return new IgnoreRange(minHz, maxHz, null, Instant.now());
    }

    /**
     * Returns true if the given frequency falls within this ignored range (inclusive on both ends).
     *
     * @param frequencyHz frequency to test
     * @return true when minHz &lt;= frequencyHz &lt;= maxHz
     */
    public boolean contains(long frequencyHz)
    {
        return frequencyHz >= minHz && frequencyHz <= maxHz;
    }

    /**
     * Returns true if this range overlaps with the given frequency span [spanMinHz, spanMaxHz].
     * Two ranges overlap when one does not lie entirely before or after the other.
     *
     * @param spanMinHz lower bound of the span to test (inclusive)
     * @param spanMaxHz upper bound of the span to test (inclusive)
     * @return true when the ranges intersect
     */
    public boolean overlaps(long spanMinHz, long spanMaxHz)
    {
        return maxHz >= spanMinHz && minHz <= spanMaxHz;
    }
}
