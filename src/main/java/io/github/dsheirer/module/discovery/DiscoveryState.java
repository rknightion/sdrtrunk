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

/**
 * Lifecycle state of a Discovery row produced by BandScanController.
 */
public enum DiscoveryState
{
    /** Energy was detected at this frequency but probing has not started yet. */
    ENERGY_DETECTED,

    /** The SignalClassifier is actively probing this frequency. */
    PROBING,

    /** A decoder locked — bestDecoder and metadata are available. */
    IDENTIFIED,

    /** Energy was present but no decoder locked (or only partial). */
    UNIDENTIFIED,

    /** This frequency overlaps an already-configured channel in the playlist. */
    KNOWN,

    /** An unexpected error occurred during probing. */
    ERROR
}
