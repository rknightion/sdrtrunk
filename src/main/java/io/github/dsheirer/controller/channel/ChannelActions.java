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
package io.github.dsheirer.controller.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared lifecycle actions for channels that can be invoked from Swing and JavaFX UI surfaces.
 */
public final class ChannelActions
{
    private static final Logger mLog = LoggerFactory.getLogger(ChannelActions.class);

    private ChannelActions()
    {
    }

    /**
     * Stops a channel if it is currently processing.
     *
     * @param channelProcessingManager processing manager
     * @param channel channel to stop
     */
    public static void stop(ChannelProcessingManager channelProcessingManager, Channel channel)
    {
        if(channelProcessingManager == null || channel == null || !channel.isProcessing())
        {
            return;
        }

        try
        {
            channelProcessingManager.stop(channel);
        }
        catch(ChannelException e)
        {
            mLog.warn("Error stopping channel '{}': {}", channel.getName(), e.getMessage());
        }
    }

    /**
     * Removes a temporary live channel from the channel model, stopping it first if needed.
     *
     * @param channelModel channel model
     * @param channelProcessingManager processing manager
     * @param channel channel to remove
     */
    public static void removeTemporaryLive(ChannelModel channelModel, ChannelProcessingManager channelProcessingManager,
                                           Channel channel)
    {
        if(channelModel == null || channel == null || !channel.isTemporaryLive())
        {
            return;
        }

        if(channelProcessingManager != null)
        {
            try
            {
                channelProcessingManager.stop(channel);
            }
            catch(ChannelException e)
            {
                mLog.warn("Error stopping temporary live channel '{}': {}", channel.getName(), e.getMessage());
            }
        }

        channelModel.removeChannel(channel);
    }

    /**
     * Promotes a temporary live channel into a normal persisted playlist channel.
     *
     * @param channel channel to save
     */
    public static void saveToPlaylist(Channel channel)
    {
        if(channel != null && channel.isTemporaryLive())
        {
            channel.setTemporaryLive(false);
        }
    }
}
