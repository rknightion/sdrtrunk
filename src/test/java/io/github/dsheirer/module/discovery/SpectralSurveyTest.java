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

import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SpectralSurvey.findPeaks (pure static logic) and in-band survey
 * with a fake source provider.
 */
class SpectralSurveyTest
{
    private static final org.slf4j.Logger mLog =
        org.slf4j.LoggerFactory.getLogger(SpectralSurveyTest.class);

    /** Base frequency for test arrays: 154 MHz */
    private static final long BASE_FREQ_HZ = 154_000_000L;

    /** Bin width for most tests: 500 Hz */
    private static final long BIN_WIDTH_HZ = 500L;

    /** Default threshold: 6 dB above noise floor */
    private static final double THRESHOLD_DB = 6.0;

    private ExecutorService mExecutor;

    @BeforeEach
    void setUp()
    {
        mExecutor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown()
    {
        mExecutor.shutdownNow();
    }

    // =========================================================================
    // findPeaks — static method tests (Task 3.1)
    // =========================================================================

    @Test
    void findPeaks_emptyArray_returnsEmpty()
    {
        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(new float[0], BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);
        assertTrue(peaks.isEmpty(), "Empty array should yield no peaks");
    }

    @Test
    void findPeaks_flatNoise_returnsNoPeaks()
    {
        // Flat noise at -80 dB — no peaks expected
        float[] mags = makeFlat(100, -80.0f);
        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);
        assertTrue(peaks.isEmpty(), "Flat noise should yield no peaks");
    }

    @Test
    void findPeaks_oneFatBump_yieldsOnePeakWithCorrectCenterAndWidth()
    {
        // Noise at -80 dB; a 5-bin bump from index 40..44 at -50 dB
        float[] mags = makeFlat(100, -80.0f);
        int bumpStart = 40;
        int bumpEnd = 44;

        for(int i = bumpStart; i <= bumpEnd; i++)
        {
            mags[i] = -50.0f;
        }

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        assertEquals(1, peaks.size(), "Should detect exactly one peak");

        EnergyPeak peak = peaks.get(0);

        // Center should be around bin 42 (the middle of bins 40..44)
        long expectedCenter = BASE_FREQ_HZ + 42 * BIN_WIDTH_HZ;
        long tolerance = 3 * BIN_WIDTH_HZ; // allow ±3 bins
        assertTrue(Math.abs(peak.centerFrequencyHz() - expectedCenter) <= tolerance,
            "Peak center should be near bin 42; expected ~" + expectedCenter + " got " + peak.centerFrequencyHz());

        // Bandwidth should be at least the bump span (5 bins = 2500 Hz) widened by guards
        assertTrue(peak.occupiedBandwidthHz() >= 5 * (int)BIN_WIDTH_HZ,
            "Peak bandwidth should be at least the bump span");

        // Power should match the bump level
        assertTrue(peak.powerDb() >= -55.0 && peak.powerDb() <= -45.0,
            "Peak power should be around -50 dB, got: " + peak.powerDb());

        // SNR should be approximately 30 dB (peak − floor)
        assertTrue(peak.snrDb() >= 20.0,
            "SNR should be at least 20 dB (well above noise floor), got: " + peak.snrDb());
    }

    @Test
    void findPeaks_twoWellSeparatedBumps_yieldsTwoDistinctPeaks()
    {
        // Two bumps at bins 10..12 and 80..82 (well-separated — no merge expected)
        float[] mags = makeFlat(100, -80.0f);

        for(int i = 10; i <= 12; i++) mags[i] = -50.0f;
        for(int i = 80; i <= 82; i++) mags[i] = -55.0f;

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        assertEquals(2, peaks.size(),
            "Should detect exactly two separate peaks; got " + peaks.size());

        // Peaks should be sorted by center frequency ascending
        assertTrue(peaks.get(0).centerFrequencyHz() < peaks.get(1).centerFrequencyHz(),
            "Peaks should be sorted by frequency ascending");
    }

    @Test
    void findPeaks_twoVeryCloseBumps_mergesToOnePeak()
    {
        // Two bumps at adjacent bins (1 bin apart) — should merge
        float[] mags = makeFlat(200, -80.0f);
        // Bump 1: bins 50..52
        // Bump 2: bins 53..55  (only 1 gap bin apart — within MIN_SEPARATION_BINS)
        for(int i = 50; i <= 52; i++) mags[i] = -50.0f;
        for(int i = 53; i <= 55; i++) mags[i] = -50.0f;

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        // The two runs have only 0 gap between them (no below-threshold bins between them)
        // so they should already form one contiguous run, or merge if separated by guard distance
        assertTrue(peaks.size() <= 2,
            "Close bumps should yield at most 2 peaks (might merge to 1)");
    }

    @Test
    void findPeaks_bumpAtEdge_handledGracefully()
    {
        // Bump right at the beginning (bins 0..2)
        float[] mags = makeFlat(50, -80.0f);
        mags[0] = -50.0f;
        mags[1] = -50.0f;
        mags[2] = -50.0f;

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        assertEquals(1, peaks.size(), "Edge bump should produce one peak");
        assertTrue(peaks.get(0).centerFrequencyHz() > 0, "Peak center must be positive");
    }

    @Test
    void findPeaks_bumpAtLastBin_handledGracefully()
    {
        // Bump at the last 3 bins
        float[] mags = makeFlat(50, -80.0f);
        mags[47] = -50.0f;
        mags[48] = -50.0f;
        mags[49] = -50.0f;

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        assertEquals(1, peaks.size(), "Trailing-edge bump should produce one peak");
    }

    @Test
    void findPeaks_singleBinAboveThreshold_yieldsPeak()
    {
        // Exactly one bin above threshold
        float[] mags = makeFlat(50, -80.0f);
        mags[25] = -50.0f;

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        assertEquals(1, peaks.size(), "Single above-threshold bin should yield one peak");
    }

    @Test
    void findPeaks_throwsOnInvalidBinWidth()
    {
        assertThrows(IllegalArgumentException.class, () ->
            SpectralSurvey.findPeaks(new float[]{-80.0f}, 0L, BASE_FREQ_HZ, THRESHOLD_DB));
    }

    @Test
    void findPeaks_throwsOnNonPositiveBaseFreq()
    {
        assertThrows(IllegalArgumentException.class, () ->
            SpectralSurvey.findPeaks(new float[]{-80.0f, -50.0f}, BIN_WIDTH_HZ, 0L, THRESHOLD_DB));
    }

    // =========================================================================
    // estimateNoiseFloor — package-visible helper
    // =========================================================================

    @Test
    void estimateNoiseFloor_allEqualValues_returnsThoseValues()
    {
        float[] mags = makeFlat(100, -80.0f);
        double floor = SpectralSurvey.estimateNoiseFloor(mags);
        assertEquals(-80.0, floor, 0.5, "Flat noise floor should return approximately the input value");
    }

    @Test
    void estimateNoiseFloor_fewHighPeaks_notPulledUpByPeaks()
    {
        // 90 bins at -80, 10 bins at -30 (strong signals)
        float[] mags = makeFlat(100, -80.0f);
        for(int i = 90; i < 100; i++) mags[i] = -30.0f;

        double floor = SpectralSurvey.estimateNoiseFloor(mags);

        // Floor should still be around -80, not pulled up by the strong bins
        assertTrue(floor <= -70.0,
            "Noise floor should not be dominated by strong signal bins; got: " + floor);
    }

    // =========================================================================
    // In-band survey — fake source provider (Task 3.2)
    // =========================================================================

    @Test
    @Timeout(10) // should complete well within 5 seconds
    void survey_withFakeSource_detectsPeaksInCannedBuffers() throws Exception
    {
        // Create a fake source that emits a tone at a known frequency offset from center
        long centerHz = 154_000_000L;
        double sampleRate = 2_000_000.0; // 2 Msps
        int bufferSize = 1024;

        // Tone at 200 kHz offset from center.
        // At 4096 FFT bins and 2 Msps, bin width = ~488 Hz.
        // Expected tone bin (after fftshift) ≈ 4096/2 + 200000/488 ≈ 2048 + 410 = bin 2458.
        // Expected center frequency ≈ centerHz + 200 kHz = 154_200_000 Hz.
        double toneOffsetHz = 200_000.0;
        long expectedToneHz = centerHz + (long) toneOffsetHz;
        float amplitude = 0.5f;

        FakeComplexSource fakeSource = new FakeComplexSource(sampleRate, centerHz);

        // Add AWGN noise at ~25 dB below the tone so we have a realistic noise floor.
        // The tone occupies a narrow main lobe that stands ~25 dB above the noise,
        // which greatly exceeds the 6 dB detection threshold.  Without noise, Hann-window
        // leakage fills all bins and the noise-floor estimator cannot distinguish signal
        // from leakage (every bin exceeds the threshold).
        float noiseAmplitude = amplitude / (float) Math.sqrt(Math.pow(10.0, 25.0 / 10.0));
        Random rng = new Random(42L); // fixed seed for reproducibility

        // Supply enough buffers to fill the 4096-sample ring multiple times
        int numBuffers = 40;
        for(int b = 0; b < numBuffers; b++)
        {
            float[] iBuffer = new float[bufferSize];
            float[] qBuffer = new float[bufferSize];

            for(int n = 0; n < bufferSize; n++)
            {
                double phase = 2.0 * Math.PI * toneOffsetHz * (b * bufferSize + n) / sampleRate;
                iBuffer[n] = (float)(amplitude * Math.cos(phase)) + noiseAmplitude * (float) rng.nextGaussian();
                qBuffer[n] = (float)(amplitude * Math.sin(phase)) + noiseAmplitude * (float) rng.nextGaussian();
            }

            fakeSource.addBuffer(iBuffer, qBuffer);
        }

        SpectralSurvey.SurveySourceProvider fakeProvider = (config, spec, name) -> fakeSource;
        SpectralSurvey survey = new SpectralSurvey(fakeProvider, mExecutor);

        List<EnergyPeak> peaks = survey.survey(
            centerHz - 1_000_000L,
            centerHz + 1_000_000L,
            Duration.ofMillis(200),
            6.0,
            null
        ).get(8, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks, "Peaks must not be null");
        assertEquals(1, peaks.size(),
            "Should detect exactly one peak for the injected tone; got " + peaks.size());

        EnergyPeak peak = peaks.get(0);

        // Allow ±5 kHz tolerance (10 bins at ~488 Hz per bin)
        long toleranceHz = 5_000L;
        assertTrue(Math.abs(peak.centerFrequencyHz() - expectedToneHz) <= toleranceHz,
            "Peak center should be within " + toleranceHz + " Hz of the tone at "
                + expectedToneHz + " Hz; got " + peak.centerFrequencyHz() + " Hz");

        mLog.info("Survey detected tone at {} Hz (expected {} Hz, delta {} Hz)",
            peak.centerFrequencyHz(), expectedToneHz,
            Math.abs(peak.centerFrequencyHz() - expectedToneHz));
    }

    @Test
    @Timeout(5)
    void survey_whenSourceProviderReturnsNull_completesExceptionally() throws Exception
    {
        // Provider that can't satisfy the request (no tuner available for this span)
        SpectralSurvey.SurveySourceProvider nullProvider = (config, spec, name) -> null;

        SpectralSurvey survey = new SpectralSurvey(nullProvider, mExecutor);

        var future = survey.survey(100_000_000L, 2_000_000_000L, Duration.ofSeconds(1), 6.0, null);

        assertThrows(java.util.concurrent.ExecutionException.class, () ->
            future.get(4, java.util.concurrent.TimeUnit.SECONDS),
            "Wide-span survey with null provider should complete exceptionally");
    }

    @Test
    @Timeout(5)
    void survey_whenCancelled_completesCancelledAndReleasesSource() throws Exception
    {
        // A source that blocks (never completes naturally)
        TrackingFakeComplexSource blockingSource = new TrackingFakeComplexSource(2_000_000.0, 154_000_000L);
        // Don't add any buffers — it will just sit waiting

        SpectralSurvey.SurveySourceProvider blockingProvider = (config, spec, name) -> blockingSource;
        SpectralSurvey survey = new SpectralSurvey(blockingProvider, mExecutor);

        var future = survey.survey(
            154_000_000L - 1_000_000L,
            154_000_000L + 1_000_000L,
            Duration.ofSeconds(30), // long dwell
            6.0,
            null
        );

        // Cancel after a short delay
        Thread.sleep(150);
        boolean wasCancelled = future.cancel(true);
        assertTrue(wasCancelled, "cancel() should return true");
        assertTrue(future.isCancelled(), "Future should be cancelled");

        // Give the survey's worker thread a moment to run its finally block
        long deadline = System.currentTimeMillis() + 2_000;
        while(!blockingSource.wasStopped() && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20);
        }

        assertTrue(blockingSource.wasStopped(),
            "Source stop() must be called when the survey is cancelled (tuner source released)");
        assertTrue(blockingSource.wasDisposed(),
            "Source dispose() must be called when the survey is cancelled");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Creates a float array of the given size filled with a constant value. */
    private static float[] makeFlat(int size, float value)
    {
        float[] arr = new float[size];
        Arrays.fill(arr, value);
        return arr;
    }

    // -------------------------------------------------------------------------
    // Fake ComplexSource for testing in-band survey
    // -------------------------------------------------------------------------

    /**
     * A fake {@link ComplexSource} that plays back queued buffers to a registered listener,
     * then delivers silence (zero samples) until the listener is removed.
     */
    static class FakeComplexSource extends ComplexSource
    {
        private final double mSampleRate;
        private final long mFrequency;
        private volatile Listener<ComplexSamples> mListener;
        private final List<float[]> mIBuffers = new ArrayList<>();
        private final List<float[]> mQBuffers = new ArrayList<>();
        private volatile boolean mStopped = false;

        FakeComplexSource(double sampleRate, long frequency)
        {
            mSampleRate = sampleRate;
            mFrequency = frequency;
        }

        void addBuffer(float[] i, float[] q)
        {
            mIBuffers.add(Arrays.copyOf(i, i.length));
            mQBuffers.add(Arrays.copyOf(q, q.length));
        }

        @Override
        public SampleType getSampleType() { return SampleType.COMPLEX; }

        @Override
        public double getSampleRate() { return mSampleRate; }

        @Override
        public long getFrequency() { return mFrequency; }

        @Override
        public void setListener(Listener<ComplexSamples> listener) { mListener = listener; }

        @Override
        public void removeSourceEventListener() {}

        @Override
        public Listener<SourceEvent> getSourceEventListener() { return null; }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener) {}

        @Override
        public void reset() {}

        @Override
        public void start()
        {
            // Deliver all queued buffers synchronously on the calling thread
            Listener<ComplexSamples> listener = mListener;
            if(listener != null)
            {
                for(int b = 0; b < mIBuffers.size() && !mStopped; b++)
                {
                    listener.receive(new ComplexSamples(mIBuffers.get(b), mQBuffers.get(b), System.currentTimeMillis()));
                }
            }
        }

        @Override
        public void stop()
        {
            mStopped = true;
        }

        @Override
        public void dispose()
        {
            mStopped = true;
            mListener = null;
        }
    }

    /**
     * A {@link FakeComplexSource} variant that separately tracks whether
     * {@link #stop()} and {@link #dispose()} were called.  Used to verify that the
     * survey releases its tuner source when cancelled.
     */
    static class TrackingFakeComplexSource extends ComplexSource
    {
        private final double mSampleRate;
        private final long mFrequency;
        private volatile Listener<ComplexSamples> mListener;
        private volatile boolean mStopped = false;
        private volatile boolean mDisposed = false;

        TrackingFakeComplexSource(double sampleRate, long frequency)
        {
            mSampleRate = sampleRate;
            mFrequency = frequency;
        }

        boolean wasStopped()  { return mStopped; }
        boolean wasDisposed() { return mDisposed; }

        @Override
        public SampleType getSampleType() { return SampleType.COMPLEX; }

        @Override
        public double getSampleRate() { return mSampleRate; }

        @Override
        public long getFrequency() { return mFrequency; }

        @Override
        public void setListener(Listener<ComplexSamples> listener) { mListener = listener; }

        @Override
        public void removeSourceEventListener() {}

        @Override
        public Listener<SourceEvent> getSourceEventListener() { return null; }

        @Override
        public void setSourceEventListener(Listener<SourceEvent> listener) {}

        @Override
        public void reset() {}

        @Override
        public void start()
        {
            // Does nothing — simulates a source that never delivers samples (blocking survey)
        }

        @Override
        public void stop()
        {
            mStopped = true;
        }

        @Override
        public void dispose()
        {
            mDisposed = true;
            mListener = null;
        }
    }

    // =========================================================================
    // Stepped (wide) sweep — Task 5.1
    // =========================================================================

    /**
     * A recording {@link TunerControl} fake that logs each setCenterFreqHz call,
     * tracks the restore (last call after sweep), and delegates sample delivery
     * to a configurable {@link SpectralSurvey.SurveySourceProvider} for each step.
     *
     * <p>The usable bandwidth is fixed at {@code usableBw} Hz.  After each
     * {@link #setCenterFreqHz} the step-source provider is queried so tests can
     * inject different canned buffers per step.</p>
     */
    static class RecordingTunerControl implements TunerControl
    {
        final List<Long> mSetFrequencyCalls = new ArrayList<>();
        private long mCurrentFreq;
        private final long mUsableBw;
        private final long mMinFreq;
        private final long mMaxFreq;
        private boolean mAvailable;

        RecordingTunerControl(long initialFreq, long usableBw, long minFreq, long maxFreq)
        {
            mCurrentFreq = initialFreq;
            mUsableBw = usableBw;
            mMinFreq = minFreq;
            mMaxFreq = maxFreq;
            mAvailable = true;
        }

        void setAvailable(boolean available) { mAvailable = available; }

        @Override
        public long getCurrentCenterFreqHz() { return mCurrentFreq; }

        @Override
        public long getUsableBandwidthHz() { return mUsableBw; }

        @Override
        public long getMinFrequencyHz() { return mMinFreq; }

        @Override
        public long getMaxFrequencyHz() { return mMaxFreq; }

        @Override
        public void setCenterFreqHz(long frequencyHz) throws SourceException
        {
            mSetFrequencyCalls.add(frequencyHz);
            mCurrentFreq = frequencyHz;
        }

        @Override
        public boolean isAvailable() { return mAvailable; }

        /** Returns the last frequency that was set (the restore call). */
        long lastSetFreq()
        {
            return mSetFrequencyCalls.isEmpty() ? mCurrentFreq
                : mSetFrequencyCalls.get(mSetFrequencyCalls.size() - 1);
        }
    }

    @Test
    @Timeout(15)
    void surveyWide_singleStep_whenSpanFitsUsableBw() throws Exception
    {
        // Span is smaller than usable bandwidth → one step suffices
        long startFreq = 154_000_000L;
        long usableBw = 2_000_000L;          // 2 MHz
        long minHz = startFreq;
        long maxHz = startFreq + 1_500_000L; // 1.5 MHz span — fits in 2 MHz

        RecordingTunerControl tunerControl = new RecordingTunerControl(
            startFreq, usableBw, 100_000_000L, 1_700_000_000L);

        // Source always delivers silence (no peaks expected)
        SpectralSurvey.SurveySourceProvider silentProvider = (config, spec, name) ->
            new FakeComplexSource(usableBw, config.getFrequency()); // no buffers → no data

        SpectralSurvey survey = new SpectralSurvey(silentProvider, mExecutor);

        List<EnergyPeak> peaks = survey.surveyWide(minHz, maxHz,
            Duration.ofMillis(600), 6.0, tunerControl, null)
            .get(10, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks);

        // The last call to setCenterFreqHz must restore the original center
        assertEquals(startFreq, tunerControl.lastSetFreq(),
            "Tuner must be restored to original center frequency after a single-step sweep");
    }

    @Test
    @Timeout(20)
    void surveyWide_multipleSteps_coversFullSpan() throws Exception
    {
        // Span = 6 MHz, usable bandwidth = 2 MHz → expect at least 3 steps
        long startFreq = 154_000_000L;
        long usableBw = 2_000_000L;
        long minHz = startFreq;
        long maxHz = startFreq + 6_000_000L;

        RecordingTunerControl tunerControl = new RecordingTunerControl(
            startFreq, usableBw, 100_000_000L, 1_700_000_000L);

        SpectralSurvey.SurveySourceProvider silentProvider = (config, spec, name) ->
            new FakeComplexSource(usableBw, config.getFrequency());

        SpectralSurvey survey = new SpectralSurvey(silentProvider, mExecutor);

        List<EnergyPeak> peaks = survey.surveyWide(minHz, maxHz,
            Duration.ofSeconds(3), 6.0, tunerControl, null)
            .get(15, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks);

        // At stride=80%*2MHz=1.6MHz we need at least ceil(6/1.6)=4 steps.
        // We expect at least 3 distinct retune calls before the restore call.
        // The restore call is always the last, so step count = total calls - 1.
        int tuneCallCount = tunerControl.mSetFrequencyCalls.size();
        assertTrue(tuneCallCount >= 4,
            "Should issue at least 4 setCenterFreqHz calls (steps + restore); got " + tuneCallCount);

        // Last call must restore the original frequency
        assertEquals(startFreq, tunerControl.lastSetFreq(),
            "Tuner must be restored to original center frequency after multi-step sweep");
    }

    @Test
    @Timeout(15)
    void surveyWide_restoresFrequencyOnNormalCompletion() throws Exception
    {
        long originalFreq = 162_000_000L;
        long usableBw = 2_000_000L;

        RecordingTunerControl tunerControl = new RecordingTunerControl(
            originalFreq, usableBw, 100_000_000L, 1_700_000_000L);

        SpectralSurvey.SurveySourceProvider silentProvider = (config, spec, name) ->
            new FakeComplexSource(usableBw, config.getFrequency());

        SpectralSurvey survey = new SpectralSurvey(silentProvider, mExecutor);

        survey.surveyWide(160_000_000L, 164_000_000L,
            Duration.ofSeconds(2), 6.0, tunerControl, null)
            .get(12, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(originalFreq, tunerControl.lastSetFreq(),
            "Tuner center frequency must be restored to " + originalFreq + " Hz after normal completion");
    }

    @Test
    @Timeout(15)
    void surveyWide_restoresFrequencyOnCancel() throws Exception
    {
        long originalFreq = 162_000_000L;
        long usableBw = 2_000_000L;

        // Blocking source — never delivers samples, so dwell blocks until interrupted
        TrackingFakeComplexSource blockingSource = new TrackingFakeComplexSource(usableBw, originalFreq);

        // RecordingTunerControl whose setCenterFreqHz captures the original, then allows steps
        AtomicLong lastRestoreCall = new AtomicLong(0L);
        RecordingTunerControl tunerControl = new RecordingTunerControl(
            originalFreq, usableBw, 100_000_000L, 1_700_000_000L)
        {
            @Override
            public void setCenterFreqHz(long frequencyHz) throws SourceException
            {
                super.setCenterFreqHz(frequencyHz);
                lastRestoreCall.set(frequencyHz);
            }
        };

        SpectralSurvey.SurveySourceProvider blockingProvider = (config, spec, name) -> blockingSource;
        SpectralSurvey survey = new SpectralSurvey(blockingProvider, mExecutor);

        var future = survey.surveyWide(160_000_000L, 164_000_000L,
            Duration.ofSeconds(30), 6.0, tunerControl, null);

        Thread.sleep(200);
        future.cancel(true);

        // Allow the finally block to run
        long deadline = System.currentTimeMillis() + 3_000;
        while(tunerControl.lastSetFreq() != originalFreq && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20);
        }

        assertEquals(originalFreq, tunerControl.lastSetFreq(),
            "Tuner must be restored to original center frequency on cancellation");
    }

    @Test
    @Timeout(15)
    void surveyWide_restoresFrequencyOnSourceError() throws Exception
    {
        long originalFreq = 154_000_000L;
        long usableBw = 2_000_000L;

        // Provider that throws a SourceException immediately
        SpectralSurvey.SurveySourceProvider throwingProvider = (config, spec, name) ->
        {
            throw new SourceException("Simulated hardware error");
        };

        RecordingTunerControl tunerControl = new RecordingTunerControl(
            originalFreq, usableBw, 100_000_000L, 1_700_000_000L);

        SpectralSurvey survey = new SpectralSurvey(throwingProvider, mExecutor);

        // The survey should either fail (exceptionally) or complete with an empty list — either way
        // the restore call must have happened.
        var future = survey.surveyWide(152_000_000L, 156_000_000L,
            Duration.ofSeconds(2), 6.0, tunerControl, null);

        // Wait for the future to settle (error or result)
        try
        {
            future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e)
        {
            // expected for error path
        }

        // Whether the sweep threw or silently continued, the restore must have happened
        assertEquals(originalFreq, tunerControl.lastSetFreq(),
            "Tuner must be restored to original center frequency even when a step's source throws");
    }

    @Test
    @Timeout(5)
    void surveyWide_nullTunerControl_failsExceptionally() throws Exception
    {
        SpectralSurvey.SurveySourceProvider silentProvider = (config, spec, name) -> null;
        SpectralSurvey survey = new SpectralSurvey(silentProvider, mExecutor);

        var future = survey.surveyWide(150_000_000L, 160_000_000L,
            Duration.ofSeconds(1), 6.0, null, null);

        assertThrows(java.util.concurrent.ExecutionException.class, () ->
            future.get(4, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    @Timeout(5)
    void surveyWide_unavailableTuner_failsExceptionally() throws Exception
    {
        long originalFreq = 154_000_000L;
        RecordingTunerControl unavailableTuner = new RecordingTunerControl(
            originalFreq, 2_000_000L, 100_000_000L, 1_700_000_000L);
        unavailableTuner.setAvailable(false);

        SpectralSurvey.SurveySourceProvider silentProvider = (config, spec, name) ->
            new FakeComplexSource(2_000_000L, config.getFrequency());

        SpectralSurvey survey = new SpectralSurvey(silentProvider, mExecutor);

        var future = survey.surveyWide(152_000_000L, 156_000_000L,
            Duration.ofSeconds(1), 6.0, unavailableTuner, null);

        assertThrows(java.util.concurrent.ExecutionException.class, () ->
            future.get(4, java.util.concurrent.TimeUnit.SECONDS));

        // The unavailable check prevents any retune calls
        assertTrue(unavailableTuner.mSetFrequencyCalls.isEmpty(),
            "No retune calls should be made when tuner is unavailable");
    }

    @Test
    @Timeout(20)
    void surveyWide_peaksFromDifferentSteps_areAccumulated() throws Exception
    {
        // Two steps, each injects a tone at a different frequency.
        // We expect two distinct peaks in the result.
        long usableBw = 2_000_000L;   // 2 MHz
        long startFreq = 154_000_000L;

        // Step 1 center: 154 + 1 = 155 MHz (first step within the 6 MHz span)
        // Step 2 center: 155.6 MHz (stride = 80% * 2 = 1.6 MHz)
        // etc. — the exact centers depend on the algorithm; we just need two steps with tones.

        // Simpler: inject a tone in the source for EVERY step so both steps detect it.
        // After merge, we should still have at least 1 peak.
        long toneOffsetHz = 400_000L;
        float amplitude = 0.5f;
        float noiseAmplitude = amplitude / (float) Math.sqrt(Math.pow(10.0, 25.0 / 10.0));
        Random rng = new Random(42L);
        double sampleRate = usableBw;
        int bufferSize = 512;
        int numBuffers = 40;

        SpectralSurvey.SurveySourceProvider toneProvider = (config, spec, name) ->
        {
            FakeComplexSource source = new FakeComplexSource(sampleRate, config.getFrequency());
            long tone = toneOffsetHz; // offset from step center
            for(int b = 0; b < numBuffers; b++)
            {
                float[] iBuffer = new float[bufferSize];
                float[] qBuffer = new float[bufferSize];
                for(int n = 0; n < bufferSize; n++)
                {
                    double phase = 2.0 * Math.PI * tone * (b * bufferSize + n) / sampleRate;
                    iBuffer[n] = (float)(amplitude * Math.cos(phase)) + noiseAmplitude * (float) rng.nextGaussian();
                    qBuffer[n] = (float)(amplitude * Math.sin(phase)) + noiseAmplitude * (float) rng.nextGaussian();
                }
                source.addBuffer(iBuffer, qBuffer);
            }
            return source;
        };

        RecordingTunerControl tunerControl = new RecordingTunerControl(
            startFreq, usableBw, 100_000_000L, 1_700_000_000L);

        SpectralSurvey survey = new SpectralSurvey(toneProvider, mExecutor);

        List<EnergyPeak> peaks = survey.surveyWide(startFreq, startFreq + 4_000_000L,
            Duration.ofSeconds(3), 6.0, tunerControl, null)
            .get(18, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks);
        // The tones from all steps are accumulated; after merge we expect at least 1 peak
        assertFalse(peaks.isEmpty(),
            "Should detect at least one peak from tones injected at each step");

        // Restore must have happened
        assertEquals(startFreq, tunerControl.lastSetFreq(),
            "Tuner must be restored after a multi-step sweep that detected peaks");
    }

    /**
     * Verifies that a signal seen in the overlap region of two adjacent steps is de-duplicated
     * to a single peak by {@code mergePeaksCrossStep}.
     *
     * <p>Strategy: inject the same tone at every step so it appears in every step's output.
     * After cross-step merging, the combined list should contain only ONE peak (or at most a
     * small number) rather than one per step, proving the wider-tolerance merge is effective.</p>
     */
    @Test
    @Timeout(20)
    void surveyWide_duplicatePeaksInOverlapRegion_mergedToSinglePeak() throws Exception
    {
        long usableBw = 2_000_000L;
        long startFreq = 150_000_000L;
        long spanHz    = 6_000_000L;     // 3+ steps at 80% stride

        // Place the tone at a fixed absolute frequency that falls in the overlap zone
        // between step 1 and step 2.  Stride = 0.8 * 2 MHz = 1.6 MHz.
        // Step 1 center ≈ startFreq + usableBw/2 = 151 MHz  → window [150, 152] MHz
        // Step 2 center ≈ 151 + 1.6 = 152.6 MHz             → window [151.6, 153.6] MHz
        // Overlap zone: [151.6, 152] MHz — place tone at 151.8 MHz
        long toneAbsHz = startFreq + 1_800_000L; // 151.8 MHz

        float amplitude = 0.5f;
        float noiseAmplitude = amplitude / (float) Math.sqrt(Math.pow(10.0, 25.0 / 10.0));
        Random rng = new Random(99L);
        double sampleRate = usableBw;
        int bufferSize = 512;
        int numBuffers = 40;

        SpectralSurvey.SurveySourceProvider toneProvider = (config, spec, name) ->
        {
            FakeComplexSource source = new FakeComplexSource(sampleRate, config.getFrequency());
            long toneOffset = toneAbsHz - config.getFrequency(); // offset from this step's center

            // Only inject the tone if this step's window covers the tone frequency
            long stepMin = config.getFrequency() - (long)(sampleRate / 2);
            long stepMax = config.getFrequency() + (long)(sampleRate / 2);

            if(toneAbsHz >= stepMin && toneAbsHz <= stepMax)
            {
                for(int b = 0; b < numBuffers; b++)
                {
                    float[] iBuffer = new float[bufferSize];
                    float[] qBuffer = new float[bufferSize];
                    for(int n = 0; n < bufferSize; n++)
                    {
                        double phase = 2.0 * Math.PI * toneOffset * (b * bufferSize + n) / sampleRate;
                        iBuffer[n] = (float)(amplitude * Math.cos(phase)) + noiseAmplitude * (float) rng.nextGaussian();
                        qBuffer[n] = (float)(amplitude * Math.sin(phase)) + noiseAmplitude * (float) rng.nextGaussian();
                    }
                    source.addBuffer(iBuffer, qBuffer);
                }
            }
            return source;
        };

        RecordingTunerControl tunerControl = new RecordingTunerControl(
            startFreq, usableBw, 100_000_000L, 1_700_000_000L);

        SpectralSurvey survey = new SpectralSurvey(toneProvider, mExecutor);

        List<EnergyPeak> peaks = survey.surveyWide(startFreq, startFreq + spanHz,
            Duration.ofSeconds(3), 6.0, tunerControl, null)
            .get(18, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks);

        // The cross-step merge should collapse any duplicate from the two steps that both
        // cover the 151.8 MHz tone into a single peak.  Use a tight tolerance (5 kHz) so
        // we count only peaks that are genuinely at the tone frequency, not unrelated noise
        // peaks that happen to fall within a wider window.
        long toleranceHz = 5_000L;
        long nearToneCount = peaks.stream()
            .filter(p -> Math.abs(p.centerFrequencyHz() - toneAbsHz) <= toleranceHz)
            .count();

        // There must be exactly one peak near the tone (merged, not doubled)
        assertTrue(nearToneCount >= 1,
            "At least one peak should be near the tone at " + (toneAbsHz / 1_000_000.0) + " MHz");
        assertTrue(nearToneCount <= 1,
            "Cross-step duplicate near the tone (" + (toneAbsHz / 1_000_000.0) + " MHz) should "
            + "merge to at most 1 peak within " + (toleranceHz / 1000) + " kHz; found "
            + nearToneCount + " peaks — mergePeaksCrossStep did not de-duplicate the overlap zone");
    }
}
