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

import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production binding of {@link TunerControl} over a {@link TunerController}.
 *
 * <h3>Safety note</h3>
 * <p>Calling {@link #setCenterFreqHz} while the tuner has active
 * {@code TunerChannelSource}s will cause those sources to receive samples at
 * the wrong frequency and produce corrupt decoded output until the caller
 * restores the original frequency.  The stepped sweep is only used after the
 * operator has explicitly confirmed the disruption via the {@code ScanDialog}
 * warning banner.</p>
 */
public class TunerControlImpl implements TunerControl
{
    private static final Logger mLog = LoggerFactory.getLogger(TunerControlImpl.class);

    private final TunerController mTunerController;

    /**
     * Constructs the impl over the given controller.
     *
     * @param tunerController the underlying hardware controller; must not be null
     */
    public TunerControlImpl(TunerController tunerController)
    {
        if(tunerController == null)
        {
            throw new IllegalArgumentException("tunerController must not be null");
        }

        mTunerController = tunerController;
    }

    @Override
    public long getCurrentCenterFreqHz()
    {
        return mTunerController.getFrequency();
    }

    @Override
    public long getUsableBandwidthHz()
    {
        return mTunerController.getUsableBandwidth();
    }

    @Override
    public long getMinFrequencyHz()
    {
        return mTunerController.getMinimumFrequency();
    }

    @Override
    public long getMaxFrequencyHz()
    {
        return mTunerController.getMaximumFrequency();
    }

    @Override
    public void setCenterFreqHz(long frequencyHz) throws SourceException
    {
        mTunerController.setFrequency(frequencyHz);
    }

    @Override
    public boolean isAvailable()
    {
        return true;
    }
}
