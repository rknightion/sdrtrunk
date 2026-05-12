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

import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.Listener;

/**
 * Minimal {@link Module} that forwards {@link IdentifierUpdateNotification} events to a
 * {@link LockWatcher}, allowing the watcher to harvest identifiers (NAC, color code, etc.)
 * from a probe chain without being a full module itself.
 *
 * <p>When added to a {@link io.github.dsheirer.module.ProcessingChain}, the chain's
 * auto-wiring will subscribe this module to the chain's identifier-update broadcaster
 * via the {@link IdentifierUpdateListener} interface.</p>
 */
class IdentifierForwardingModule extends Module implements IdentifierUpdateListener
{
    private final LockWatcher mLockWatcher;

    /**
     * Constructs an instance that forwards identifier updates to the supplied watcher.
     *
     * @param lockWatcher the watcher to forward updates to
     */
    IdentifierForwardingModule(LockWatcher lockWatcher)
    {
        mLockWatcher = lockWatcher;
    }

    @Override
    public Listener<IdentifierUpdateNotification> getIdentifierUpdateListener()
    {
        return mLockWatcher.getIdentifierUpdateListener();
    }

    @Override
    public void reset() {}

    @Override
    public void start() {}

    @Override
    public void stop() {}

    @Override
    public void dispose() {}
}
