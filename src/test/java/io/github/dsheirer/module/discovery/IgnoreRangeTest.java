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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IgnoreRange value type.
 */
class IgnoreRangeTest
{
    // 154.0 – 154.5 MHz
    private static final long MIN = 154_000_000L;
    private static final long MAX = 154_500_000L;

    private final IgnoreRange range = IgnoreRange.of(MIN, MAX);

    // -------------------------------------------------------------------------
    // contains()
    // -------------------------------------------------------------------------

    @Test
    void contains_returnsTrue_forFrequencyAtLowerBound()
    {
        assertTrue(range.contains(MIN));
    }

    @Test
    void contains_returnsTrue_forFrequencyAtUpperBound()
    {
        assertTrue(range.contains(MAX));
    }

    @Test
    void contains_returnsTrue_forFrequencyInsideRange()
    {
        assertTrue(range.contains(154_250_000L));
    }

    @Test
    void contains_returnsFalse_forFrequencyBelowRange()
    {
        assertFalse(range.contains(MIN - 1));
    }

    @Test
    void contains_returnsFalse_forFrequencyAboveRange()
    {
        assertFalse(range.contains(MAX + 1));
    }

    // -------------------------------------------------------------------------
    // overlaps()
    // -------------------------------------------------------------------------

    @Test
    void overlaps_returnsTrue_whenSpanIsCompletelyInsideRange()
    {
        assertTrue(range.overlaps(154_100_000L, 154_400_000L));
    }

    @Test
    void overlaps_returnsTrue_whenRangeIsCompletelyInsideSpan()
    {
        assertTrue(range.overlaps(MIN - 1_000_000L, MAX + 1_000_000L));
    }

    @Test
    void overlaps_returnsTrue_whenSpanPartiallyOverlapsLower()
    {
        assertTrue(range.overlaps(MIN - 100_000L, MIN + 100_000L));
    }

    @Test
    void overlaps_returnsTrue_whenSpanPartiallyOverlapsUpper()
    {
        assertTrue(range.overlaps(MAX - 100_000L, MAX + 100_000L));
    }

    @Test
    void overlaps_returnsTrue_whenSpanTouchesLowerBound()
    {
        assertTrue(range.overlaps(MIN - 50_000L, MIN));
    }

    @Test
    void overlaps_returnsTrue_whenSpanTouchesUpperBound()
    {
        assertTrue(range.overlaps(MAX, MAX + 50_000L));
    }

    @Test
    void overlaps_returnsFalse_whenSpanIsCompletelyBelow()
    {
        assertFalse(range.overlaps(MIN - 200_000L, MIN - 1));
    }

    @Test
    void overlaps_returnsFalse_whenSpanIsCompletelyAbove()
    {
        assertFalse(range.overlaps(MAX + 1, MAX + 200_000L));
    }

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test
    void constructor_throwsIllegalArgument_whenMinExceedsMax()
    {
        assertThrows(IllegalArgumentException.class,
            () -> new IgnoreRange(MAX, MIN, null, java.time.Instant.now()));
    }

    @Test
    void constructor_permitsSingleFrequencyRange()
    {
        IgnoreRange single = IgnoreRange.of(MIN, MIN);
        assertTrue(single.contains(MIN));
        assertFalse(single.contains(MIN + 1));
    }
}
