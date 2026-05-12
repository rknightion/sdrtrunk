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
 * Event fired by {@link DiscoveryModel} to notify listeners of changes to the discovery list.
 *
 * <p>Consumers register via {@link DiscoveryModel#addListener} using the
 * {@code io.github.dsheirer.sample.Listener} callback pattern.
 * The Phase-4 Swing overlay will use this to paint frequency-band annotations.</p>
 *
 * @param type      the kind of change that occurred
 * @param discovery the affected discovery row; may be {@code null} for {@link Type#CLEARED}
 */
public record DiscoveryEvent(Type type, Discovery discovery)
{
    /**
     * Types of discovery list changes.
     */
    public enum Type
    {
        /** A new {@link Discovery} row was appended to the model. */
        ADDED,

        /** An existing {@link Discovery} row was updated in place (state, decoder, confidence, etc.). */
        UPDATED,

        /** A {@link Discovery} row was removed from the model. */
        REMOVED,

        /**
         * All rows were cleared.  The {@code discovery} field of the containing
         * {@link DiscoveryEvent} will be {@code null} for this type.
         */
        CLEARED
    }
}
