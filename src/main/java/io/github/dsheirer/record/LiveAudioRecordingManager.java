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
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3AudioConverter;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.wave.AudioMetadata;
import io.github.dsheirer.record.wave.AudioMetadataUtils;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records decoded audio that would otherwise be eligible for live playback.  A user-controlled recording session
 * produces one MP3 per logical channel/talkgroup key.
 */
public class LiveAudioRecordingManager implements Listener<AudioSegment>
{
    private static final Logger mLog = LoggerFactory.getLogger(LiveAudioRecordingManager.class);
    private static final String SESSION_DIRECTORY_PREFIX = "now_playing_";
    private static final int SILENCE_CHUNK_SAMPLES = 160;
    private static final long MAX_INSERTED_SILENCE_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);

    private final LinkedTransferQueue<AudioSegment> mNewAudioSegments = new LinkedTransferQueue<>();
    private final List<AudioSegment> mAudioSegments = new ArrayList<>();
    private final Map<String,LiveRecordingWriter> mWriters = new LinkedHashMap<>();
    private final AtomicBoolean mProcessing = new AtomicBoolean();
    private final UserPreferences mUserPreferences;
    private ScheduledFuture<?> mAudioSegmentProcessorFuture;
    private volatile boolean mRecording;
    private String mSessionName;
    private Path mSessionDirectory;

    /**
     * Constructs an instance.
     * @param userPreferences for recording paths and MP3 encoder settings
     */
    public LiveAudioRecordingManager(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
    }

    /**
     * Starts the background processor.  This does not start a recording session.
     */
    public void start()
    {
        if(mAudioSegmentProcessorFuture == null)
        {
            mAudioSegmentProcessorFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(new AudioSegmentProcessor(),
                0, 250, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops the background processor and closes the current recording session.
     */
    public void stop()
    {
        if(mAudioSegmentProcessorFuture != null)
        {
            mAudioSegmentProcessorFuture.cancel(true);
            mAudioSegmentProcessorFuture = null;
        }

        stopRecording();
        discardQueuedAudioSegments();
    }

    /**
     * Starts a new Now Playing recording session.
     */
    public synchronized void startRecording()
    {
        if(!mRecording)
        {
            mSessionName = TimeStamp.getLongTimeStamp("_");
            mSessionDirectory = mUserPreferences.getDirectoryPreference().getDirectoryRecording()
                .resolve(SESSION_DIRECTORY_PREFIX + mSessionName);

            try
            {
                Files.createDirectories(mSessionDirectory);
                mRecording = true;
                mLog.info("Started Now Playing recording session [{}]", mSessionDirectory);
            }
            catch(IOException ioe)
            {
                Path sessionDirectory = mSessionDirectory;
                mSessionName = null;
                mSessionDirectory = null;
                mLog.error("Unable to create Now Playing recording session directory [{}]", sessionDirectory, ioe);
            }
        }
    }

    /**
     * Stops the current Now Playing recording session and closes all open MP3 files.
     */
    public synchronized void stopRecording()
    {
        if(mRecording)
        {
            mRecording = false;
            processAudioSegments(true);
            closeWriters();
            mLog.info("Stopped Now Playing recording session [{}]", mSessionDirectory);
            mSessionName = null;
            mSessionDirectory = null;
        }
    }

    /**
     * Toggles the current recording session.
     */
    public void setRecording(boolean recording)
    {
        if(recording)
        {
            startRecording();
        }
        else
        {
            stopRecording();
        }
    }

    /**
     * Indicates if a Now Playing recording session is active.
     */
    public boolean isRecording()
    {
        return mRecording;
    }

    /**
     * Current session directory, or null if recording is stopped.
     */
    public Path getSessionDirectory()
    {
        return mSessionDirectory;
    }

    /**
     * Number of open MP3 files in the current session.
     */
    public int getActiveRecordingCount()
    {
        return mWriters.size();
    }

    @Override
    public void receive(AudioSegment audioSegment)
    {
        if(audioSegment == null)
        {
            return;
        }

        if(isRecording())
        {
            mNewAudioSegments.add(audioSegment);
        }
        else
        {
            audioSegment.decrementConsumerCount();
        }
    }

    /**
     * Processes queued audio segments.  Package-private for tests.
     */
    synchronized void processAudioSegments()
    {
        processAudioSegments(false);
    }

    private synchronized void processAudioSegments(boolean includeIncomplete)
    {
        mNewAudioSegments.drainTo(mAudioSegments);

        if(mAudioSegments.isEmpty())
        {
            return;
        }

        boolean recording = isRecording() || includeIncomplete;
        var iterator = mAudioSegments.iterator();

        while(iterator.hasNext())
        {
            AudioSegment audioSegment = iterator.next();

            if(!recording || audioSegment.isComplete() || includeIncomplete)
            {
                iterator.remove();

                if(recording && shouldRecord(audioSegment))
                {
                    record(audioSegment);
                }

                audioSegment.decrementConsumerCount();
            }
        }
    }

    private boolean shouldRecord(AudioSegment audioSegment)
    {
        return audioSegment.hasAudio() &&
            !audioSegment.isEncrypted() &&
            !audioSegment.isDoNotMonitor() &&
            !(audioSegment.isDuplicate() &&
                mUserPreferences.getCallManagementPreference().isDuplicatePlaybackSuppressionEnabled());
    }

    private void record(AudioSegment audioSegment)
    {
        LiveRecordingKey key = LiveRecordingKey.from(audioSegment.getIdentifierCollection(), audioSegment.getTimeslot());
        LiveRecordingWriter writer = mWriters.get(key.key());

        if(writer == null)
        {
            Path path = getRecordingPath(key);

            try
            {
                writer = new LiveRecordingWriter(path, audioSegment, mUserPreferences);
                mWriters.put(key.key(), writer);
            }
            catch(IOException ioe)
            {
                mLog.error("Unable to create Now Playing recording [{}]", path, ioe);
                return;
            }
        }

        try
        {
            writer.write(audioSegment);
        }
        catch(IOException ioe)
        {
            mLog.error("Error writing Now Playing recording [{}]", writer.getPath(), ioe);
        }
    }

    private Path getRecordingPath(LiveRecordingKey key)
    {
        String filename = clean(key.filename());

        int maxLength = 255 - RecordFormat.MP3.getExtension().length();
        if(filename.length() > maxLength)
        {
            filename = filename.substring(0, maxLength);
        }

        return mSessionDirectory.resolve(filename + RecordFormat.MP3.getExtension());
    }

    private void closeWriters()
    {
        for(LiveRecordingWriter writer: mWriters.values())
        {
            try
            {
                writer.close();
            }
            catch(IOException ioe)
            {
                mLog.error("Error closing Now Playing recording [{}]", writer.getPath(), ioe);
            }
        }

        mWriters.clear();
    }

    private void discardQueuedAudioSegments()
    {
        AudioSegment audioSegment = mNewAudioSegments.poll();

        while(audioSegment != null)
        {
            audioSegment.decrementConsumerCount();
            audioSegment = mNewAudioSegments.poll();
        }

        for(AudioSegment queuedAudioSegment: mAudioSegments)
        {
            queuedAudioSegment.decrementConsumerCount();
        }

        mAudioSegments.clear();
    }

    private static String value(IdentifierCollection identifierCollection, IdentifierClass identifierClass, Form form)
    {
        if(identifierCollection != null)
        {
            Identifier identifier = identifierCollection.getIdentifier(identifierClass, form, Role.ANY);

            if(identifier != null && identifier.getValue() != null)
            {
                return identifier.getValue().toString();
            }
        }

        return null;
    }

    private static String clean(String value)
    {
        if(value == null || value.isBlank())
        {
            return "unknown";
        }

        String cleaned = StringUtils.replaceIllegalCharacters(value.trim());

        while(cleaned.contains("--"))
        {
            cleaned = cleaned.replace("--", "-");
        }

        if(cleaned.length() > 1 && cleaned.endsWith("-"))
        {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }

        return cleaned.isBlank() ? "unknown" : cleaned;
    }

    /**
     * Scheduled runnable to process audio segments.
     */
    public class AudioSegmentProcessor implements Runnable
    {
        @Override
        public void run()
        {
            if(mProcessing.compareAndSet(false, true))
            {
                try
                {
                    processAudioSegments();
                }
                catch(Throwable t)
                {
                    mLog.error("Error processing Now Playing audio recordings", t);
                }
                finally
                {
                    mProcessing.set(false);
                }
            }
        }
    }

    private static class LiveRecordingKey
    {
        private final String mKey;
        private final String mFilename;

        private LiveRecordingKey(String key, String filename)
        {
            mKey = key;
            mFilename = filename;
        }

        private String key()
        {
            return mKey;
        }

        private String filename()
        {
            return mFilename;
        }

        private static LiveRecordingKey from(IdentifierCollection identifierCollection, int timeslot)
        {
            String system = value(identifierCollection, IdentifierClass.CONFIGURATION, Form.SYSTEM);
            String site = value(identifierCollection, IdentifierClass.CONFIGURATION, Form.SITE);
            String channel = value(identifierCollection, IdentifierClass.CONFIGURATION, Form.CHANNEL);
            String frequency = value(identifierCollection, IdentifierClass.CONFIGURATION, Form.CHANNEL_FREQUENCY);
            Identifier to = identifierCollection != null ? identifierCollection.getToIdentifier() : null;

            List<String> keyParts = new ArrayList<>();
            List<String> fileParts = new ArrayList<>();

            addPart(keyParts, "system", system);
            addPart(fileParts, null, system);
            addPart(keyParts, "site", site);
            addPart(fileParts, null, site);

            if(to != null)
            {
                addPart(keyParts, "to", to.getProtocol() + "-" + to.getForm() + "-" + to.getValue());
                addPart(fileParts, "TO", to.toString());
            }
            else if(channel != null)
            {
                addPart(keyParts, "channel", channel);
                addPart(fileParts, "CHANNEL", channel);

                if(frequency != null)
                {
                    addPart(keyParts, "frequency", frequency);
                    addPart(fileParts, "FREQUENCY", frequency);
                }
            }
            else if(frequency != null)
            {
                addPart(keyParts, "frequency", frequency);
                addPart(fileParts, "FREQUENCY", frequency);
            }
            else
            {
                addPart(keyParts, "timeslot", String.valueOf(timeslot));
                addPart(fileParts, "TIMESLOT", String.valueOf(timeslot));
            }

            if(to == null && timeslot > 0)
            {
                addPart(keyParts, "timeslot", String.valueOf(timeslot));
                addPart(fileParts, "TIMESLOT", String.valueOf(timeslot));
            }

            return new LiveRecordingKey(String.join("|", keyParts), String.join("_", fileParts));
        }

        private static void addPart(List<String> parts, String label, String value)
        {
            if(value != null && !value.isBlank())
            {
                if(label != null)
                {
                    parts.add(clean(label + "-" + value));
                }
                else
                {
                    parts.add(clean(value));
                }
            }
        }
    }

    private static class LiveRecordingWriter implements Closeable
    {
        private final Path mPath;
        private final MP3AudioConverter mConverter;
        private final OutputStream mOutputStream;
        private long mLastEndTimestamp = -1;

        private LiveRecordingWriter(Path path, AudioSegment firstSegment, UserPreferences userPreferences)
            throws IOException
        {
            mPath = path;
            Files.createDirectories(mPath.getParent());
            mOutputStream = new FileOutputStream(mPath.toFile());

            IdentifierCollection identifierCollection = firstSegment.getIdentifierCollection();
            AliasList aliasList = firstSegment.getAliasList() != null ? firstSegment.getAliasList() : new AliasList("Live");
            Map<AudioMetadata,String> metadataMap = AudioMetadataUtils.getMetadataMap(identifierCollection, aliasList);
            mOutputStream.write(AudioMetadataUtils.getMP3ID3(metadataMap));

            InputAudioFormat inputAudioFormat = userPreferences.getMP3Preference().getAudioSampleRate();
            MP3Setting mp3Setting = userPreferences.getMP3Preference().getMP3Setting();
            boolean normalizeAudio = userPreferences.getMP3Preference().isNormalizeAudioBeforeEncode();
            mConverter = new MP3AudioConverter(inputAudioFormat, mp3Setting, normalizeAudio);
        }

        private Path getPath()
        {
            return mPath;
        }

        private void write(AudioSegment audioSegment) throws IOException
        {
            writeSilence(audioSegment.getStartTimestamp());

            for(byte[] frame: mConverter.convertStreaming(audioSegment.getAudioBuffers()))
            {
                mOutputStream.write(frame);
            }

            mLastEndTimestamp = Math.max(mLastEndTimestamp, audioSegment.getEndTimestamp());
        }

        private void writeSilence(long startTimestamp) throws IOException
        {
            if(mLastEndTimestamp > 0 && startTimestamp > mLastEndTimestamp)
            {
                long silenceMilliseconds = Math.min(startTimestamp - mLastEndTimestamp, MAX_INSERTED_SILENCE_MILLISECONDS);
                writeSilenceSamples(silenceMilliseconds * 8);
            }
        }

        private void writeSilenceSamples(long samples) throws IOException
        {
            while(samples > 0)
            {
                int sampleCount = (int)Math.min(SILENCE_CHUNK_SAMPLES, samples);

                for(byte[] frame: mConverter.convertStreaming(Collections.singletonList(new float[sampleCount])))
                {
                    mOutputStream.write(frame);
                }

                samples -= sampleCount;
            }
        }

        @Override
        public void close() throws IOException
        {
            for(byte[] frame: mConverter.flush())
            {
                mOutputStream.write(frame);
            }

            mOutputStream.flush();
            mOutputStream.close();
        }
    }
}
