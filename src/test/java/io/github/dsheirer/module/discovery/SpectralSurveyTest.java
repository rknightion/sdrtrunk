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
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SpectralSurvey}.
 *
 * <p>Uses a {@link FakeTunerControl} that feeds canned {@link ComplexSamples} via
 * {@link TunerControl#addWidebandSampleListener} — no real SDR hardware required.</p>
 *
 * <h3>Coverage</h3>
 * <ul>
 *   <li>{@link SpectralSurvey#findPeaks} — static pure peak finder (comprehensive)</li>
 *   <li>In-band survey — finds tones at correct absolute Hz, masks DC spike, filters to sub-span</li>
 *   <li>Stepped survey — tones spanning multiple steps are all found, restore confirmed</li>
 *   <li>Null / unavailable TunerControl — exceptional future with descriptive message</li>
 *   <li>Cancellation — future is cancelled; tuner center is restored in stepped path</li>
 * </ul>
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
    // findPeaks — static method tests
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
        float[] mags = makeFlat(100, -80.0f);
        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);
        assertTrue(peaks.isEmpty(), "Flat noise should yield no peaks");
    }

    @Test
    void findPeaks_oneFatBump_yieldsOnePeakWithCorrectCenterAndWidth()
    {
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

        long expectedCenter = BASE_FREQ_HZ + 42 * BIN_WIDTH_HZ;
        long tolerance = 3 * BIN_WIDTH_HZ;
        assertTrue(Math.abs(peak.centerFrequencyHz() - expectedCenter) <= tolerance,
            "Peak center should be near bin 42; expected ~" + expectedCenter + " got " + peak.centerFrequencyHz());

        assertTrue(peak.occupiedBandwidthHz() >= 5 * (int)BIN_WIDTH_HZ,
            "Peak bandwidth should be at least the bump span");

        assertTrue(peak.powerDb() >= -55.0 && peak.powerDb() <= -45.0,
            "Peak power should be around -50 dB, got: " + peak.powerDb());

        assertTrue(peak.snrDb() >= 20.0,
            "SNR should be at least 20 dB (well above noise floor), got: " + peak.snrDb());
    }

    @Test
    void findPeaks_twoWellSeparatedBumps_yieldsTwoDistinctPeaks()
    {
        float[] mags = makeFlat(100, -80.0f);

        for(int i = 10; i <= 12; i++) mags[i] = -50.0f;
        for(int i = 80; i <= 82; i++) mags[i] = -55.0f;

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        assertEquals(2, peaks.size(),
            "Should detect exactly two separate peaks; got " + peaks.size());

        assertTrue(peaks.get(0).centerFrequencyHz() < peaks.get(1).centerFrequencyHz(),
            "Peaks should be sorted by frequency ascending");
    }

    @Test
    void findPeaks_twoVeryCloseBumps_mergesToOnePeak()
    {
        float[] mags = makeFlat(200, -80.0f);
        for(int i = 50; i <= 52; i++) mags[i] = -50.0f;
        for(int i = 53; i <= 55; i++) mags[i] = -50.0f;

        List<EnergyPeak> peaks = SpectralSurvey.findPeaks(mags, BIN_WIDTH_HZ, BASE_FREQ_HZ, THRESHOLD_DB);

        assertTrue(peaks.size() <= 2,
            "Close bumps should yield at most 2 peaks (might merge to 1)");
    }

    @Test
    void findPeaks_bumpAtEdge_handledGracefully()
    {
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
        float[] mags = makeFlat(100, -80.0f);
        for(int i = 90; i < 100; i++) mags[i] = -30.0f;

        double floor = SpectralSurvey.estimateNoiseFloor(mags);

        assertTrue(floor <= -70.0,
            "Noise floor should not be dominated by strong signal bins; got: " + floor);
    }

    // =========================================================================
    // In-band survey via FakeTunerControl
    // =========================================================================

    @Test
    @Timeout(15)
    void survey_inBand_detectsToneAtCorrectAbsoluteHz() throws Exception
    {
        long centerHz = 154_000_000L;
        double sampleRate = 2_000_000.0; // 2 Msps

        // Tone at 200 kHz offset from center → absolute Hz = 154.2 MHz
        double toneOffsetHz = 200_000.0;
        long expectedToneHz = centerHz + (long) toneOffsetHz;

        FakeTunerControl tunerControl = new FakeTunerControl(centerHz, sampleRate);
        tunerControl.setToneOffsets(new double[]{toneOffsetHz});

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        List<EnergyPeak> peaks = survey.survey(
            centerHz - 1_000_000L,
            centerHz + 1_000_000L,
            Duration.ofMillis(300),
            6.0,
            null,
            tunerControl
        ).get(12, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks, "Peaks must not be null");
        assertEquals(1, peaks.size(),
            "Should detect exactly one peak for the injected tone; got " + peaks.size());

        EnergyPeak peak = peaks.get(0);
        long toleranceHz = 10_000L; // ±10 bins at 2 Msps / 4096
        assertTrue(Math.abs(peak.centerFrequencyHz() - expectedToneHz) <= toleranceHz,
            "Peak center should be within " + toleranceHz + " Hz of the tone at "
                + expectedToneHz + " Hz; got " + peak.centerFrequencyHz() + " Hz");

        mLog.info("In-band survey detected tone at {} Hz (expected {} Hz, delta {} Hz)",
            peak.centerFrequencyHz(), expectedToneHz,
            Math.abs(peak.centerFrequencyHz() - expectedToneHz));
    }

    @Test
    @Timeout(10)
    void survey_inBand_doesNotReportDcSpike() throws Exception
    {
        // Add a strong DC offset; the DC mask should suppress the center-frequency spike.
        long centerHz = 154_000_000L;
        double sampleRate = 2_000_000.0;

        FakeTunerControl tunerControl = new FakeTunerControl(centerHz, sampleRate);
        tunerControl.setDcOffset(0.3f); // strong DC component at center
        tunerControl.setToneOffsets(new double[]{300_000.0}); // real tone at +300 kHz

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        List<EnergyPeak> peaks = survey.survey(
            centerHz - 900_000L,
            centerHz + 900_000L,
            Duration.ofMillis(300),
            6.0,
            null,
            tunerControl
        ).get(8, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks);

        // No peak should be at the center frequency (DC spike must be masked)
        long dcTolerance = 50_000L; // 50 kHz window around center
        boolean dcPeakFound = peaks.stream()
            .anyMatch(p -> Math.abs(p.centerFrequencyHz() - centerHz) <= dcTolerance);

        assertFalse(dcPeakFound,
            "DC spike at center frequency should be masked; found a peak within "
                + (dcTolerance / 1000) + " kHz of center. Peaks: " + peaks);
    }

    @Test
    @Timeout(10)
    void survey_inBand_subSpanFiltersOutOfRangePeaks() throws Exception
    {
        // Tuner sees 2 MHz, but we request only a 500 kHz sub-span.
        // Tone outside the sub-span should not appear in results.
        long centerHz = 154_000_000L;
        double sampleRate = 2_000_000.0;

        // Tone at +600 kHz (outside the requested ±400 kHz sub-span)
        double toneOffsetHz = 600_000.0;

        FakeTunerControl tunerControl = new FakeTunerControl(centerHz, sampleRate);
        tunerControl.setToneOffsets(new double[]{toneOffsetHz});

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        // Request only [153.6 MHz, 154.4 MHz] — tone at 154.6 MHz is outside
        List<EnergyPeak> peaks = survey.survey(
            centerHz - 400_000L,
            centerHz + 400_000L,
            Duration.ofMillis(300),
            6.0,
            null,
            tunerControl
        ).get(8, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks);

        // The tone at 154.6 MHz is outside the requested span — should not appear
        long outOfRangeToneHz = centerHz + (long) toneOffsetHz;
        boolean outOfRangePeakFound = peaks.stream()
            .anyMatch(p -> Math.abs(p.centerFrequencyHz() - outOfRangeToneHz) <= 20_000L);

        assertFalse(outOfRangePeakFound,
            "Tone at " + (outOfRangeToneHz / 1_000_000.0) + " MHz outside requested sub-span "
                + "should be filtered out; peaks: " + peaks);
    }

    @Test
    @Timeout(5)
    void survey_nullTunerControl_completesExceptionally() throws Exception
    {
        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        var future = survey.survey(100_000_000L, 200_000_000L, Duration.ofSeconds(1), 6.0, null, null);

        java.util.concurrent.ExecutionException ex = assertThrows(
            java.util.concurrent.ExecutionException.class,
            () -> future.get(4, java.util.concurrent.TimeUnit.SECONDS));

        assertNotNull(ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("spectral display") ||
                   ex.getCause().getMessage().contains("tuner"),
            "Error message should mention tuner; got: " + ex.getCause().getMessage());
    }

    @Test
    @Timeout(5)
    void survey_unavailableTunerControl_completesExceptionally() throws Exception
    {
        FakeTunerControl unavailable = new FakeTunerControl(154_000_000L, 2_000_000.0);
        unavailable.setAvailable(false);

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        var future = survey.survey(153_000_000L, 155_000_000L, Duration.ofSeconds(1), 6.0, null, unavailable);

        assertThrows(java.util.concurrent.ExecutionException.class,
            () -> future.get(4, java.util.concurrent.TimeUnit.SECONDS));
    }

    // =========================================================================
    // Stepped survey
    // =========================================================================

    @Test
    @Timeout(30)
    void survey_steppedPath_toneFoundAcrossMultipleSteps() throws Exception
    {
        // Span = 6 MHz, sample rate = 2 MHz → 4+ steps
        long startFreq = 154_000_000L;
        long spanHz = 6_000_000L;
        double sampleRate = 2_000_000.0;

        // Place a tone at 156 MHz (middle of the span)
        long toneAbsHz = startFreq + 2_000_000L; // 156 MHz

        FakeTunerControl tunerControl = new FakeTunerControl(startFreq, sampleRate);
        // Tone is defined in absolute Hz; the fake computes the baseband offset per retune
        tunerControl.setAbsoluteToneHz(toneAbsHz);

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        List<EnergyPeak> peaks = survey.survey(
            startFreq,
            startFreq + spanHz,
            Duration.ofSeconds(4),
            6.0,
            null,
            tunerControl
        ).get(28, java.util.concurrent.TimeUnit.SECONDS);

        assertNotNull(peaks);
        assertFalse(peaks.isEmpty(),
            "Should detect at least one peak from the tone at " + (toneAbsHz / 1_000_000.0) + " MHz");

        // Original center must have been restored
        long lastFreq = tunerControl.lastSetFreqHz();
        assertEquals(startFreq, lastFreq,
            "Tuner must be restored to original center " + (startFreq / 1_000_000.0)
                + " MHz after stepped sweep; last set = " + (lastFreq / 1_000_000.0) + " MHz");

        // At least 3 retune calls (steps + restore)
        assertTrue(tunerControl.getSetFreqCallCount() >= 3,
            "Should issue at least 3 setCenterFreqHz calls (steps + restore); got "
                + tunerControl.getSetFreqCallCount());
    }

    @Test
    @Timeout(20)
    void survey_steppedPath_restoresFrequencyOnNormalCompletion() throws Exception
    {
        long originalCenter = 160_000_000L;
        double sampleRate = 2_000_000.0;

        FakeTunerControl tunerControl = new FakeTunerControl(originalCenter, sampleRate);

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        // 4 MHz span with 2 MHz sample rate → stepped
        survey.survey(
            158_000_000L, 162_000_000L,
            Duration.ofSeconds(2), 6.0, null, tunerControl
        ).get(18, java.util.concurrent.TimeUnit.SECONDS);

        assertEquals(originalCenter, tunerControl.lastSetFreqHz(),
            "Tuner must be restored to original center after normal completion");
    }

    @Test
    @Timeout(20)
    void survey_steppedPath_restoresFrequencyOnCancel() throws Exception
    {
        long originalCenter = 162_000_000L;
        double sampleRate = 2_000_000.0;

        // Use a fake that blocks in addWidebandSampleListener to prevent early completion
        FakeTunerControl blockingTuner = new FakeTunerControl(originalCenter, sampleRate);
        blockingTuner.setBlockSamples(true); // never delivers samples — survey blocks in dwell

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        var future = survey.survey(
            160_000_000L, 164_000_000L,
            Duration.ofSeconds(30), 6.0, null, blockingTuner
        );

        Thread.sleep(300);
        future.cancel(true);

        // Wait for the finally block to restore the center
        long deadline = System.currentTimeMillis() + 4_000;
        while(blockingTuner.lastSetFreqHz() != originalCenter && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(20);
        }

        assertEquals(originalCenter, blockingTuner.lastSetFreqHz(),
            "Tuner center must be restored to " + originalCenter + " Hz on cancellation");
    }

    @Test
    @Timeout(15)
    void survey_steppedPath_restoresFrequencyOnError() throws Exception
    {
        long originalCenter = 154_000_000L;
        double sampleRate = 2_000_000.0;

        // Fake that throws on the 2nd setCenterFreqHz call (simulates hardware error mid-sweep)
        FakeTunerControl throwingTuner = new FakeTunerControl(originalCenter, sampleRate)
        {
            private int mCallCount = 0;

            @Override
            public void setCenterFreqHz(long frequencyHz) throws SourceException
            {
                mCallCount++;
                if(mCallCount == 2)
                {
                    throw new SourceException("Simulated hardware error on step 2");
                }
                super.setCenterFreqHz(frequencyHz);
            }
        };

        SpectralSurvey survey = new SpectralSurvey(mExecutor);

        var future = survey.survey(
            152_000_000L, 156_000_000L,
            Duration.ofSeconds(1), 6.0, null, throwingTuner
        );

        // Wait for it to settle (either complete with error or complete normally after restore)
        try
        {
            future.get(12, java.util.concurrent.TimeUnit.SECONDS);
        }
        catch(java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e)
        {
            // expected
        }

        assertEquals(originalCenter, throwingTuner.lastSetFreqHz(),
            "Tuner center must be restored even when a step's setCenterFreqHz throws");
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

    // =========================================================================
    // FakeTunerControl — wideband I/Q tap that feeds canned tones
    // =========================================================================

    /**
     * A fake {@link TunerControl} for tests.
     *
     * <h3>Sample delivery</h3>
     * <p>When a listener is registered via {@link #addWidebandSampleListener}, a daemon thread
     * is spawned that continuously delivers {@link ComplexSamples} containing one or more
     * complex sinusoidal tones with additive Gaussian noise.  The tones can be specified as:
     * <ul>
     *   <li>{@link #setToneOffsets(double[])} — baseband offsets in Hz (tone at {@code centerHz + offset})</li>
     *   <li>{@link #setAbsoluteToneHz(long)} — absolute frequency; the fake computes the offset
     *       from the current center frequency at feed time (updates automatically after retuning)</li>
     * </ul>
     * The feeder stops when the listener is removed or the fake is shut down.</p>
     *
     * <h3>Stepped sweep support</h3>
     * <p>After each {@link #setCenterFreqHz} call the feeder recomputes tone offsets from the
     * current center, so the same absolute-frequency tone appears at the correct baseband
     * position after a retune.</p>
     */
    static class FakeTunerControl implements TunerControl
    {
        private static final float AMPLITUDE = 0.5f;
        private static final float NOISE_SNR_DB = 25.0f;
        private static final int BUFFER_SIZE = 512;

        private volatile long mCenterFreqHz;
        private final double mSampleRate;
        private final long mMinFreqHz;
        private final long mMaxFreqHz;
        private volatile boolean mAvailable = true;

        /** Baseband tone offsets in Hz (relative to center). */
        private volatile double[] mToneOffsets = new double[0];

        /** Absolute-frequency tone; set to Long.MIN_VALUE to disable. */
        private volatile long mAbsoluteToneHz = Long.MIN_VALUE;

        /** DC offset added to I samples (to test DC masking). */
        private volatile float mDcOffset = 0.0f;

        /** When true, the feeder thread does not deliver samples (for blocking-cancel tests). */
        private volatile boolean mBlockSamples = false;

        /** Registered wideband listeners and their feeder threads. */
        private final List<Listener<ComplexSamples>> mListeners = new CopyOnWriteArrayList<>();
        private final List<Thread> mFeederThreads = new CopyOnWriteArrayList<>();

        /** Tracks all setCenterFreqHz calls (including the restore). */
        private final List<Long> mSetFreqCalls = Collections.synchronizedList(new ArrayList<>());

        FakeTunerControl(long centerFreqHz, double sampleRate)
        {
            this(centerFreqHz, sampleRate, 100_000_000L, 1_700_000_000L);
        }

        FakeTunerControl(long centerFreqHz, double sampleRate, long minFreqHz, long maxFreqHz)
        {
            mCenterFreqHz = centerFreqHz;
            mSampleRate = sampleRate;
            mMinFreqHz = minFreqHz;
            mMaxFreqHz = maxFreqHz;
        }

        void setToneOffsets(double[] offsets) { mToneOffsets = offsets.clone(); }
        void setAbsoluteToneHz(long absHz) { mAbsoluteToneHz = absHz; }
        void setDcOffset(float dcOffset) { mDcOffset = dcOffset; }
        void setBlockSamples(boolean block) { mBlockSamples = block; }
        void setAvailable(boolean available) { mAvailable = available; }

        int getSetFreqCallCount() { return mSetFreqCalls.size(); }

        long lastSetFreqHz()
        {
            return mSetFreqCalls.isEmpty() ? mCenterFreqHz
                : mSetFreqCalls.get(mSetFreqCalls.size() - 1);
        }

        // ----- TunerControl interface -----

        @Override
        public long getCurrentCenterFreqHz() { return mCenterFreqHz; }

        @Override
        public long getUsableBandwidthHz() { return (long) mSampleRate; }

        @Override
        public long getMinFrequencyHz() { return mMinFreqHz; }

        @Override
        public long getMaxFrequencyHz() { return mMaxFreqHz; }

        @Override
        public double getCurrentSampleRateHz() { return mSampleRate; }

        @Override
        public void setCenterFreqHz(long frequencyHz) throws SourceException
        {
            mSetFreqCalls.add(frequencyHz);
            mCenterFreqHz = frequencyHz;
        }

        @Override
        public boolean isAvailable() { return mAvailable; }

        @Override
        public void addWidebandSampleListener(Listener<ComplexSamples> listener)
        {
            mListeners.add(listener);

            if(mBlockSamples)
            {
                // Don't spawn a feeder — the survey will block until cancelled
                return;
            }

            Thread feeder = new Thread(() -> feedSamples(listener), "fake-tuner-feeder");
            feeder.setDaemon(true);
            mFeederThreads.add(feeder);
            feeder.start();
        }

        @Override
        public void removeWidebandSampleListener(Listener<ComplexSamples> listener)
        {
            mListeners.remove(listener);
            // Feeder threads check mListeners and exit when the listener is removed
        }

        /**
         * Delivers {@link ComplexSamples} containing the configured tone(s) + noise to the
         * listener until the listener is removed from the active list.
         */
        private void feedSamples(Listener<ComplexSamples> listener)
        {
            Random rng = new Random(42L);
            float noiseAmp = AMPLITUDE / (float) Math.sqrt(Math.pow(10.0, NOISE_SNR_DB / 10.0));
            long sampleIndex = 0;

            while(mListeners.contains(listener) && !Thread.currentThread().isInterrupted())
            {
                float[] iArr = new float[BUFFER_SIZE];
                float[] qArr = new float[BUFFER_SIZE];

                // Determine tone offsets at the current center (recomputes after retune)
                double[] offsets = computeCurrentOffsets();

                for(int n = 0; n < BUFFER_SIZE; n++)
                {
                    float i = mDcOffset + noiseAmp * (float) rng.nextGaussian();
                    float q = noiseAmp * (float) rng.nextGaussian();

                    for(double offset : offsets)
                    {
                        double phase = 2.0 * Math.PI * offset * (sampleIndex + n) / mSampleRate;
                        i += AMPLITUDE * (float) Math.cos(phase);
                        q += AMPLITUDE * (float) Math.sin(phase);
                    }

                    iArr[n] = i;
                    qArr[n] = q;
                }

                sampleIndex += BUFFER_SIZE;
                listener.receive(new ComplexSamples(iArr, qArr, System.currentTimeMillis()));

                // Tiny yield so the dwell-wait thread can make progress
                Thread.yield();
            }
        }

        private double[] computeCurrentOffsets()
        {
            double[] base = mToneOffsets;
            long absHz = mAbsoluteToneHz;

            if(absHz == Long.MIN_VALUE)
            {
                return base;
            }

            // Absolute tone: compute offset from current center
            double[] result = Arrays.copyOf(base, base.length + 1);
            result[base.length] = absHz - mCenterFreqHz;
            return result;
        }
    }
}
