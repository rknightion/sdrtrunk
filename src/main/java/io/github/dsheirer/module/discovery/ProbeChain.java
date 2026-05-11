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

import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderType;

/**
 * A lightweight container pairing a transient probe {@link ProcessingChain} with its
 * associated {@link LockWatcher}, so the two can be managed together during probing.
 *
 * @param decoderType the protocol this chain is probing
 * @param chain       the probe chain (decoder-only; no audio/log/recorder modules); must not be null
 * @param lockWatcher the watcher observing this chain's decoder-state events; must not be null
 */
public record ProbeChain(DecoderType decoderType, ProcessingChain chain, LockWatcher lockWatcher)
{
    /**
     * Compact constructor validating that neither {@code chain} nor {@code lockWatcher} is null.
     */
    public ProbeChain
    {
        if(decoderType == null)
        {
            throw new IllegalArgumentException("decoderType must not be null");
        }
        if(chain == null)
        {
            throw new IllegalArgumentException("chain must not be null");
        }
        if(lockWatcher == null)
        {
            throw new IllegalArgumentException("lockWatcher must not be null");
        }
    }
}
