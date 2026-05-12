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
package io.github.dsheirer.util;

import javafx.application.Platform;

/**
 * Small utility for safely running a {@link Runnable} on the JavaFX Application Thread
 * from any calling thread, with a headless / unit-test fallback.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>If the caller is already on the FX thread — run inline immediately.</li>
 *   <li>If the FX toolkit is running but we are on a background thread — delegate to
 *       {@link Platform#runLater(Runnable)} so the action executes on the FX thread.</li>
 *   <li>If the FX toolkit is not running (headless / unit tests) —
 *       {@code Platform.runLater} throws {@code IllegalStateException: Toolkit not initialized}.
 *       We catch that exception and execute the action inline on the calling thread.
 *       This keeps all headless tests green without any special mock setup.</li>
 * </ul>
 *
 * <p>This class is package-visible to callers; it has no instance state and only provides
 * the {@link #run(Runnable)} static method.</p>
 */
public final class FxThreads
{
    /** Utility class — do not instantiate. */
    private FxThreads() {}

    /**
     * Runs {@code action} on the JavaFX Application Thread, or inline if the toolkit is
     * not running (headless / test path).
     *
     * @param action the action to run; must not be null
     */
    public static void run(Runnable action)
    {
        if(Platform.isFxApplicationThread())
        {
            action.run();
            return;
        }

        try
        {
            Platform.runLater(action);
        }
        catch(IllegalStateException e)
        {
            // FX toolkit not started — run inline (headless / test path)
            action.run();
        }
    }
}
