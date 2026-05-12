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
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production binding of {@link TunerControl} over a {@link TunerController}.
 *
 * <p>The constructor takes a {@link Supplier}{@code <TunerController>} so that
 * {@link #isAvailable()} and every operation always operate on <em>whatever tuner the
 * spectral display is currently showing</em>, even after the operator switches tuners.
 * The supplier is queried on each call; it may return {@code null} if no tuner is
 * currently displayed, in which case {@link #isAvailable()} returns {@code false} and
 * any mutating call throws {@link IllegalStateException}.</p>
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

    private final Supplier<TunerController> mControllerSupplier;

    /**
     * Constructs the impl with a supplier of the active tuner controller.
     *
     * <p>The supplier is evaluated on each call so that this instance tracks
     * whichever tuner the spectral display is currently showing.  A supplier
     * that returns {@code null} means "no tuner currently selected".</p>
     *
     * @param controllerSupplier supplier for the current tuner controller; must not be null,
     *                           but may return null when no tuner is displayed
     */
    public TunerControlImpl(Supplier<TunerController> controllerSupplier)
    {
        if(controllerSupplier == null)
        {
            throw new IllegalArgumentException("controllerSupplier must not be null");
        }

        mControllerSupplier = controllerSupplier;
    }

    /**
     * Returns the current tuner controller from the supplier, or {@code null} if none.
     */
    private TunerController controller()
    {
        return mControllerSupplier.get();
    }

    /**
     * Returns the current tuner controller, throwing if none is available.
     */
    private TunerController requireController()
    {
        TunerController tc = mControllerSupplier.get();
        if(tc == null)
        {
            throw new IllegalStateException("No tuner controller available (no tuner currently displayed)");
        }
        return tc;
    }

    @Override
    public long getCurrentCenterFreqHz()
    {
        return requireController().getFrequency();
    }

    @Override
    public long getUsableBandwidthHz()
    {
        return requireController().getUsableBandwidth();
    }

    @Override
    public long getMinFrequencyHz()
    {
        return requireController().getMinimumFrequency();
    }

    @Override
    public long getMaxFrequencyHz()
    {
        return requireController().getMaximumFrequency();
    }

    @Override
    public void setCenterFreqHz(long frequencyHz) throws SourceException
    {
        requireController().setFrequency(frequencyHz);
    }

    @Override
    public boolean isAvailable()
    {
        return controller() != null;
    }
}
