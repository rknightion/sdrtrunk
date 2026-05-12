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
 * High-level outcome of a classification attempt by the SignalClassifier.
 */
public enum ClassificationOutcome
{
    /** At least one decoder achieved a sustained lock; bestDecoder/bestDecodeConfig are valid. */
    IDENTIFIED,

    /** Energy was present but no decoder locked (or only partial locks). */
    UNIDENTIFIED,

    /** No significant energy was detected at the requested frequency. */
    NO_SIGNAL,

    /** The classification could not be completed due to an error (e.g. no tuner capacity). */
    ERROR,

    /** The classification was cancelled by the caller before it could complete. */
    CANCELLED
}
