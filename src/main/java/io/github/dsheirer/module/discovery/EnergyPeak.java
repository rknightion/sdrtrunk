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
 * A signal energy peak found by SpectralSurvey within a frequency band.
 *
 * @param centerFrequencyHz  estimated center frequency of the signal
 * @param occupiedBandwidthHz estimated occupied bandwidth of the signal
 * @param powerDb             peak power in dB relative to full-scale
 * @param snrDb               estimated signal-to-noise ratio in dB
 */
public record EnergyPeak(long centerFrequencyHz, int occupiedBandwidthHz, double powerDb, double snrDb)
{
    /**
     * Compact constructor validating required fields.
     */
    public EnergyPeak
    {
        if(centerFrequencyHz <= 0)
        {
            throw new IllegalArgumentException("centerFrequencyHz must be positive, got: " + centerFrequencyHz);
        }

        if(occupiedBandwidthHz < 0)
        {
            throw new IllegalArgumentException("occupiedBandwidthHz must not be negative, got: " + occupiedBandwidthHz);
        }
    }
}
