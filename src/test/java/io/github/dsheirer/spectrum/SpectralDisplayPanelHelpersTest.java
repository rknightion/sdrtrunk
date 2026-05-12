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
package io.github.dsheirer.spectrum;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the package-private helper methods extracted into {@link SpectralDisplayPanel}.
 *
 * <p>These methods are pure logic (no Swing), so they can be tested without a display.</p>
 */
class SpectralDisplayPanelHelpersTest
{
    // -------------------------------------------------------------------------
    // isNonTrivialDrag (static, pure) — threshold is >= 10 pixels
    // -------------------------------------------------------------------------

    @Test
    void isNonTrivialDrag_zero_false()
    {
        assertFalse(SpectralDisplayPanel.isNonTrivialDrag(100, 100));
    }

    @Test
    void isNonTrivialDrag_fivePixels_false()
    {
        assertFalse(SpectralDisplayPanel.isNonTrivialDrag(100, 105));
    }

    @Test
    void isNonTrivialDrag_ninePixels_false()
    {
        assertFalse(SpectralDisplayPanel.isNonTrivialDrag(100, 109));
    }

    @Test
    void isNonTrivialDrag_tenPixels_true()
    {
        assertTrue(SpectralDisplayPanel.isNonTrivialDrag(100, 110));
    }

    @Test
    void isNonTrivialDrag_reversed_trueSameAsForward()
    {
        // The method should be symmetric (absolute value)
        assertTrue(SpectralDisplayPanel.isNonTrivialDrag(110, 100));
    }

    @Test
    void isNonTrivialDrag_largeRange_true()
    {
        assertTrue(SpectralDisplayPanel.isNonTrivialDrag(0, 500));
    }
}
