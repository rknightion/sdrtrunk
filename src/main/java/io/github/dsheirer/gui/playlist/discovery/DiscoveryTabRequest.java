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
package io.github.dsheirer.gui.playlist.discovery;

import io.github.dsheirer.gui.playlist.PlaylistEditorRequest;

/**
 * Request to show the Discovery tab in the playlist editor.
 *
 * <p>May optionally carry a frequency to focus and/or a pre-filled span to open the scan dialog with.</p>
 */
public class DiscoveryTabRequest extends PlaylistEditorRequest
{
    /** Frequency to highlight in the discovery table; 0 means no specific row. */
    private final long mFocusFrequencyHz;

    /** Min frequency for pre-filling the scan dialog; 0 means no pre-fill. */
    private final long mScanMinHz;

    /** Max frequency for pre-filling the scan dialog; 0 means no pre-fill. */
    private final long mScanMaxHz;

    /**
     * Creates a simple show-discovery-tab request with no specific focus or pre-fill.
     */
    public DiscoveryTabRequest()
    {
        this(0L, 0L, 0L);
    }

    /**
     * Creates a show-discovery-tab request that also focuses a specific frequency row.
     *
     * @param focusFrequencyHz frequency of the discovery to highlight, in Hz; 0 = none
     */
    public DiscoveryTabRequest(long focusFrequencyHz)
    {
        this(focusFrequencyHz, 0L, 0L);
    }

    /**
     * Creates a show-discovery-tab request that also opens the scan dialog pre-filled with a span.
     *
     * @param scanMinHz lower edge of the scan span, in Hz; 0 = no pre-fill
     * @param scanMaxHz upper edge of the scan span, in Hz; 0 = no pre-fill
     * @param focusFrequencyHz frequency to highlight, in Hz; 0 = none
     */
    public DiscoveryTabRequest(long focusFrequencyHz, long scanMinHz, long scanMaxHz)
    {
        mFocusFrequencyHz = focusFrequencyHz;
        mScanMinHz = scanMinHz;
        mScanMaxHz = scanMaxHz;
    }

    @Override
    public TabName getTabName()
    {
        return TabName.DISCOVERY;
    }

    /**
     * Frequency to highlight in the discovery table, in Hz.  Returns 0 if no row should be focused.
     */
    public long getFocusFrequencyHz()
    {
        return mFocusFrequencyHz;
    }

    /**
     * Lower edge of the pre-filled scan span, in Hz.  Returns 0 if no scan dialog should open.
     */
    public long getScanMinHz()
    {
        return mScanMinHz;
    }

    /**
     * Upper edge of the pre-filled scan span, in Hz.  Returns 0 if no scan dialog should open.
     */
    public long getScanMaxHz()
    {
        return mScanMaxHz;
    }

    /**
     * Returns true if this request should open the scan dialog with a pre-filled span.
     */
    public boolean hasScanSpan()
    {
        return mScanMinHz > 0 && mScanMaxHz > mScanMinHz;
    }
}
