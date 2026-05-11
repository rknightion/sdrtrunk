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

import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfiguration;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;

/**
 * Seam for acquiring a {@link ComplexSource} during a classification attempt.
 *
 * <p>In production the default binding is:</p>
 * <pre>
 *   (config, spec, name) -> (ComplexSource) tunerManager.getSource(config, spec, name)
 * </pre>
 *
 * <p>In tests a fake implementation is injected that returns a scripted source
 * without needing a real tuner.</p>
 *
 * @see SignalClassifier
 */
@FunctionalInterface
public interface SourceProvider
{
    /**
     * Acquires a complex source for the given configuration.
     *
     * @param config        source configuration (typically a {@link io.github.dsheirer.source.config.SourceConfigTuner})
     * @param specification channel specification (sample rate, bandwidth, filter parameters)
     * @param threadName    suggested thread name for the source's worker thread
     * @return the acquired source, or {@code null} if no tuner has capacity
     * @throws SourceException if a hardware or configuration error prevents acquisition
     */
    ComplexSource acquire(SourceConfiguration config, ChannelSpecification specification, String threadName)
        throws SourceException;
}
