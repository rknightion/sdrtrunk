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
package io.github.dsheirer.preference.discovery;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.discovery.IgnoreRange;
import io.github.dsheirer.preference.PreferenceType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for DiscoveryPreference defaults and persistence.
 *
 * <p>Each test gets a fresh {@link DiscoveryPreference} backed by a uniquely-named throwaway
 * node under {@code java.util.prefs.Preferences.userRoot()}.  The node is removed in
 * {@code @AfterEach} so developer preferences are never touched.</p>
 */
class DiscoveryPreferenceTest
{
    private Preferences mTestNode;
    private DiscoveryPreference mPreference;
    private List<PreferenceType> mUpdateEvents;

    @BeforeEach
    void setUp() throws Exception
    {
        // Each test gets its own isolated Preferences node — never touches real user prefs
        mTestNode = Preferences.userRoot().node("sdrtrunk-test-" + UUID.randomUUID());
        mUpdateEvents = new ArrayList<>();
        mPreference = new DiscoveryPreference(mUpdateEvents::add, mTestNode);
    }

    @AfterEach
    void tearDown() throws Exception
    {
        // Remove the throwaway node so nothing leaks between test runs
        mTestNode.removeNode();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a second {@link DiscoveryPreference} backed by the same test node, simulating
     * a fresh instance reading persisted values.
     */
    private DiscoveryPreference freshPreference()
    {
        return new DiscoveryPreference(t -> {}, mTestNode);
    }

    // -------------------------------------------------------------------------
    // Defaults
    // -------------------------------------------------------------------------

    @Test
    void default_surveyDwellIs3Seconds()
    {
        assertEquals(Duration.ofSeconds(3), mPreference.getSurveyDwell());
    }

    @Test
    void default_energyThresholdIs6dB()
    {
        assertEquals(6.0, mPreference.getEnergyThresholdDb(), 0.001);
    }

    @Test
    void default_maxConcurrentProbesIs2()
    {
        assertEquals(2, mPreference.getMaxConcurrentProbes());
    }

    @Test
    void default_maxConcurrentClassificationsIs1()
    {
        assertEquals(1, mPreference.getMaxConcurrentClassifications());
    }

    @Test
    void default_tunerHeadroomIs0()
    {
        assertEquals(0, mPreference.getTunerHeadroomChannels());
    }

    @Test
    void default_clickDefaultBandwidthIs12500()
    {
        assertEquals(12500, mPreference.getClickDefaultBandwidthHz());
    }

    @Test
    void default_keepListeningDurationIs30Seconds()
    {
        assertEquals(Duration.ofSeconds(30), mPreference.getKeepListeningDuration());
    }

    @Test
    void default_overlayDisplayIsIdentifiedOnly()
    {
        assertEquals(OverlayDisplay.IDENTIFIED_ONLY, mPreference.getOverlayDisplay());
    }

    @Test
    void default_ignoreListIsEmpty()
    {
        assertNotNull(mPreference.getIgnoreList());
        assertTrue(mPreference.getIgnoreList().isEmpty());
    }

    @Test
    void default_excludedDecodersIsEmpty()
    {
        assertNotNull(mPreference.getExcludedDecoders());
        assertTrue(mPreference.getExcludedDecoders().isEmpty());
    }

    @Test
    void default_defaultScanDecodersContainsAllPrimaries()
    {
        Set<DecoderType> scanDecoders = mPreference.getDefaultScanDecoders();
        assertNotNull(scanDecoders);
        assertFalse(scanDecoders.isEmpty());
        for(DecoderType primary : DecoderType.PRIMARY_DECODERS)
        {
            assertTrue(scanDecoders.contains(primary),
                "Default scan decoders should include primary decoder: " + primary);
        }
    }

    // -------------------------------------------------------------------------
    // Setter persistence — write then re-read via a fresh instance
    // -------------------------------------------------------------------------

    @Test
    void set_surveyDwellPersists()
    {
        mPreference.setSurveyDwell(Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(10), freshPreference().getSurveyDwell());
    }

    @Test
    void set_energyThresholdPersists()
    {
        mPreference.setEnergyThresholdDb(12.5);
        assertEquals(12.5, freshPreference().getEnergyThresholdDb(), 0.001);
    }

    @Test
    void set_maxConcurrentProbesPersists()
    {
        mPreference.setMaxConcurrentProbes(4);
        assertEquals(4, freshPreference().getMaxConcurrentProbes());
    }

    @Test
    void set_maxConcurrentClassificationsPersists()
    {
        mPreference.setMaxConcurrentClassifications(3);
        assertEquals(3, freshPreference().getMaxConcurrentClassifications());
    }

    @Test
    void set_clickDefaultBandwidthPersists()
    {
        mPreference.setClickDefaultBandwidthHz(25000);
        assertEquals(25000, freshPreference().getClickDefaultBandwidthHz());
    }

    @Test
    void set_keepListeningDurationPersists()
    {
        mPreference.setKeepListeningDuration(Duration.ofSeconds(60));
        assertEquals(Duration.ofSeconds(60), freshPreference().getKeepListeningDuration());
    }

    @Test
    void set_overlayDisplayPersists()
    {
        mPreference.setOverlayDisplay(OverlayDisplay.ALL);
        assertEquals(OverlayDisplay.ALL, freshPreference().getOverlayDisplay());
    }

    // -------------------------------------------------------------------------
    // Ignore list round-trip
    // -------------------------------------------------------------------------

    @Test
    void addIgnoreRange_appearsInList()
    {
        IgnoreRange range = IgnoreRange.of(154_000_000L, 155_000_000L, "test range");
        mPreference.addIgnoreRange(range);

        List<IgnoreRange> list = mPreference.getIgnoreList();
        assertEquals(1, list.size());
        assertEquals(154_000_000L, list.get(0).minHz());
        assertEquals(155_000_000L, list.get(0).maxHz());
    }

    @Test
    void removeIgnoreRange_removesFromList()
    {
        IgnoreRange a = IgnoreRange.of(100_000_000L, 101_000_000L);
        IgnoreRange b = IgnoreRange.of(200_000_000L, 201_000_000L);
        mPreference.addIgnoreRange(a);
        mPreference.addIgnoreRange(b);

        mPreference.removeIgnoreRange(a);

        List<IgnoreRange> list = mPreference.getIgnoreList();
        assertEquals(1, list.size());
        assertEquals(200_000_000L, list.get(0).minHz());
    }

    @Test
    void ignoreList_persistsAcrossInstances()
    {
        IgnoreRange r = IgnoreRange.of(160_000_000L, 161_000_000L, "persist me");
        mPreference.addIgnoreRange(r);

        List<IgnoreRange> loaded = freshPreference().getIgnoreList();
        assertEquals(1, loaded.size());
        assertEquals(160_000_000L, loaded.get(0).minHz());
        assertEquals(161_000_000L, loaded.get(0).maxHz());
        assertEquals("persist me", loaded.get(0).note());
    }

    @Test
    void ignoreList_noteWithSpecialChars_roundTrips()
    {
        // Notes containing pipe, newline, and quotes must survive JSON serialisation
        String tricky = "has|pipe\nand\nnewlines \"and quotes\"";
        IgnoreRange r = IgnoreRange.of(170_000_000L, 171_000_000L, tricky);
        mPreference.addIgnoreRange(r);

        List<IgnoreRange> loaded = freshPreference().getIgnoreList();
        assertEquals(1, loaded.size());
        assertEquals(tricky, loaded.get(0).note(),
            "Note with special characters must survive JSON round-trip");
        assertEquals(170_000_000L, loaded.get(0).minHz());
        assertEquals(171_000_000L, loaded.get(0).maxHz());
    }

    // -------------------------------------------------------------------------
    // Tuner headroom channels
    // -------------------------------------------------------------------------

    @Test
    void set_tunerHeadroomChannelsPersists()
    {
        mPreference.setTunerHeadroomChannels(2);
        assertEquals(2, freshPreference().getTunerHeadroomChannels());
    }

    // -------------------------------------------------------------------------
    // Preference update notifications
    // -------------------------------------------------------------------------

    @Test
    void setter_firesUpdateNotification()
    {
        mPreference.setEnergyThresholdDb(9.0);
        assertFalse(mUpdateEvents.isEmpty(), "setter should fire at least one preference update notification");
        assertTrue(mUpdateEvents.contains(PreferenceType.DISCOVERY),
            "fired event should be DISCOVERY type");
    }

    // -------------------------------------------------------------------------
    // probeWindow — per-decoder probe time window
    // -------------------------------------------------------------------------

    @Test
    void probeWindow_returnsPositiveDurationForAllPrimaries()
    {
        for(DecoderType dt : DecoderType.PRIMARY_DECODERS)
        {
            Duration window = mPreference.probeWindow(dt);
            assertNotNull(window, "probeWindow must not return null for " + dt);
            assertTrue(window.toMillis() > 0, "probeWindow must be positive for " + dt);
        }
    }

    @Test
    void probeWindow_p25IsLongerThanNbfm()
    {
        Duration p25 = mPreference.probeWindow(DecoderType.P25_PHASE1);
        Duration nbfm = mPreference.probeWindow(DecoderType.NBFM);
        // P25 framing is slower — its window should be at least as long as NBFM
        assertTrue(p25.compareTo(nbfm) >= 0,
            "P25_PHASE1 probe window should be >= NBFM probe window");
    }
}
