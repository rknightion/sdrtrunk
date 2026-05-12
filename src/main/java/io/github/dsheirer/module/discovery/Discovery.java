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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderType;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

/**
 * Observable model object representing one discovered signal found during a band scan.
 *
 * <p>All {@code JavaFX Simple*Property} fields are observable so that a future Phase-4
 * table can bind directly.  Plain getters and {@code *Property()} accessors are provided
 * for every field following the convention used by {@link Channel}.</p>
 *
 * <h3>Threading</h3>
 * <p>All property mutations must be called on the JavaFX Application Thread.
 * {@code BandScanController} wraps every setter call via {@link io.github.dsheirer.util.FxThreads#run},
 * which marshals to the FX thread when the toolkit is running, or runs inline in headless tests.
 * This ensures that {@code DiscoveryEditor} table bindings — which run on the FX thread — are
 * always updated from the correct thread.</p>
 */
public class Discovery
{
    private final long mCenterFrequencyHz;
    private final int mBandwidthHz;
    private final double mPowerDb;
    private final double mSnrDb;

    private final ObjectProperty<DiscoveryState> mState = new SimpleObjectProperty<>(DiscoveryState.ENERGY_DETECTED);
    private final ObjectProperty<DecoderType> mDetectedDecoder = new SimpleObjectProperty<>(null);
    private final ObjectProperty<SignalKind> mKind = new SimpleObjectProperty<>(SignalKind.UNKNOWN);
    private final IntegerProperty mConfidence = new SimpleIntegerProperty(0);
    private final ObjectProperty<Instant> mFirstSeen = new SimpleObjectProperty<>();
    private final ObjectProperty<Instant> mLastSeen = new SimpleObjectProperty<>();
    private final ObjectProperty<Channel> mCreatedChannel = new SimpleObjectProperty<>(null);
    private final BooleanProperty mWatched = new SimpleBooleanProperty(false);
    private Map<String, String> mMetadata = new HashMap<>();

    /**
     * Monotonically-increasing counter incremented each time the metadata map is replaced.
     * JavaFX columns can bind to this property to re-render Notes cells when metadata changes.
     */
    private final IntegerProperty mMetadataVersion = new SimpleIntegerProperty(0);

    /**
     * Constructs a Discovery from an energy peak.
     *
     * @param peak      the energy peak that triggered this discovery row
     * @param firstSeen the instant the peak was first detected
     */
    public Discovery(EnergyPeak peak, Instant firstSeen)
    {
        mCenterFrequencyHz = peak.centerFrequencyHz();
        mBandwidthHz = peak.occupiedBandwidthHz();
        mPowerDb = peak.powerDb();
        mSnrDb = peak.snrDb();
        mFirstSeen.set(firstSeen);
        mLastSeen.set(firstSeen);
    }

    /**
     * Constructs a Discovery directly from field values (for testing or manual construction).
     *
     * @param centerFrequencyHz center frequency in Hz
     * @param bandwidthHz       occupied bandwidth in Hz
     * @param powerDb           peak power in dB
     * @param snrDb             signal-to-noise ratio in dB
     * @param firstSeen         instant first seen
     */
    public Discovery(long centerFrequencyHz, int bandwidthHz, double powerDb, double snrDb, Instant firstSeen)
    {
        mCenterFrequencyHz = centerFrequencyHz;
        mBandwidthHz = bandwidthHz;
        mPowerDb = powerDb;
        mSnrDb = snrDb;
        mFirstSeen.set(firstSeen);
        mLastSeen.set(firstSeen);
    }

    // -------------------------------------------------------------------------
    // Immutable scalar fields
    // -------------------------------------------------------------------------

    /**
     * Center frequency of the discovered signal, in Hz.
     *
     * @return center frequency in Hz
     */
    public long getCenterFrequencyHz()
    {
        return mCenterFrequencyHz;
    }

    /**
     * Occupied bandwidth of the discovered signal, in Hz.
     *
     * @return bandwidth in Hz
     */
    public int getBandwidthHz()
    {
        return mBandwidthHz;
    }

    /**
     * Peak power of the discovered signal, in dB relative to full-scale.
     *
     * @return power in dB
     */
    public double getPowerDb()
    {
        return mPowerDb;
    }

    /**
     * Estimated signal-to-noise ratio, in dB.
     *
     * @return SNR in dB
     */
    public double getSnrDb()
    {
        return mSnrDb;
    }

    // -------------------------------------------------------------------------
    // Observable property: state
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the discovery lifecycle state.
     *
     * @return state property
     */
    public ObjectProperty<DiscoveryState> stateProperty()
    {
        return mState;
    }

    /**
     * Current lifecycle state.
     *
     * @return current state
     */
    public DiscoveryState getState()
    {
        return mState.get();
    }

    /**
     * Sets the lifecycle state.
     *
     * @param state new state; must not be null
     */
    public void setState(DiscoveryState state)
    {
        mState.set(state);
    }

    // -------------------------------------------------------------------------
    // Observable property: detectedDecoder
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the detected decoder type.
     *
     * @return decoder property (value may be null until IDENTIFIED)
     */
    public ObjectProperty<DecoderType> detectedDecoderProperty()
    {
        return mDetectedDecoder;
    }

    /**
     * The decoder that locked, or {@code null} if not yet IDENTIFIED.
     *
     * @return detected decoder type or null
     */
    public DecoderType getDetectedDecoder()
    {
        return mDetectedDecoder.get();
    }

    /**
     * Sets the detected decoder type.
     *
     * @param decoder decoder type; may be null
     */
    public void setDetectedDecoder(DecoderType decoder)
    {
        mDetectedDecoder.set(decoder);
    }

    // -------------------------------------------------------------------------
    // Observable property: kind
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the signal kind.
     *
     * @return kind property
     */
    public ObjectProperty<SignalKind> kindProperty()
    {
        return mKind;
    }

    /**
     * Signal kind (CONTROL, CONVENTIONAL, TRAFFIC, UNKNOWN).
     *
     * @return signal kind
     */
    public SignalKind getKind()
    {
        return mKind.get();
    }

    /**
     * Sets the signal kind.
     *
     * @param kind signal kind; must not be null
     */
    public void setKind(SignalKind kind)
    {
        mKind.set(kind);
    }

    // -------------------------------------------------------------------------
    // Observable property: confidence (0..4 "pips")
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the confidence level.
     *
     * @return confidence property (0..4)
     */
    public IntegerProperty confidenceProperty()
    {
        return mConfidence;
    }

    /**
     * Confidence level from 0 (no confidence) to 4 (very high confidence),
     * derived from the best candidate's lock state and quality.
     *
     * @return confidence pips (0..4)
     */
    public int getConfidence()
    {
        return mConfidence.get();
    }

    /**
     * Sets the confidence pips.
     *
     * @param confidence value in [0, 4]
     */
    public void setConfidence(int confidence)
    {
        mConfidence.set(confidence);
    }

    // -------------------------------------------------------------------------
    // Observable property: firstSeen
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the first-seen timestamp.
     *
     * @return first-seen property
     */
    public ObjectProperty<Instant> firstSeenProperty()
    {
        return mFirstSeen;
    }

    /**
     * When this discovery was first detected.
     *
     * @return first-seen instant
     */
    public Instant getFirstSeen()
    {
        return mFirstSeen.get();
    }

    /**
     * Updates the first-seen timestamp.
     *
     * @param firstSeen instant; may be null
     */
    public void setFirstSeen(Instant firstSeen)
    {
        mFirstSeen.set(firstSeen);
    }

    // -------------------------------------------------------------------------
    // Observable property: lastSeen
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the last-seen timestamp.
     *
     * @return last-seen property
     */
    public ObjectProperty<Instant> lastSeenProperty()
    {
        return mLastSeen;
    }

    /**
     * When this discovery was last confirmed to have energy.
     *
     * @return last-seen instant
     */
    public Instant getLastSeen()
    {
        return mLastSeen.get();
    }

    /**
     * Updates the last-seen timestamp.
     *
     * @param lastSeen instant; may be null
     */
    public void setLastSeen(Instant lastSeen)
    {
        mLastSeen.set(lastSeen);
    }

    // -------------------------------------------------------------------------
    // Observable property: createdChannel
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the channel created from this discovery.
     *
     * @return created-channel property (value may be null until added)
     */
    public ObjectProperty<Channel> createdChannelProperty()
    {
        return mCreatedChannel;
    }

    /**
     * The channel created from this discovery, or {@code null} if not yet added.
     *
     * @return created channel or null
     */
    public Channel getCreatedChannel()
    {
        return mCreatedChannel.get();
    }

    /**
     * Sets the channel created from this discovery.
     *
     * @param channel channel instance; may be null
     */
    public void setCreatedChannel(Channel channel)
    {
        mCreatedChannel.set(channel);
    }

    // -------------------------------------------------------------------------
    // Observable property: watched
    // -------------------------------------------------------------------------

    /**
     * JavaFX property for the watched flag.
     *
     * @return watched property
     */
    public BooleanProperty watchedProperty()
    {
        return mWatched;
    }

    /**
     * Whether the operator has flagged this discovery for continuous re-probing.
     *
     * @return true if watched
     */
    public boolean isWatched()
    {
        return mWatched.get();
    }

    /**
     * Sets the watched flag.
     *
     * @param watched true to flag for continuous re-probing
     */
    public void setWatched(boolean watched)
    {
        mWatched.set(watched);
    }

    // -------------------------------------------------------------------------
    // Metadata map (plain, not an ObservableMap; Phase 4 can wrap it)
    // -------------------------------------------------------------------------

    /**
     * Key/value metadata harvested during probing (e.g. NAC, color code, site ID).
     * Returns the live map; callers should not modify it.
     *
     * @return metadata map (never null, may be empty)
     */
    public Map<String, String> getMetadata()
    {
        return mMetadata;
    }

    /**
     * Replaces the metadata map.  Also increments the metadata-version counter so that
     * JavaFX bindings on {@link #metadataVersionProperty()} detect the change and refresh.
     *
     * @param metadata new metadata map; if null, an empty map is stored
     */
    public void setMetadata(Map<String, String> metadata)
    {
        mMetadata = (metadata != null) ? metadata : new HashMap<>();
        bumpMetadataVersion();
    }

    // -------------------------------------------------------------------------
    // Observable property: metadataVersion
    // -------------------------------------------------------------------------

    /**
     * JavaFX property whose value increments each time the metadata map is replaced.
     * Bind table cell value factories to this property to trigger Notes-column refreshes
     * when metadata changes.
     *
     * @return metadata-version property
     */
    public IntegerProperty metadataVersionProperty()
    {
        return mMetadataVersion;
    }

    /**
     * Increments the metadata-version counter.  Called automatically by {@link #setMetadata}
     * and may also be called externally when the map's contents are mutated in place.
     */
    public void bumpMetadataVersion()
    {
        mMetadataVersion.set(mMetadataVersion.get() + 1);
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    @Override
    public String toString()
    {
        return "Discovery{"
            + "freq=" + mCenterFrequencyHz + "Hz"
            + ", bw=" + mBandwidthHz + "Hz"
            + ", state=" + mState.get()
            + ", decoder=" + mDetectedDecoder.get()
            + ", kind=" + mKind.get()
            + ", confidence=" + mConfidence.get()
            + ", power=" + mPowerDb + "dB"
            + ", snr=" + mSnrDb + "dB"
            + ", watched=" + mWatched.get()
            + "}";
    }
}
