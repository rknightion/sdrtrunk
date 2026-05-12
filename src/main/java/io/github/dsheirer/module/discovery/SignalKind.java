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
 * Kind of signal discovered or identified by the SignalClassifier.
 */
public enum SignalKind
{
    /** A trunking control channel (P25, DMR, LTR, MPT1327, Passport). */
    CONTROL,

    /** A decoded data channel or data burst. */
    DATA,

    /** A conventional voice channel or generic active conventional signal. */
    CONVENTIONAL,

    /** A trunked voice or data traffic channel. */
    TRAFFIC,

    /** The kind could not be determined from the decoded messages. */
    UNKNOWN
}
