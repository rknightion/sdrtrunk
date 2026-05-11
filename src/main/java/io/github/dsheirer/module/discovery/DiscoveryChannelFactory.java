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
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.source.config.SourceConfigTuner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory that creates {@link Channel} instances from signal-discovery results.
 *
 * <p>Used by both the click-to-tune controller (Phase 2) and the band-scan orchestrator (Phase 3)
 * so that channel construction is not duplicated.</p>
 *
 * <h3>Naming convention</h3>
 * <ul>
 *   <li>{@code "Discovered <freq_MHz>"} for conventional / traffic / unknown signals</li>
 *   <li>{@code "Discovered <freq_MHz> (control)"} when {@code result.kind() == CONTROL}</li>
 * </ul>
 *
 * <h3>Decode configuration</h3>
 * The {@link DecodeConfiguration} stored on the returned channel is always a fresh copy;
 * it is never the same object instance as the one carried in the {@link ClassificationResult},
 * so it is safe to start multiple channels without config sharing.
 */
public class DiscoveryChannelFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryChannelFactory.class);

    /**
     * Creates a {@link Channel} from a successful {@link ClassificationResult}.
     *
     * @param result              the classification result — must have {@link ClassificationOutcome#IDENTIFIED}
     * @param defaultAliasListName alias list name to associate with the channel (may be null)
     * @return a configured, ready-to-add {@link Channel}
     * @throws IllegalArgumentException if the result is not {@code IDENTIFIED}
     */
    public Channel createChannel(ClassificationResult result, String defaultAliasListName)
    {
        if(result == null)
        {
            throw new IllegalArgumentException("ClassificationResult must not be null");
        }

        if(!result.isIdentified())
        {
            throw new IllegalArgumentException("Cannot create a channel from a non-IDENTIFIED result (outcome="
                + result.outcome() + ")");
        }

        String name = buildChannelName(result.centerFrequencyHz(), result.kind());

        Channel channel = new Channel(name);
        channel.setSourceConfiguration(buildSourceConfig(result.centerFrequencyHz()));

        // Always use a fresh decode config — never share the object in the result
        DecodeConfiguration freshConfig = DecoderFactory.getDecodeConfiguration(result.bestDecoder());
        channel.setDecodeConfiguration(freshConfig);

        if(defaultAliasListName != null && !defaultAliasListName.isBlank())
        {
            channel.setAliasListName(defaultAliasListName);
        }

        mLog.debug("Created channel '{}' for {} at {} Hz", name, result.bestDecoder(),
            result.centerFrequencyHz());

        return channel;
    }

    /**
     * Creates a {@link Channel} for the "Decode here as ▸ X" path, where the user explicitly
     * selects a decoder without running the auto-detection classifier.
     *
     * @param freqHz       center frequency, in Hz
     * @param type         decoder type chosen by the user
     * @param aliasListName alias list name (may be null)
     * @return a configured {@link Channel}
     */
    public Channel createChannel(long freqHz, DecoderType type, String aliasListName)
    {
        if(freqHz <= 0)
        {
            throw new IllegalArgumentException("freqHz must be positive, got: " + freqHz);
        }

        if(type == null)
        {
            throw new IllegalArgumentException("DecoderType must not be null");
        }

        String name = buildChannelName(freqHz, SignalKind.UNKNOWN);

        Channel channel = new Channel(name);
        channel.setSourceConfiguration(buildSourceConfig(freqHz));
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(type));

        if(aliasListName != null && !aliasListName.isBlank())
        {
            channel.setAliasListName(aliasListName);
        }

        mLog.debug("Created manual channel '{}' for {} at {} Hz", name, type, freqHz);

        return channel;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the human-readable channel name for a discovered signal.
     *
     * @param freqHz frequency in Hz
     * @param kind   signal kind — appends " (control)" for {@link SignalKind#CONTROL}
     * @return the channel name string
     */
    static String buildChannelName(long freqHz, SignalKind kind)
    {
        // Format as MHz with 3 decimal places (e.g. "Discovered 154.920")
        double mhz = freqHz / 1_000_000.0;
        String freqLabel = String.format("%.3f", mhz);
        String name = "Discovered " + freqLabel;

        if(kind == SignalKind.CONTROL)
        {
            name += " (control)";
        }

        return name;
    }

    /**
     * Builds a {@link SourceConfigTuner} for the given frequency.
     *
     * @param freqHz center frequency in Hz
     * @return configured source configuration
     */
    private static SourceConfigTuner buildSourceConfig(long freqHz)
    {
        SourceConfigTuner config = new SourceConfigTuner();
        config.setFrequency(freqHz);
        return config;
    }
}
