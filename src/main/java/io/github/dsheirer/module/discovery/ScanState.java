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
 * Lifecycle state of a {@link BandScanController} scan operation.
 */
public enum ScanState
{
    /** No scan is running; the controller is ready to accept a new {@link ScanRequest}. */
    IDLE,

    /** The spectral survey is in progress: acquiring and analysing the I/Q spectrum. */
    SURVEYING,

    /** The survey is complete; the classifier is probing detected energy peaks. */
    PROBING,

    /** All peaks have been probed; the scan completed normally (non-continuous). */
    DONE,

    /** A continuous scan has completed one cycle and is waiting for the re-survey interval. */
    IDLE_CONTINUOUS,

    /** The scan was stopped via {@link BandScanController#stop()}. */
    CANCELLED,

    /** An unexpected error occurred during the scan. */
    ERROR
}
