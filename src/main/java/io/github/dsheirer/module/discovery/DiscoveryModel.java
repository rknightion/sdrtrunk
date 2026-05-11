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

import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Holds the set of {@link Discovery} rows produced during a band scan and notifies
 * registered listeners of add / update / remove / clear events.
 *
 * <h3>JavaFX observable list</h3>
 * <p>The internal list is a {@link javafx.collections.ObservableList} so that a Phase-4
 * JavaFX table can bind to it directly and pick up structural changes (add/remove) as well
 * as in-place property mutations (because {@link Discovery} exposes
 * {@code javafx.beans.property} fields).</p>
 *
 * <h3>Threading</h3>
 * <p>All {@code ObservableList} mutations (add, remove, clear) <em>should</em> be performed
 * on the JavaFX Application Thread when a UI is bound, so that JavaFX scene-graph observers
 * do not receive notifications off-thread.  For the Phase-3 headless use-case (no bound UI)
 * mutations may be performed on any thread.  {@link BandScanController} explicitly notes this
 * contract and Phase 4 will marshal appropriately via {@code Platform.runLater}.
 * The {@link Broadcaster}-based {@link DiscoveryEvent} stream is fired synchronously by
 * whatever thread calls the mutating method.</p>
 */
public class DiscoveryModel
{
    private final ObservableList<Discovery> mDiscoveries = FXCollections.observableArrayList();
    private final Broadcaster<DiscoveryEvent> mBroadcaster = new Broadcaster<>();

    // -------------------------------------------------------------------------
    // Listener management
    // -------------------------------------------------------------------------

    /**
     * Adds a listener that will receive {@link DiscoveryEvent}s as rows are added, updated,
     * removed, or cleared.
     *
     * @param listener the listener to register; must not be null
     */
    public void addListener(Listener<DiscoveryEvent> listener)
    {
        mBroadcaster.addListener(listener);
    }

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     */
    public void removeListener(Listener<DiscoveryEvent> listener)
    {
        mBroadcaster.removeListener(listener);
    }

    // -------------------------------------------------------------------------
    // Read access
    // -------------------------------------------------------------------------

    /**
     * Returns an unmodifiable view of the discovery list.
     *
     * <p>Phase-4 table bindings should use the underlying {@link ObservableList}
     * directly via {@link #getDiscoveries()}; this view is provided for safe
     * non-UI consumers.</p>
     *
     * @return unmodifiable snapshot-stable view
     */
    public ObservableList<Discovery> getDiscoveries()
    {
        return FXCollections.unmodifiableObservableList(mDiscoveries);
    }

    /**
     * Finds all discoveries whose frequency range overlaps the given span.
     *
     * <p>Overlap test: the discovery occupies [{@code center - bw/2}, {@code center + bw/2}].
     * Two ranges overlap when neither lies entirely before or after the other, i.e.
     * {@code aMax >= bMin && aMin <= bMax}.</p>
     *
     * @param centerHz   center of the span to test, in Hz
     * @param widthHz    total width of the span to test, in Hz
     * @return list of discoveries that overlap the span; never null, may be empty
     */
    public List<Discovery> findOverlapping(long centerHz, int widthHz)
    {
        long spanMin = centerHz - widthHz / 2L;
        long spanMax = centerHz + widthHz / 2L;

        List<Discovery> result = new ArrayList<>();

        for(Discovery d : mDiscoveries)
        {
            long dMin = d.getCenterFrequencyHz() - d.getBandwidthHz() / 2L;
            long dMax = d.getCenterFrequencyHz() + d.getBandwidthHz() / 2L;

            if(dMax >= spanMin && dMin <= spanMax)
            {
                result.add(d);
            }
        }

        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------
    // Mutation methods
    // -------------------------------------------------------------------------

    /**
     * Appends a new discovery to the list and fires an {@link DiscoveryEvent.Type#ADDED} event.
     *
     * @param discovery the discovery to add; must not be null
     */
    public void add(Discovery discovery)
    {
        if(discovery == null)
        {
            throw new IllegalArgumentException("discovery must not be null");
        }

        mDiscoveries.add(discovery);
        mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.ADDED, discovery));
    }

    /**
     * Fires an {@link DiscoveryEvent.Type#UPDATED} event for a discovery that is already
     * in the list.  The row's properties have already been mutated by the caller; this
     * method notifies the Swing-side broadcaster.  The JavaFX table observes property
     * changes directly and does not need explicit notification here.
     *
     * @param discovery the discovery that was updated; must be in the list
     */
    public void update(Discovery discovery)
    {
        if(discovery == null)
        {
            return;
        }

        mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.UPDATED, discovery));
    }

    /**
     * Removes a discovery from the list and fires a {@link DiscoveryEvent.Type#REMOVED} event.
     *
     * @param discovery the discovery to remove
     */
    public void remove(Discovery discovery)
    {
        if(discovery == null)
        {
            return;
        }

        mDiscoveries.remove(discovery);
        mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.REMOVED, discovery));
    }

    /**
     * Removes all discoveries and fires a single {@link DiscoveryEvent.Type#CLEARED} event.
     */
    public void clear()
    {
        mDiscoveries.clear();
        mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.CLEARED, null));
    }

    /**
     * Removes all discoveries that are in a "terminal" state (IDENTIFIED, UNIDENTIFIED, ERROR,
     * KNOWN) — i.e., rows that have been fully processed and are no longer changing.
     * Discoveries in ENERGY_DETECTED or PROBING state are left in place.
     *
     * <p>Fires individual {@link DiscoveryEvent.Type#REMOVED} events for each removed row,
     * plus a final {@link DiscoveryEvent.Type#CLEARED} event to let listeners reset any
     * aggregate state.</p>
     */
    public void clearFinished()
    {
        List<Discovery> toRemove = new ArrayList<>();

        for(Discovery d : mDiscoveries)
        {
            DiscoveryState s = d.getState();
            if(s == DiscoveryState.IDENTIFIED || s == DiscoveryState.UNIDENTIFIED
                || s == DiscoveryState.ERROR || s == DiscoveryState.KNOWN)
            {
                toRemove.add(d);
            }
        }

        for(Discovery d : toRemove)
        {
            mDiscoveries.remove(d);
            mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.REMOVED, d));
        }

        mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.CLEARED, null));
    }
}
