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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.discovery.IgnoreRange;
import io.github.dsheirer.preference.Preference;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.sample.Listener;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User preferences for signal discovery and click-to-tune.
 *
 * <p>Follows the existing {@link Preference} subclass pattern:
 * lazy-loaded fields backed by {@code java.util.prefs.Preferences},
 * setters notify via {@link #notifyPreferenceUpdated()}.</p>
 *
 * <h3>Ignore-list serialisation</h3>
 * The ignore list is serialised as a JSON array of objects, each with fields
 * {@code minHz}, {@code maxHz}, {@code note} (string, may be null), and
 * {@code addedAtEpochMs} (long, epoch milliseconds).  Notes containing {@code |}
 * or newlines are fully supported.
 *
 * <p>Note: {@code Preferences.put} silently truncates values past ~8 192 chars.
 * If the serialised list exceeds that limit a warning is logged; existing entries
 * are preserved in memory but only the truncated bytes are stored on disk.
 * This is an acceptable limitation for Phase 1.</p>
 */
public class DiscoveryPreference extends Preference
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryPreference.class);

    /** Maximum safe length for a single {@code Preferences} string value. */
    private static final int PREFS_MAX_CHARS = 8_000;

    // -------------------------------------------------------------------------
    // Preference keys
    // -------------------------------------------------------------------------

    private static final String KEY_SURVEY_DWELL_SECONDS     = "discovery.survey.dwell.seconds";
    private static final String KEY_ENERGY_THRESHOLD_DB      = "discovery.energy.threshold.db";
    private static final String KEY_MAX_CONCURRENT_PROBES    = "discovery.max.concurrent.probes";
    private static final String KEY_MAX_CONCURRENT_CLASS     = "discovery.max.concurrent.classifications";
    private static final String KEY_TUNER_HEADROOM_CHANNELS  = "discovery.tuner.headroom.channels";
    private static final String KEY_CLICK_DEFAULT_BW_HZ      = "discovery.click.default.bandwidth.hz";
    private static final String KEY_KEEP_LISTENING_SECONDS   = "discovery.keep.listening.seconds";
    private static final String KEY_OVERLAY_DISPLAY          = "discovery.overlay.display";
    private static final String KEY_IGNORE_LIST              = "discovery.ignore.list";

    // -------------------------------------------------------------------------
    // Defaults (package-visible for test verification)
    // -------------------------------------------------------------------------

    static final int     DEFAULT_SURVEY_DWELL_SECONDS      = 3;
    static final double  DEFAULT_ENERGY_THRESHOLD_DB       = 6.0;
    static final int     DEFAULT_MAX_CONCURRENT_PROBES     = 2;
    static final int     DEFAULT_MAX_CONCURRENT_CLASS      = 1;
    static final int     DEFAULT_TUNER_HEADROOM_CHANNELS   = 0;
    static final int     DEFAULT_CLICK_DEFAULT_BW_HZ       = 12_500;
    static final int     DEFAULT_KEEP_LISTENING_SECONDS    = 30;
    static final String  DEFAULT_OVERLAY_DISPLAY           = OverlayDisplay.IDENTIFIED_ONLY.name();

    /**
     * Per-decoder probe windows (milliseconds).  These represent the maximum time
     * the classifier will wait for each protocol to lock.
     */
    private static final java.util.Map<DecoderType, Long> PROBE_WINDOWS_MS;

    static
    {
        java.util.Map<DecoderType, Long> map = new java.util.EnumMap<>(DecoderType.class);
        // P25 requires several super-frames to confirm a control channel — allow more time
        map.put(DecoderType.P25_PHASE1,  5_000L);
        map.put(DecoderType.P25_PHASE2,  5_000L);
        // DMR super-frame is 120 ms, allow several
        map.put(DecoderType.DMR,          3_000L);
        // Simple analog/FM detects quickly
        map.put(DecoderType.NBFM,         2_000L);
        map.put(DecoderType.AM,           2_000L);
        // Others: conservative default
        PROBE_WINDOWS_MS = Collections.unmodifiableMap(map);
    }

    private static final long DEFAULT_PROBE_WINDOW_MS = 4_000L;

    // -------------------------------------------------------------------------
    // Jackson mapper — plain JSON, no special modules needed (epoch-ms longs)
    // -------------------------------------------------------------------------

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    // -------------------------------------------------------------------------
    // Lazy-cached fields (null = not yet loaded)
    // -------------------------------------------------------------------------

    private Preferences mPreferences = Preferences.userNodeForPackage(DiscoveryPreference.class);

    private Integer  mSurveyDwellSeconds;
    private Double   mEnergyThresholdDb;
    private Integer  mMaxConcurrentProbes;
    private Integer  mMaxConcurrentClassifications;
    private Integer  mTunerHeadroomChannels;
    private Integer  mClickDefaultBandwidthHz;
    private Integer  mKeepListeningSeconds;
    private OverlayDisplay mOverlayDisplay;
    private List<IgnoreRange> mIgnoreList;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs this preference with an update listener.
     *
     * @param updateListener receives {@link PreferenceType#DISCOVERY} whenever a setting changes
     */
    public DiscoveryPreference(Listener<PreferenceType> updateListener)
    {
        super(updateListener);
    }

    // -------------------------------------------------------------------------
    // PreferenceType
    // -------------------------------------------------------------------------

    @Override
    public PreferenceType getPreferenceType()
    {
        return PreferenceType.DISCOVERY;
    }

    // -------------------------------------------------------------------------
    // Survey dwell
    // -------------------------------------------------------------------------

    /**
     * How long the spectral survey dwells on each frequency step to measure power.
     *
     * @return dwell duration (default 3 seconds)
     */
    public Duration getSurveyDwell()
    {
        if(mSurveyDwellSeconds == null)
        {
            mSurveyDwellSeconds = mPreferences.getInt(KEY_SURVEY_DWELL_SECONDS, DEFAULT_SURVEY_DWELL_SECONDS);
        }

        return Duration.ofSeconds(mSurveyDwellSeconds);
    }

    /**
     * Sets the survey dwell duration.
     *
     * @param dwell must be positive
     */
    public void setSurveyDwell(Duration dwell)
    {
        if(dwell == null || dwell.isNegative() || dwell.isZero())
        {
            throw new IllegalArgumentException("survey dwell must be positive");
        }

        mSurveyDwellSeconds = (int) dwell.getSeconds();
        mPreferences.putInt(KEY_SURVEY_DWELL_SECONDS, mSurveyDwellSeconds);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Energy threshold
    // -------------------------------------------------------------------------

    /**
     * SNR threshold (dB) above which a frequency is considered to have a signal worth probing.
     * This is a floor-relative value: the classifier returns NO_SIGNAL if the peak reading
     * is not at least this many dB above the estimated noise floor.
     *
     * @return threshold in dB (default 6.0)
     */
    public double getEnergyThresholdDb()
    {
        if(mEnergyThresholdDb == null)
        {
            mEnergyThresholdDb = mPreferences.getDouble(KEY_ENERGY_THRESHOLD_DB, DEFAULT_ENERGY_THRESHOLD_DB);
        }

        return mEnergyThresholdDb;
    }

    /**
     * Sets the energy threshold.
     *
     * @param thresholdDb threshold in dB
     */
    public void setEnergyThresholdDb(double thresholdDb)
    {
        mEnergyThresholdDb = thresholdDb;
        mPreferences.putDouble(KEY_ENERGY_THRESHOLD_DB, thresholdDb);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Max concurrent probes
    // -------------------------------------------------------------------------

    /**
     * Maximum number of probe chains that may run simultaneously on a single
     * classification attempt.
     *
     * @return max concurrent probes (default 2)
     */
    public int getMaxConcurrentProbes()
    {
        if(mMaxConcurrentProbes == null)
        {
            mMaxConcurrentProbes = mPreferences.getInt(KEY_MAX_CONCURRENT_PROBES, DEFAULT_MAX_CONCURRENT_PROBES);
        }

        return mMaxConcurrentProbes;
    }

    /**
     * Sets the max concurrent probes.
     *
     * @param max must be at least 1
     */
    public void setMaxConcurrentProbes(int max)
    {
        if(max < 1)
        {
            throw new IllegalArgumentException("max concurrent probes must be at least 1");
        }

        mMaxConcurrentProbes = max;
        mPreferences.putInt(KEY_MAX_CONCURRENT_PROBES, max);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Max concurrent classifications
    // -------------------------------------------------------------------------

    /**
     * Maximum number of classification operations ({@code classify()} calls) that may
     * be running at once in the executor.
     *
     * @return max concurrent classifications (default 1)
     */
    public int getMaxConcurrentClassifications()
    {
        if(mMaxConcurrentClassifications == null)
        {
            mMaxConcurrentClassifications = mPreferences.getInt(KEY_MAX_CONCURRENT_CLASS, DEFAULT_MAX_CONCURRENT_CLASS);
        }

        return mMaxConcurrentClassifications;
    }

    /**
     * Sets the max concurrent classifications.
     *
     * @param max must be at least 1
     */
    public void setMaxConcurrentClassifications(int max)
    {
        if(max < 1)
        {
            throw new IllegalArgumentException("max concurrent classifications must be at least 1");
        }

        mMaxConcurrentClassifications = max;
        mPreferences.putInt(KEY_MAX_CONCURRENT_CLASS, max);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Tuner headroom channels
    // -------------------------------------------------------------------------

    /**
     * Number of tuner channels to keep free (headroom) for the operator.
     * 0 = no restriction; positive values reserve that many slots before
     * allowing a classification to acquire a source.
     *
     * <p>Note: since {@code TunerManager.getSource()} returning {@code null} already
     * signals "no capacity", this setting is advisory; enforcement is deferred to
     * a future scheduler layer.  It is persisted so callers can read it.</p>
     *
     * @return number of channels to keep free (default 0)
     */
    public int getTunerHeadroomChannels()
    {
        if(mTunerHeadroomChannels == null)
        {
            mTunerHeadroomChannels = mPreferences.getInt(KEY_TUNER_HEADROOM_CHANNELS, DEFAULT_TUNER_HEADROOM_CHANNELS);
        }

        return mTunerHeadroomChannels;
    }

    /**
     * Sets the tuner headroom channel count.
     *
     * @param channels must not be negative
     */
    public void setTunerHeadroomChannels(int channels)
    {
        if(channels < 0)
        {
            throw new IllegalArgumentException("tuner headroom must not be negative");
        }

        mTunerHeadroomChannels = channels;
        mPreferences.putInt(KEY_TUNER_HEADROOM_CHANNELS, channels);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Click default bandwidth
    // -------------------------------------------------------------------------

    /**
     * Default assumed signal bandwidth (Hz) when the user double-clicks on the spectrum
     * (no drag to indicate width).
     *
     * @return bandwidth in Hz (default 12 500)
     */
    public int getClickDefaultBandwidthHz()
    {
        if(mClickDefaultBandwidthHz == null)
        {
            mClickDefaultBandwidthHz = mPreferences.getInt(KEY_CLICK_DEFAULT_BW_HZ, DEFAULT_CLICK_DEFAULT_BW_HZ);
        }

        return mClickDefaultBandwidthHz;
    }

    /**
     * Sets the click default bandwidth.
     *
     * @param bandwidthHz must be positive
     */
    public void setClickDefaultBandwidthHz(int bandwidthHz)
    {
        if(bandwidthHz <= 0)
        {
            throw new IllegalArgumentException("click default bandwidth must be positive");
        }

        mClickDefaultBandwidthHz = bandwidthHz;
        mPreferences.putInt(KEY_CLICK_DEFAULT_BW_HZ, bandwidthHz);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Keep-listening duration
    // -------------------------------------------------------------------------

    /**
     * How long the classifier waits after the initial probe window when the user
     * explicitly chooses "keep listening".
     *
     * @return duration (default 30 seconds)
     */
    public Duration getKeepListeningDuration()
    {
        if(mKeepListeningSeconds == null)
        {
            mKeepListeningSeconds = mPreferences.getInt(KEY_KEEP_LISTENING_SECONDS, DEFAULT_KEEP_LISTENING_SECONDS);
        }

        return Duration.ofSeconds(mKeepListeningSeconds);
    }

    /**
     * Sets the keep-listening duration.
     *
     * @param duration must be positive
     */
    public void setKeepListeningDuration(Duration duration)
    {
        if(duration == null || duration.isNegative() || duration.isZero())
        {
            throw new IllegalArgumentException("keep-listening duration must be positive");
        }

        mKeepListeningSeconds = (int) duration.getSeconds();
        mPreferences.putInt(KEY_KEEP_LISTENING_SECONDS, mKeepListeningSeconds);
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Overlay display mode
    // -------------------------------------------------------------------------

    /**
     * Controls which discovery results are painted on the spectral-display overlay.
     *
     * @return display mode (default {@link OverlayDisplay#IDENTIFIED_ONLY})
     */
    public OverlayDisplay getOverlayDisplay()
    {
        if(mOverlayDisplay == null)
        {
            String name = mPreferences.get(KEY_OVERLAY_DISPLAY, DEFAULT_OVERLAY_DISPLAY);

            try
            {
                mOverlayDisplay = OverlayDisplay.valueOf(name);
            }
            catch(Exception e)
            {
                mLog.warn("Unknown overlay display preference '{}', using default", name);
                mOverlayDisplay = OverlayDisplay.IDENTIFIED_ONLY;
            }
        }

        return mOverlayDisplay;
    }

    /**
     * Sets the overlay display mode.
     *
     * @param mode must not be null
     */
    public void setOverlayDisplay(OverlayDisplay mode)
    {
        if(mode == null)
        {
            throw new IllegalArgumentException("overlay display mode must not be null");
        }

        mOverlayDisplay = mode;
        mPreferences.put(KEY_OVERLAY_DISPLAY, mode.name());
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Excluded decoders
    // -------------------------------------------------------------------------

    /**
     * Returns the set of decoder types the user has explicitly excluded from all
     * discovery probing.  Empty by default.
     *
     * @return unmodifiable set of excluded decoders
     */
    public Set<DecoderType> getExcludedDecoders()
    {
        // Phase 1 v1: no UI yet; always returns empty set.
        // Persisted exclusions will be added in Phase 4's DiscoveryPreferenceEditor.
        return Collections.emptySet();
    }

    // -------------------------------------------------------------------------
    // Default scan decoders
    // -------------------------------------------------------------------------

    /**
     * The set of decoder types to try when running a band scan.
     * Defaults to all {@link DecoderType#PRIMARY_DECODERS}.
     *
     * @return mutable copy of the preferred set
     */
    public Set<DecoderType> getDefaultScanDecoders()
    {
        // Phase 1 v1: always returns all primaries; persisted customisation in Phase 4.
        return EnumSet.copyOf(DecoderType.PRIMARY_DECODERS);
    }

    // -------------------------------------------------------------------------
    // Ignore list
    // -------------------------------------------------------------------------

    /**
     * Returns the persisted list of frequency ranges to ignore during discovery.
     *
     * @return live (mutable) list; modifications are not automatically persisted — call
     *         {@link #addIgnoreRange(IgnoreRange)} or {@link #removeIgnoreRange(IgnoreRange)}
     */
    public List<IgnoreRange> getIgnoreList()
    {
        if(mIgnoreList == null)
        {
            mIgnoreList = loadIgnoreList();
        }

        return mIgnoreList;
    }

    /**
     * Adds a range to the ignore list and persists the updated list.
     *
     * @param range range to add; must not be null
     */
    public void addIgnoreRange(IgnoreRange range)
    {
        if(range == null)
        {
            throw new IllegalArgumentException("ignore range must not be null");
        }

        getIgnoreList().add(range);
        saveIgnoreList();
        notifyPreferenceUpdated();
    }

    /**
     * Removes a range from the ignore list (by value equality) and persists the updated list.
     *
     * @param range range to remove
     */
    public void removeIgnoreRange(IgnoreRange range)
    {
        getIgnoreList().remove(range);
        saveIgnoreList();
        notifyPreferenceUpdated();
    }

    // -------------------------------------------------------------------------
    // Per-decoder probe window
    // -------------------------------------------------------------------------

    /**
     * Returns the maximum time the classifier will wait for the given decoder type to lock.
     *
     * @param decoderType the decoder being probed
     * @return probe window duration
     */
    public Duration probeWindow(DecoderType decoderType)
    {
        long ms = PROBE_WINDOWS_MS.getOrDefault(decoderType, DEFAULT_PROBE_WINDOW_MS);
        return Duration.ofMillis(ms);
    }

    // -------------------------------------------------------------------------
    // Ignore-list JSON serialisation helpers
    // -------------------------------------------------------------------------

    /**
     * JSON DTO for one ignore range entry.  Uses plain long fields to avoid requiring
     * a Jackson JavaTimeModule (Instant is stored as epoch-milliseconds).
     */
    static class IgnoreRangeDto
    {
        public long minHz;
        public long maxHz;
        public String note;
        public long addedAtEpochMs;

        /** No-arg constructor for Jackson deserialisation. */
        public IgnoreRangeDto() {}

        static IgnoreRangeDto from(IgnoreRange r)
        {
            IgnoreRangeDto dto = new IgnoreRangeDto();
            dto.minHz = r.minHz();
            dto.maxHz = r.maxHz();
            dto.note  = r.note();
            dto.addedAtEpochMs = r.addedAt().toEpochMilli();
            return dto;
        }

        IgnoreRange toIgnoreRange()
        {
            return new IgnoreRange(minHz, maxHz, note, Instant.ofEpochMilli(addedAtEpochMs));
        }
    }

    private List<IgnoreRange> loadIgnoreList()
    {
        List<IgnoreRange> list = new ArrayList<>();
        String raw = mPreferences.get(KEY_IGNORE_LIST, "");

        if(raw == null || raw.isBlank())
        {
            return list;
        }

        try
        {
            List<IgnoreRangeDto> dtos = JSON_MAPPER.readValue(raw, new TypeReference<List<IgnoreRangeDto>>(){});

            for(IgnoreRangeDto dto : dtos)
            {
                try
                {
                    list.add(dto.toIgnoreRange());
                }
                catch(Exception e)
                {
                    mLog.warn("Skipping invalid ignore-range entry: {}", e.getMessage());
                }
            }
        }
        catch(Exception e)
        {
            mLog.warn("Could not parse ignore-list JSON: {}", e.getMessage());
        }

        return list;
    }

    private void saveIgnoreList()
    {
        if(mIgnoreList == null || mIgnoreList.isEmpty())
        {
            mPreferences.remove(KEY_IGNORE_LIST);
            return;
        }

        try
        {
            List<IgnoreRangeDto> dtos = new ArrayList<>(mIgnoreList.size());

            for(IgnoreRange r : mIgnoreList)
            {
                dtos.add(IgnoreRangeDto.from(r));
            }

            String json = JSON_MAPPER.writeValueAsString(dtos);

            if(json.length() > PREFS_MAX_CHARS)
            {
                mLog.warn("Discovery ignore list exceeds Preferences storage limit ({} chars > {}); "
                    + "list is stored in memory but may be truncated on disk.",
                    json.length(), PREFS_MAX_CHARS);
            }

            mPreferences.put(KEY_IGNORE_LIST, json);
        }
        catch(Exception e)
        {
            mLog.error("Could not serialise ignore list to JSON: {}", e.getMessage());
        }
    }
}
