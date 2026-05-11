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

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.map.ChannelMapModel;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a transient "probe chain" for a given {@link DecoderType}.
 *
 * <p>A probe chain contains only the decoder and channel-state modules needed to
 * detect a protocol lock.  Audio output, event logging, recording, and traffic-channel
 * management modules are stripped, so the chain is lightweight and does not produce
 * side-effects (no audio, no log entries, no recordings).</p>
 *
 * <p>The chain is not started here — the caller must call
 * {@link ProcessingChain#setSource(io.github.dsheirer.source.Source)} then
 * {@link ProcessingChain#start()} before samples will flow through it.</p>
 */
public class ProbeChainFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(ProbeChainFactory.class);

    private final AliasModel mAliasModel;
    private final ChannelMapModel mChannelMapModel;
    private final UserPreferences mUserPreferences;

    /**
     * Constructs an instance.
     *
     * @param aliasModel      alias model (used by DecoderFactory; an empty model is acceptable)
     * @param channelMapModel channel map model (used by MPT1327; an empty model is acceptable)
     * @param userPreferences user preferences (used by P25/DMR audio modules, which we strip)
     */
    public ProbeChainFactory(AliasModel aliasModel, ChannelMapModel channelMapModel, UserPreferences userPreferences)
    {
        mAliasModel = aliasModel;
        mChannelMapModel = channelMapModel;
        mUserPreferences = userPreferences;
    }

    /**
     * Builds a decoder-only probe chain for the given decoder type.
     *
     * @param decoderType the protocol to probe
     * @return a {@link ProbeChain} holding the chain and its {@link LockWatcher}
     * @throws IllegalArgumentException if the decoder type is not a primary decoder
     */
    public ProbeChain build(DecoderType decoderType)
    {
        // Build a throwaway channel with the default decode configuration for this type.
        //
        // WHY ChannelType.STANDARD: DecoderFactory.getPrimaryModules() checks the ChannelType when
        // the TrafficChannelManager parameter is null.  For TRAFFIC channels without a TCM, the
        // factory skips adding the P25 P1 decoder state machine, which breaks lock detection.
        // Using STANDARD ensures the full decoder stack is built.  The subsequent call to
        // removeTrafficChannelManager() strips the otherwise-unused TCM module so the chain
        // remains lightweight and produces no side effects.
        Channel channel = buildProbeChannel(decoderType);

        // Create the processing chain (auto-adds channel state, DecodeEventHistory, MessageHistory)
        ProcessingChain chain = new ProcessingChain(channel, mAliasModel);

        // Get all primary modules from DecoderFactory (no aux decoders, null traffic channel manager)
        List<Module> primaryModules;

        try
        {
            primaryModules = DecoderFactory.getPrimaryModules(
                mChannelMapModel,
                channel,
                mAliasModel,
                mUserPreferences,
                null,   // no traffic channel manager
                null    // no channel descriptor
            );
        }
        catch(Exception e)
        {
            chain.dispose();
            throw new RuntimeException("Failed to build primary modules for " + decoderType, e);
        }

        // Strip audio modules — we only want the decoder and state modules
        List<Module> probeModules = stripAudioModules(primaryModules);

        chain.addModules(probeModules);

        // Remove logging/recording/traffic modules that the chain or factory may have added
        chain.removeEventLoggingModules();
        chain.removeRecordingModules();
        chain.removeTrafficChannelManager();

        // Create and wire the LockWatcher.
        // Use addDecoderStateEventListener to register without making it a full module.
        LockWatcher lockWatcher = new LockWatcher();
        chain.addDecoderStateEventListener(lockWatcher.getDecoderStateListener());

        // Also wire identifier updates so the watcher can harvest NAC / color code / etc.
        // ProcessingChain.receive(IdentifierUpdateNotification) broadcasts them, but there's
        // no external addIdentifierUpdateListener — so we register via addModule if needed,
        // or we just let the watcher receive them indirectly through the chain's identifier
        // update broadcaster. The cleanest approach: add the watcher as a Module so that
        // ProcessingChain.addModule() auto-wires it via IdentifierUpdateListener.
        chain.addModule(new IdentifierForwardingModule(lockWatcher));

        return new ProbeChain(decoderType, chain, lockWatcher);
    }

    /**
     * Creates a minimal {@link Channel} configured with the default decode config for the
     * given decoder type and a placeholder source config.
     */
    private Channel buildProbeChannel(DecoderType decoderType)
    {
        Channel channel = new Channel("probe:" + decoderType.name());
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(decoderType));

        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(0L);
        channel.setSourceConfiguration(sourceConfig);

        return channel;
    }

    /**
     * Removes any {@link AbstractAudioModule} instances from the list, returning a new list
     * containing only non-audio modules.
     */
    private static List<Module> stripAudioModules(List<Module> modules)
    {
        List<Module> stripped = new ArrayList<>(modules.size());

        for(Module module : modules)
        {
            if(!(module instanceof AbstractAudioModule))
            {
                stripped.add(module);
            }
            else
            {
                mLog.trace("Stripping audio module from probe chain: {}", module.getClass().getSimpleName());
            }
        }

        return stripped;
    }
}
