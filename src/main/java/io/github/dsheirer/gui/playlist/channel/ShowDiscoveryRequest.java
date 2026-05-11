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
 * Request to bring the Discovery panel/tab into focus.
 *
 * <p>Posted on the global {@link io.github.dsheirer.eventbus.MyEventBus} by the spectral display's
 * "Show discoveries" context-menu item.  Phase 4 will add a subscriber in the playlist editor
 * that switches to the Discovery tab and optionally selects a specific discovery row.</p>
 *
 * <p>The optional {@code focusFrequencyHz} field allows the caller to request that the
 * tab scroll to / highlight any discovery near the given frequency.  A value of {@code 0}
 * means "just show the tab, don't focus a specific row".</p>
 *
 * @param focusFrequencyHz frequency to highlight, in Hz; {@code 0} = no specific row
 */
public record ShowDiscoveryRequest(long focusFrequencyHz)
{
    /**
     * Creates a show-discoveries request with no specific row focus.
     */
    public ShowDiscoveryRequest()
    {
        this(0L);
    }
}
