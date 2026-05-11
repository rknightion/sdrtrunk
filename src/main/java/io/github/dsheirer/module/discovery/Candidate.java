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

import io.github.dsheirer.module.decode.DecoderType;

/**
 * Result of probing a single decoder candidate against a captured signal.
 *
 * @param decoderType the decoder that was tried
 * @param lockState   how well it locked
 * @param lockQuality 0.0–1.0 quality metric (higher = faster/cleaner lock)
 * @param note        optional human-readable note (e.g. partial sync detail, error message)
 */
public record Candidate(DecoderType decoderType, LockState lockState, double lockQuality, String note)
{
    /**
     * Compact constructor validating required fields.
     */
    public Candidate
    {
        if(decoderType == null)
        {
            throw new IllegalArgumentException("decoderType must not be null");
        }

        if(lockState == null)
        {
            throw new IllegalArgumentException("lockState must not be null");
        }

        if(lockQuality < 0.0 || lockQuality > 1.0)
        {
            throw new IllegalArgumentException("lockQuality must be in [0.0, 1.0], got: " + lockQuality);
        }
    }

    /**
     * Convenience factory for a candidate with no note.
     */
    public static Candidate of(DecoderType decoderType, LockState lockState, double lockQuality)
    {
        return new Candidate(decoderType, lockState, lockQuality, null);
    }
}
