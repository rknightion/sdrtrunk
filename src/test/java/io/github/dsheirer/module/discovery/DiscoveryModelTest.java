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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DiscoveryModel}: event firing, list mutations, and overlap math.
 */
class DiscoveryModelTest
{
    private DiscoveryModel mModel;
    private List<DiscoveryEvent> mEvents;

    @BeforeEach
    void setUp()
    {
        mModel = new DiscoveryModel();
        mEvents = new ArrayList<>();
        mModel.addListener(mEvents::add);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Discovery makeDiscovery(long centerHz)
    {
        return new Discovery(centerHz, 12_500, -70.0, 10.0, Instant.now());
    }

    private static Discovery makeDiscovery(long centerHz, int bwHz)
    {
        return new Discovery(centerHz, bwHz, -70.0, 10.0, Instant.now());
    }

    // -------------------------------------------------------------------------
    // add() tests
    // -------------------------------------------------------------------------

    @Test
    void addFiresAddedEvent()
    {
        Discovery d = makeDiscovery(154_920_000L);
        mModel.add(d);

        assertEquals(1, mEvents.size());
        assertEquals(DiscoveryEvent.Type.ADDED, mEvents.get(0).type());
        assertEquals(d, mEvents.get(0).discovery());
    }

    @Test
    void addAppendsToObservableList()
    {
        Discovery d1 = makeDiscovery(154_920_000L);
        Discovery d2 = makeDiscovery(162_000_000L);
        mModel.add(d1);
        mModel.add(d2);

        assertEquals(2, mModel.getDiscoveries().size());
        assertTrue(mModel.getDiscoveries().contains(d1));
        assertTrue(mModel.getDiscoveries().contains(d2));
    }

    // -------------------------------------------------------------------------
    // update() tests
    // -------------------------------------------------------------------------

    @Test
    void updateFiresUpdatedEvent()
    {
        Discovery d = makeDiscovery(154_920_000L);
        mModel.add(d);
        mEvents.clear();

        d.setState(DiscoveryState.IDENTIFIED);
        mModel.update(d);

        assertEquals(1, mEvents.size());
        assertEquals(DiscoveryEvent.Type.UPDATED, mEvents.get(0).type());
        assertEquals(d, mEvents.get(0).discovery());
    }

    @Test
    void updateDoesNotMutateListStructure()
    {
        Discovery d = makeDiscovery(154_920_000L);
        mModel.add(d);
        int sizeBefore = mModel.getDiscoveries().size();

        mModel.update(d);

        assertEquals(sizeBefore, mModel.getDiscoveries().size());
    }

    @Test
    void updateWithNullIsNoOp()
    {
        mModel.add(makeDiscovery(1_000_000L));
        mEvents.clear();

        mModel.update(null); // should not throw

        assertEquals(0, mEvents.size());
    }

    // -------------------------------------------------------------------------
    // remove() tests
    // -------------------------------------------------------------------------

    @Test
    void removeFiresRemovedEvent()
    {
        Discovery d = makeDiscovery(154_920_000L);
        mModel.add(d);
        mEvents.clear();

        mModel.remove(d);

        assertEquals(1, mEvents.size());
        assertEquals(DiscoveryEvent.Type.REMOVED, mEvents.get(0).type());
        assertEquals(d, mEvents.get(0).discovery());
    }

    @Test
    void removeActuallyRemovesFromList()
    {
        Discovery d = makeDiscovery(154_920_000L);
        mModel.add(d);

        mModel.remove(d);

        assertFalse(mModel.getDiscoveries().contains(d));
        assertEquals(0, mModel.getDiscoveries().size());
    }

    @Test
    void removeWithNullIsNoOp()
    {
        mModel.add(makeDiscovery(1_000_000L));
        mEvents.clear();

        mModel.remove(null); // should not throw

        assertEquals(0, mEvents.size());
    }

    // -------------------------------------------------------------------------
    // clear() tests
    // -------------------------------------------------------------------------

    @Test
    void clearFiresClearedEvent()
    {
        mModel.add(makeDiscovery(100_000_000L));
        mModel.add(makeDiscovery(200_000_000L));
        mEvents.clear();

        mModel.clear();

        assertEquals(1, mEvents.size());
        assertEquals(DiscoveryEvent.Type.CLEARED, mEvents.get(0).type());
        assertNull(mEvents.get(0).discovery());
    }

    @Test
    void clearEmptiesList()
    {
        mModel.add(makeDiscovery(100_000_000L));
        mModel.add(makeDiscovery(200_000_000L));

        mModel.clear();

        assertEquals(0, mModel.getDiscoveries().size());
    }

    @Test
    void clearOnEmptyModelDoesNotThrow()
    {
        mModel.clear(); // should fire CLEARED on empty model
        assertEquals(1, mEvents.size());
        assertEquals(DiscoveryEvent.Type.CLEARED, mEvents.get(0).type());
    }

    // -------------------------------------------------------------------------
    // clearFinished() tests
    // -------------------------------------------------------------------------

    @Test
    void clearFinishedRemovesTerminalStates()
    {
        Discovery identified = makeDiscovery(100_000_000L);
        identified.setState(DiscoveryState.IDENTIFIED);

        Discovery unidentified = makeDiscovery(200_000_000L);
        unidentified.setState(DiscoveryState.UNIDENTIFIED);

        Discovery errored = makeDiscovery(300_000_000L);
        errored.setState(DiscoveryState.ERROR);

        Discovery known = makeDiscovery(400_000_000L);
        known.setState(DiscoveryState.KNOWN);

        Discovery probing = makeDiscovery(500_000_000L);
        probing.setState(DiscoveryState.PROBING);

        Discovery energyDetected = makeDiscovery(600_000_000L);
        energyDetected.setState(DiscoveryState.ENERGY_DETECTED);

        mModel.add(identified);
        mModel.add(unidentified);
        mModel.add(errored);
        mModel.add(known);
        mModel.add(probing);
        mModel.add(energyDetected);
        mEvents.clear();

        mModel.clearFinished();

        // List should retain only PROBING and ENERGY_DETECTED
        assertEquals(2, mModel.getDiscoveries().size());
        assertTrue(mModel.getDiscoveries().contains(probing));
        assertTrue(mModel.getDiscoveries().contains(energyDetected));
        assertFalse(mModel.getDiscoveries().contains(identified));
        assertFalse(mModel.getDiscoveries().contains(unidentified));
        assertFalse(mModel.getDiscoveries().contains(errored));
        assertFalse(mModel.getDiscoveries().contains(known));
    }

    @Test
    void clearFinishedFiresRemovedAndFinalClearedEvents()
    {
        Discovery d1 = makeDiscovery(100_000_000L);
        d1.setState(DiscoveryState.IDENTIFIED);
        Discovery d2 = makeDiscovery(200_000_000L);
        d2.setState(DiscoveryState.UNIDENTIFIED);
        mModel.add(d1);
        mModel.add(d2);
        mEvents.clear();

        mModel.clearFinished();

        // Should have 2 REMOVED events + 1 CLEARED event
        long removedCount = mEvents.stream().filter(e -> e.type() == DiscoveryEvent.Type.REMOVED).count();
        long clearedCount = mEvents.stream().filter(e -> e.type() == DiscoveryEvent.Type.CLEARED).count();
        assertEquals(2, removedCount);
        assertEquals(1, clearedCount);
    }

    // -------------------------------------------------------------------------
    // findOverlapping() boundary tests
    // -------------------------------------------------------------------------

    /**
     * Helper: build a discovery at {@code centerHz} with ±halfBw each side.
     * Returns: [center - halfBw, center + halfBw].
     */
    private Discovery makeWideDiscovery(long centerHz, int totalBwHz)
    {
        return new Discovery(centerHz, totalBwHz, -70.0, 10.0, Instant.now());
    }

    @Test
    void findOverlappingReturnsClearOverlap()
    {
        // Discovery at 100 MHz ± 6250 Hz → [99.993750 MHz, 100.006250 MHz]
        Discovery d = makeWideDiscovery(100_000_000L, 12_500);
        mModel.add(d);

        // Query span centered at 100 MHz ± 6250 Hz — same span, should overlap
        List<Discovery> found = mModel.findOverlapping(100_000_000L, 12_500);
        assertEquals(1, found.size());
        assertTrue(found.contains(d));
    }

    @Test
    void findOverlappingMissesNonOverlap()
    {
        // Discovery at 100 MHz, bw=10 kHz → [99.995 MHz, 100.005 MHz]
        Discovery d = makeWideDiscovery(100_000_000L, 10_000);
        mModel.add(d);

        // Query span at 101 MHz, bw=10 kHz → [100.995 MHz, 101.005 MHz]
        List<Discovery> found = mModel.findOverlapping(101_000_000L, 10_000);
        assertEquals(0, found.size());
    }

    @Test
    void findOverlappingTouchingEdgesIsOverlap()
    {
        // Discovery at 100 MHz, bw=10 kHz → upper edge = 100.005 MHz
        Discovery d = makeWideDiscovery(100_000_000L, 10_000);
        mModel.add(d);

        // Query span: [100.005 MHz, 100.015 MHz] — lower edge touches upper edge
        // center = 100.010 MHz, width = 10 kHz → [100.005, 100.015]
        List<Discovery> found = mModel.findOverlapping(100_010_000L, 10_000);
        assertEquals(1, found.size());
    }

    @Test
    void findOverlappingAdjacentButNotOverlapping()
    {
        // Discovery at 100 MHz, bw=10 kHz → upper edge = 100.005 MHz
        Discovery d = makeWideDiscovery(100_000_000L, 10_000);
        mModel.add(d);

        // Query span lower edge starts at 100.005001 MHz (just beyond the upper edge)
        // center = 100.010001 MHz, width = 10 kHz → [100.005001, 100.015001]
        List<Discovery> found = mModel.findOverlapping(100_010_001L, 10_000);
        // lower edge of span = 100_010_001 - 5000 = 100_005_001
        // upper edge of discovery = 100_000_000 + 5000 = 100_005_000
        // 100_005_000 < 100_005_001 so should NOT overlap
        assertEquals(0, found.size());
    }

    @Test
    void findOverlappingMultipleResults()
    {
        Discovery d1 = makeWideDiscovery(100_000_000L, 12_500);
        Discovery d2 = makeWideDiscovery(100_010_000L, 12_500);  // partially overlaps d1 at query span
        Discovery d3 = makeWideDiscovery(200_000_000L, 12_500);  // far away
        mModel.add(d1);
        mModel.add(d2);
        mModel.add(d3);

        // Query span at 100.005 MHz, width 20 kHz → [99.995, 100.015]
        List<Discovery> found = mModel.findOverlapping(100_005_000L, 20_000);
        assertEquals(2, found.size());
        assertTrue(found.contains(d1));
        assertTrue(found.contains(d2));
        assertFalse(found.contains(d3));
    }

    @Test
    void findOverlappingOnEmptyModelReturnsEmpty()
    {
        List<Discovery> found = mModel.findOverlapping(100_000_000L, 12_500);
        assertEquals(0, found.size());
    }

    // -------------------------------------------------------------------------
    // getDiscoveries() immutability
    // -------------------------------------------------------------------------

    @Test
    void getDiscoveriesReturnsUnmodifiableView()
    {
        Discovery d = makeDiscovery(100_000_000L);
        mModel.add(d);

        // The returned list should be unmodifiable
        try
        {
            mModel.getDiscoveries().add(makeDiscovery(200_000_000L));
        }
        catch(UnsupportedOperationException e)
        {
            // Expected — the list is unmodifiable
            return;
        }

        // If no exception was thrown, fail the test
        throw new AssertionError("Expected UnsupportedOperationException from unmodifiable list");
    }
}
