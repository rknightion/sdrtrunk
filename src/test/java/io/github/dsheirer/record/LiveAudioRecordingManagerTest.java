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

package io.github.dsheirer.record;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.dsp.oscillator.ScalarRealOscillator;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveAudioRecordingManagerTest
{
    @TempDir
    Path mTempDir;

    private UserPreferences mUserPreferences;
    private Path mOriginalRecordingDirectory;

    @BeforeEach
    void setup()
    {
        mUserPreferences = new UserPreferences();
        mOriginalRecordingDirectory = mUserPreferences.getDirectoryPreference().getDirectoryRecording();
        mUserPreferences.getDirectoryPreference().setDirectoryRecording(mTempDir);
        mUserPreferences.getCallManagementPreference().setDuplicatePlaybackSuppressionEnabled(true);
    }

    @AfterEach
    void cleanup()
    {
        if(mOriginalRecordingDirectory != null)
        {
            mUserPreferences.getDirectoryPreference().setDirectoryRecording(mOriginalRecordingDirectory);
        }
    }

    @Test
    void recordsOneMp3PerTalkgroupForSession() throws IOException
    {
        LiveAudioRecordingManager manager = new LiveAudioRecordingManager(mUserPreferences);

        manager.startRecording();
        receive(manager, talkgroupSegment(100));
        receive(manager, talkgroupSegment(100));
        receive(manager, talkgroupSegment(200));
        manager.processAudioSegments();
        manager.stopRecording();

        List<Path> recordings = recordings();

        assertEquals(2, recordings.size());
        assertTrue(recordings.stream().anyMatch(path -> path.getFileName().toString().contains("TO-100")));
        assertTrue(recordings.stream().anyMatch(path -> path.getFileName().toString().contains("TO-200")));
        assertTrue(recordings.stream().allMatch(path -> {
            try
            {
                return Files.size(path) > 0;
            }
            catch(IOException ioe)
            {
                return false;
            }
        }));
    }

    @Test
    void skipsEncryptedDuplicateAndDoNotMonitorSegments() throws IOException
    {
        LiveAudioRecordingManager manager = new LiveAudioRecordingManager(mUserPreferences);

        AudioSegment duplicate = talkgroupSegment(101);
        duplicate.setDuplicate(true);

        AudioSegment encrypted = talkgroupSegment(102);
        encrypted.encryptedProperty().set(true);

        AudioSegment doNotMonitor = talkgroupSegment(103);
        doNotMonitor.monitorPriorityProperty().set(Priority.DO_NOT_MONITOR);

        manager.startRecording();
        receive(manager, talkgroupSegment(100));
        receive(manager, duplicate);
        receive(manager, encrypted);
        receive(manager, doNotMonitor);
        manager.processAudioSegments();
        manager.stopRecording();

        List<Path> recordings = recordings();

        assertEquals(1, recordings.size());
        assertTrue(recordings.get(0).getFileName().toString().contains("TO-100"));
    }

    @Test
    void recordsConventionalAudioByChannelWhenNoTalkgroup() throws IOException
    {
        LiveAudioRecordingManager manager = new LiveAudioRecordingManager(mUserPreferences);

        manager.startRecording();
        receive(manager, conventionalSegment());
        manager.processAudioSegments();
        manager.stopRecording();

        List<Path> recordings = recordings();

        assertEquals(1, recordings.size());
        assertTrue(recordings.get(0).getFileName().toString().contains("CHANNEL-NFM-Test"));
    }

    private static void receive(LiveAudioRecordingManager manager, AudioSegment audioSegment)
    {
        audioSegment.incrementConsumerCount();
        manager.receive(audioSegment);
    }

    private static AudioSegment talkgroupSegment(int talkgroup)
    {
        AudioSegment audioSegment = baseSegment();
        audioSegment.addIdentifier(APCO25Talkgroup.create(talkgroup));
        audioSegment.completeProperty().set(true);
        return audioSegment;
    }

    private static AudioSegment conventionalSegment()
    {
        AudioSegment audioSegment = baseSegment();
        audioSegment.completeProperty().set(true);
        return audioSegment;
    }

    private static AudioSegment baseSegment()
    {
        AudioSegment audioSegment = new AudioSegment(new AliasList("test"), TimeslotMessage.TIMESLOT_0);
        audioSegment.addIdentifier(SystemConfigurationIdentifier.create("System A"));
        audioSegment.addIdentifier(ChannelNameConfigurationIdentifier.create("NFM Test"));
        audioSegment.addIdentifier(FrequencyConfigurationIdentifier.create(154_920_000L));

        ScalarRealOscillator oscillator = new ScalarRealOscillator(1000, 8000);

        for(int x = 0; x < 5; x++)
        {
            audioSegment.addAudio(oscillator.generate(500));
        }

        return audioSegment;
    }

    private List<Path> recordings() throws IOException
    {
        try(Stream<Path> pathStream = Files.walk(mTempDir))
        {
            return pathStream.filter(path -> path.getFileName().toString().endsWith(".mp3"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }
}
