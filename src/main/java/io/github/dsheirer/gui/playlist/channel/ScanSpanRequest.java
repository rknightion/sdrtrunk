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
package io.github.dsheirer.gui.playlist.channel;

/**
 * Request to open the band-scan dialog pre-filled with a frequency span from the spectral display.
 *
 * <p>Posted on the global {@link io.github.dsheirer.eventbus.MyEventBus} by the spectral display's
 * "Scan this view…" context-menu item.  Phase 4 will add a subscriber that opens the ScanDialog.</p>
 *
 * @param minFrequencyHz lower edge of the span to scan (inclusive), in Hz
 * @param maxFrequencyHz upper edge of the span to scan (inclusive), in Hz
 */
public record ScanSpanRequest(long minFrequencyHz, long maxFrequencyHz)
{
    /**
     * Compact constructor — validates that min ≤ max and both are positive.
     */
    public ScanSpanRequest
    {
        if(minFrequencyHz <= 0)
        {
            throw new IllegalArgumentException("minFrequencyHz must be positive, got: " + minFrequencyHz);
        }

        if(maxFrequencyHz <= 0)
        {
            throw new IllegalArgumentException("maxFrequencyHz must be positive, got: " + maxFrequencyHz);
        }

        if(minFrequencyHz > maxFrequencyHz)
        {
            throw new IllegalArgumentException("minFrequencyHz (" + minFrequencyHz + ") must be ≤ maxFrequencyHz ("
                + maxFrequencyHz + ")");
        }
    }

    /**
     * Span width in Hz.
     *
     * @return {@code maxFrequencyHz - minFrequencyHz}
     */
    public long spanHz()
    {
        return maxFrequencyHz - minFrequencyHz;
    }

    /**
     * Centre frequency of the span.
     *
     * @return midpoint in Hz
     */
    public long centerFrequencyHz()
    {
        return minFrequencyHz + spanHz() / 2;
    }
}
