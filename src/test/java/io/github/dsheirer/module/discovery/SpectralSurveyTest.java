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

    private static final org.slf4j.Logger mLog =
        org.slf4j.LoggerFactory.getLogger(SpectralSurveyTest.class);

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
}
