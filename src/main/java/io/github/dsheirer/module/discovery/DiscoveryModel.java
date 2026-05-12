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
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Holds the set of {@link Discovery} rows produced during a band scan and notifies
 * registered listeners of add / update / remove / clear events.
 *
 * <h3>Thread safety</h3>
 * <p>All public mutation methods ({@code add}, {@code update}, {@code remove}, {@code clear},
 * {@code clearFinished}) are safe to call from <em>any thread</em>.  When the JavaFX toolkit
 * is running they marshal {@link ObservableList} mutations onto the FX Application Thread via
 * {@code Platform.runLater(...)}, so {@link javafx.scene.control.TableView} bindings receive
 * change notifications on the correct thread.  When the FX toolkit is not running (e.g. in
 * headless unit tests) mutations are applied inline under the internal lock.</p>
 *
 * <p>The {@link Broadcaster}-based {@link DiscoveryEvent} stream is always fired on the thread
 * that ultimately performs the mutation (the FX thread when FX is up, the calling thread
 * otherwise).</p>
 *
 * <h3>Phase 4 contract</h3>
 * <p>With this implementation Phase 4 can call any {@code DiscoveryModel} method directly from
 * any thread — including the FX thread — without additional marshalling.  Binding a
 * {@link javafx.scene.control.TableView} to {@link #getDiscoveries()} is safe.</p>
 *
 * <h3>JavaFX observable list</h3>
 * <p>The internal list is a {@link ObservableList} so that a Phase-4 JavaFX table can bind to
 * it directly and pick up structural changes (add/remove) as well as in-place property
 * mutations (because {@link Discovery} exposes {@code javafx.beans.property} fields).</p>
 */
public class DiscoveryModel
{
    private final ObservableList<Discovery> mDiscoveries = FXCollections.observableArrayList();
    private final Broadcaster<DiscoveryEvent> mBroadcaster = new Broadcaster<>();

    /**
     * Guard for the raw list.  Every access to {@code mDiscoveries} (including reads in
     * {@code findOverlapping}) must be performed while holding this lock.  The FX-thread
     * marshal paths are exempt only because they will always run on a single thread.
     */
    private final Object mLock = new Object();

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
     * directly via this method — the list is updated on the FX thread so bindings
     * receive proper change notifications.  Non-UI callers that iterate the list
     * should take a snapshot: {@code new ArrayList<>(model.getDiscoveries())}.</p>
     *
     * @return unmodifiable view of the live observable list
     */
    public ObservableList<Discovery> getDiscoveries()
    {
        return FXCollections.unmodifiableObservableList(mDiscoveries);
    }

    /**
     * Returns a snapshot of the current list suitable for iteration on any thread.
     * Unlike {@link #getDiscoveries()} the snapshot does not observe future changes.
     *
     * @return unmodifiable snapshot; never null, may be empty
     */
    public List<Discovery> snapshot()
    {
        synchronized(mLock)
        {
            return Collections.unmodifiableList(new ArrayList<>(mDiscoveries));
        }
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

        synchronized(mLock)
        {
            for(Discovery d : mDiscoveries)
            {
                long dMin = d.getCenterFrequencyHz() - d.getBandwidthHz() / 2L;
                long dMax = d.getCenterFrequencyHz() + d.getBandwidthHz() / 2L;

                if(dMax >= spanMin && dMin <= spanMax)
                {
                    result.add(d);
                }
            }
        }

        return Collections.unmodifiableList(result);
    }

    // -------------------------------------------------------------------------
    // Mutation methods — all safe from any thread
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

        runMutation(() -> {
            mDiscoveries.add(discovery);
            mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.ADDED, discovery));
        });
    }

    /**
     * Fires an {@link DiscoveryEvent.Type#UPDATED} event for a discovery that is already
     * in the list.  The row's properties have already been mutated by the caller; this
     * method notifies the Swing-side broadcaster.  The JavaFX table observes property
     * changes directly via the discovery's {@code javafx.beans.property} fields and does
     * not need explicit notification here.
     *
     * @param discovery the discovery that was updated; must be in the list
     */
    public void update(Discovery discovery)
    {
        if(discovery == null)
        {
            return;
        }

        runMutation(() -> mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.UPDATED, discovery)));
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

        runMutation(() -> {
            mDiscoveries.remove(discovery);
            mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.REMOVED, discovery));
        });
    }

    /**
     * Removes all discoveries and fires a single {@link DiscoveryEvent.Type#CLEARED} event.
     */
    public void clear()
    {
        runMutation(() -> {
            mDiscoveries.clear();
            mBroadcaster.receive(new DiscoveryEvent(DiscoveryEvent.Type.CLEARED, null));
        });
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
        runMutation(() -> {
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
        });
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Runs a mutation action either inline (under {@link #mLock}) or marshalled to the
     * JavaFX Application Thread, depending on whether the FX toolkit is available.
     *
     * <ul>
     *   <li>If the FX toolkit is running and we are already on the FX thread — run inline
     *       (no marshalling needed; the FX thread is single-threaded for scene-graph work).</li>
     *   <li>If the FX toolkit is running and we are on a background thread — schedule with
     *       {@code Platform.runLater(...)} so the {@link ObservableList} is mutated on the
     *       FX thread.  The broadcaster fires on the FX thread as well.</li>
     *   <li>If the FX toolkit is not running (headless / test context) — {@code Platform.runLater}
     *       would throw {@code IllegalStateException: Toolkit not initialized}.  We detect this by
     *       catching that exception and falling back to running inline under {@link #mLock} on the
     *       calling thread.</li>
     * </ul>
     *
     * @param action the mutation to run; must not be null
     */
    private void runMutation(Runnable action)
    {
        if(Platform.isFxApplicationThread())
        {
            // Already on FX thread: acquire mLock briefly so that background readers
            // (snapshot / findOverlapping) cannot race a structural mutation.
            synchronized(mLock)
            {
                action.run();
            }
            return;
        }

        // Try to marshal to the FX thread.  If the toolkit is not initialised, runLater()
        // throws IllegalStateException — fall back to inline execution under mLock.
        try
        {
            // The runLater lambda must also acquire mLock when it fires on the FX thread,
            // to stay consistent with the rule above.
            Platform.runLater(() -> {
                synchronized(mLock)
                {
                    action.run();
                }
            });
        }
        catch(IllegalStateException e)
        {
            // FX toolkit not started (headless / test path) — run inline under lock
            synchronized(mLock)
            {
                action.run();
            }
        }
    }
}
