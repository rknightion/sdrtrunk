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

import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.dsp.window.WindowType;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.tuner.channel.ChannelSpecification;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Future;
import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spectral survey that finds energy peaks in a frequency span.
 *
 * <h3>Peak finding algorithm</h3>
 * <ol>
 *   <li>Estimate noise floor as the median of the bottom 30% of bins (low-percentile).
 *       Sorting just the lower percentile avoids the median being skewed by strong signals.</li>
 *   <li>Find contiguous runs of bins that exceed {@code floor + thresholdDb}.</li>
 *   <li>Map each run to an {@link EnergyPeak}: center = power-weighted centroid of the run,
 *       width = run span widened by a ±1-bin guard, power = peak bin value, snr = peak − floor.</li>
 *   <li>Merge adjacent peaks whose centers are closer than {@code MIN_SEPARATION_BINS} bins.</li>
 * </ol>
 *
 * <h3>In-band survey mode</h3>
 * <p>{@link #survey(long, long, Duration, double, ProgressListener)} acquires its own dedicated
 * {@link ComplexSource} wide enough to cover the requested span (using the
 * {@link SurveySourceProvider} seam), feeds I/Q buffers through a Hann-windowed FFT accumulator,
 * averages magnitude frames over the dwell period, then calls {@link #findPeaks} to produce the
 * result list.</p>
 *
 * <h3>Wide-span handling</h3>
 * <p>If the requested span exceeds what a single tuner channel can cover (i.e.
 * {@link SurveySourceProvider#acquire} returns {@code null}), the survey returns a failed future
 * with a descriptive message. A true stepped-sweep (Phase 5) is required for spans wider than the
 * tuner's instantaneous bandwidth. Callers should catch the failure and surface the message to the
 * operator.</p>
 *
 * <h3>Testability seam</h3>
 * <p>The {@link SurveySourceProvider} functional interface is the injection point. In production
 * the constructor binds {@code TunerManager.getSource}; tests inject a fake that delivers canned
 * {@link ComplexSamples} buffers without a real tuner.</p>
 */
public class SpectralSurvey implements SpectralSurveyApi
{
    private static final Logger mLog = LoggerFactory.getLogger(SpectralSurvey.class);

    /** FFT size for the survey. Power of two; 4096 gives ~0.5 kHz resolution at 2 Msps. */
    private static final int FFT_SIZE = 4096;

    /**
     * Window type for spectral analysis. Hann window gives a good balance of frequency
     * resolution and side-lobe suppression for peak detection.
     */
    private static final WindowType WINDOW_TYPE = WindowType.HANN;

    /**
     * Minimum bin separation for distinct peaks. Two runs whose centers are within this
     * many bins will be merged into one peak.
     */
    private static final int MIN_SEPARATION_BINS = 3;

    /**
     * Guard bins added on each side of a detected run before computing the peak's
     * occupied bandwidth. Accounts for spectral leakage from sharp signals.
     */
    private static final int GUARD_BINS = 1;

    /**
     * The percentile of bins used to estimate the noise floor (0.30 = lowest 30%).
     * Taking a low percentile avoids the presence of strong signals pulling the median up.
     */
    private static final double NOISE_FLOOR_PERCENTILE = 0.30;

    private final SurveySourceProvider mSourceProvider;
    private final ExecutorService mExecutor;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Constructs a SpectralSurvey with a custom source provider seam (for testing).
     *
     * @param sourceProvider seam for acquiring a wideband complex source
     * @param executor       executor on which survey work runs
     */
    public SpectralSurvey(SurveySourceProvider sourceProvider, ExecutorService executor)
    {
        mSourceProvider = sourceProvider;
        mExecutor = executor;
    }

    // -------------------------------------------------------------------------
    // Pure static peak finder (Task 3.1)
    // -------------------------------------------------------------------------

    /**
     * Finds energy peaks in a float array of magnitude values (in dB).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Estimate noise floor from the lowest {@link #NOISE_FLOOR_PERCENTILE} of bin values.</li>
     *   <li>Find contiguous runs of bins exceeding {@code floor + thresholdDb}.</li>
     *   <li>Convert each run to an {@link EnergyPeak} (power-weighted centroid center, guard-widened
     *       bandwidth, peak bin power, snr = peak − floor).</li>
     *   <li>Merge peaks whose centers are within {@link #MIN_SEPARATION_BINS} bins of each other.</li>
     * </ol>
     *
     * @param magnitudesDb     array of magnitude values in dB, index 0 = {@code baseFrequencyHz},
     *                         index N-1 = {@code baseFrequencyHz + (N-1) * binWidthHz}
     * @param binWidthHz       width of each frequency bin in Hz; must be &gt; 0
     * @param baseFrequencyHz  center frequency of the first bin in Hz; must be &gt; 0
     * @param thresholdDb      minimum dB above estimated noise floor for a bin to be included in a peak run
     * @return unmodifiable list of detected {@link EnergyPeak}s, sorted by center frequency ascending
     */
    public static List<EnergyPeak> findPeaks(float[] magnitudesDb, long binWidthHz,
                                              long baseFrequencyHz, double thresholdDb)
    {
        if(magnitudesDb == null || magnitudesDb.length == 0)
        {
            return Collections.emptyList();
        }

        if(binWidthHz <= 0)
        {
            throw new IllegalArgumentException("binWidthHz must be > 0, got: " + binWidthHz);
        }

        if(baseFrequencyHz <= 0)
        {
            throw new IllegalArgumentException("baseFrequencyHz must be > 0, got: " + baseFrequencyHz);
        }

        // Step 1: estimate noise floor as low-percentile of bins
        double noiseFloor = estimateNoiseFloor(magnitudesDb);

        double detectionThreshold = noiseFloor + thresholdDb;

        // Step 2: find contiguous runs of bins exceeding the detection threshold
        List<int[]> runs = new ArrayList<>(); // each run = {startIdx, endIdx} inclusive
        int runStart = -1;

        for(int i = 0; i < magnitudesDb.length; i++)
        {
            if(magnitudesDb[i] >= detectionThreshold)
            {
                if(runStart < 0)
                {
                    runStart = i;
                }
            }
            else
            {
                if(runStart >= 0)
                {
                    runs.add(new int[]{runStart, i - 1});
                    runStart = -1;
                }
            }
        }

        // Handle a run that extends to the last bin
        if(runStart >= 0)
        {
            runs.add(new int[]{runStart, magnitudesDb.length - 1});
        }

        if(runs.isEmpty())
        {
            return Collections.emptyList();
        }

        // Step 3: convert each run to an EnergyPeak
        List<EnergyPeak> peaks = new ArrayList<>(runs.size());

        for(int[] run : runs)
        {
            EnergyPeak peak = runToPeak(magnitudesDb, run[0], run[1], binWidthHz, baseFrequencyHz, noiseFloor);

            if(peak != null)
            {
                peaks.add(peak);
            }
        }

        // Step 4: merge peaks that are too close together
        peaks = mergePeaks(peaks, binWidthHz);

        return Collections.unmodifiableList(peaks);
    }

    // -------------------------------------------------------------------------
    // Instance method: in-band survey (Task 3.2)
    // -------------------------------------------------------------------------

    /**
     * Performs an in-band spectral survey over the given frequency span.
     *
     * <p>Acquires a dedicated wideband source covering the requested span, accumulates
     * FFT magnitude frames over the dwell period, then calls {@link #findPeaks} to
     * find energy peaks. The source is always stopped and disposed in a {@code finally}
     * block, even on cancellation or error.</p>
     *
     * <p>If the requested span is wider than any single tuner channel the source provider
     * can supply (provider returns {@code null}), the returned future completes exceptionally
     * with a clear message. A Phase-5 stepped sweep is required for such spans.</p>
     *
     * @param minHz       lower bound of the span in Hz (inclusive)
     * @param maxHz       upper bound of the span in Hz (inclusive)
     * @param dwell       how long to accumulate FFT frames
     * @param thresholdDb SNR threshold in dB above the estimated noise floor
     * @param progress    progress listener, called with values 0.0..1.0 during dwell; may be null
     * @return cancellable future resolving to the list of detected peaks (never null, may be empty)
     */
    public CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                                       double thresholdDb, ProgressListener progress)
    {
        if(minHz >= maxHz)
        {
            CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException(
                "minHz (" + minHz + ") must be less than maxHz (" + maxHz + ")"));
            return failed;
        }

        AtomicBoolean cancelledFlag = new AtomicBoolean(false);
        AtomicReference<Future<?>> workerFutureRef = new AtomicReference<>();

        CompletableFuture<List<EnergyPeak>> wrapper = new CompletableFuture<List<EnergyPeak>>()
        {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning)
            {
                cancelledFlag.set(true);
                boolean cancelled = super.cancel(mayInterruptIfRunning);
                Future<?> wf = workerFutureRef.get();

                if(wf != null)
                {
                    wf.cancel(true);
                }

                return cancelled;
            }
        };

        Future<?> workerFuture = mExecutor.submit(() -> {
            try
            {
                List<EnergyPeak> result = doSurvey(minHz, maxHz, dwell, thresholdDb, progress, cancelledFlag);

                if(!cancelledFlag.get())
                {
                    wrapper.complete(result);
                }
            }
            catch(Throwable t)
            {
                if(!wrapper.isCancelled())
                {
                    wrapper.completeExceptionally(t);
                }
            }
        });

        workerFutureRef.set(workerFuture);

        if(wrapper.isCancelled())
        {
            workerFuture.cancel(true);
        }

        return wrapper;
    }

    // -------------------------------------------------------------------------
    // Core survey logic (runs on executor thread)
    // -------------------------------------------------------------------------

    private List<EnergyPeak> doSurvey(long minHz, long maxHz, Duration dwell, double thresholdDb,
                                       ProgressListener progress, AtomicBoolean cancelledFlag)
    {
        long spanHz = maxHz - minHz;
        long centerHz = minHz + spanHz / 2;

        // Use a sample rate ~10% wider than the span so the signal edges don't clip
        // the channelizer's roll-off region. The minimum acceptable is the span itself.
        double targetSampleRate = spanHz * 1.1;

        // Build source config targeting the span center
        SourceConfigTuner sourceConfig = new SourceConfigTuner();
        sourceConfig.setFrequency(centerHz);

        // Channel specification: sample rate wide enough to cover the span
        // passFreq = 80% of Nyquist (standard alias-free zone), stopFreq = Nyquist
        double nyquist = targetSampleRate / 2.0;
        ChannelSpecification channelSpec = new ChannelSpecification(
            targetSampleRate,
            (int) spanHz,
            nyquist * 0.8,
            nyquist
        );

        ComplexSource source;

        try
        {
            source = mSourceProvider.acquire(sourceConfig, channelSpec, "discovery-survey-" + centerHz);
        }
        catch(SourceException e)
        {
            throw new RuntimeException("Failed to acquire survey source at " + centerHz + " Hz: " + e.getMessage(), e);
        }

        if(source == null)
        {
            // Tuner cannot provide this span — wide-range survey needs a stepped sweep (Phase 5)
            throw new RuntimeException(
                "No tuner capacity for a " + (spanHz / 1_000_000.0) + " MHz span centered at " +
                (centerHz / 1_000_000.0) + " MHz. A stepped sweep (Phase 5) is required for spans " +
                "wider than the tuner's instantaneous bandwidth.");
        }

        try
        {
            return accumulateAndFindPeaks(source, dwell, thresholdDb, progress, cancelledFlag);
        }
        finally
        {
            // Always stop and dispose the source, even on cancellation or error
            try { source.stop(); } catch(Exception ex) { mLog.debug("Error stopping survey source", ex); }
            try { source.dispose(); } catch(Exception ex) { mLog.debug("Error disposing survey source", ex); }
        }
    }

    /**
     * Accumulates FFT magnitude frames over the dwell period then runs peak detection.
     *
     * <p>Uses an inner helper object ({@link SampleAccumulator}) to hold mutable state that
     * needs to be accessible from the sample-listener callback, since lambdas require
     * effectively-final captures.</p>
     */
    private List<EnergyPeak> accumulateAndFindPeaks(ComplexSource source, Duration dwell,
                                                      double thresholdDb, ProgressListener progress,
                                                      AtomicBoolean cancelledFlag)
    {
        double sampleRate = source.getSampleRate();
        long centerFreqHz = source.getFrequency();

        // Pre-compute window coefficients
        float[] window = WindowFactory.getWindow(WINDOW_TYPE, FFT_SIZE);
        FloatFFT_1D fft = new FloatFFT_1D(FFT_SIZE);

        // Mutable accumulator object — captures everything the listener needs to update
        SampleAccumulator accumulator = new SampleAccumulator(window, fft, FFT_SIZE);

        long dwellEndMs = System.currentTimeMillis() + dwell.toMillis();
        long dwellMs = dwell.toMillis();

        // Lock object for coordinating sample delivery and the wait loop
        Object lock = new Object();

        // Register a listener to receive I/Q samples from the source
        source.setListener((ComplexSamples samples) ->
        {
            accumulator.process(samples);

            synchronized(lock)
            {
                lock.notifyAll();
            }
        });

        source.start();

        // Wait for dwell to complete, polling for cancellation
        synchronized(lock)
        {
            while(System.currentTimeMillis() < dwellEndMs && !cancelledFlag.get()
                && !Thread.currentThread().isInterrupted())
            {
                long remaining = dwellEndMs - System.currentTimeMillis();

                if(remaining <= 0)
                {
                    break;
                }

                // Report progress
                if(progress != null)
                {
                    double elapsed = dwellMs - remaining;
                    progress.onProgress(Math.min(1.0, elapsed / dwellMs));
                }

                try
                {
                    lock.wait(Math.min(remaining, 100));
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Unregister listener
        source.setListener(null);

        if(progress != null)
        {
            progress.onProgress(1.0);
        }

        double[] magnitudeAccumulator = accumulator.getMagnitudeAccumulator();

        // Check if we accumulated any data at all
        double maxAccumulated = 0.0;

        for(double v : magnitudeAccumulator)
        {
            if(v > maxAccumulated) maxAccumulated = v;
        }

        if(maxAccumulated == 0.0)
        {
            // No samples received — return empty
            return Collections.emptyList();
        }

        // Convert accumulated magnitude sum to dB
        // Using 20*log10 for amplitude (voltage-like magnitude, not power)
        float[] avgMagnitudesDb = new float[FFT_SIZE];

        for(int n = 0; n < FFT_SIZE; n++)
        {
            double linearVal = magnitudeAccumulator[n];

            if(linearVal > 0.0)
            {
                avgMagnitudesDb[n] = (float)(20.0 * Math.log10(linearVal));
            }
            else
            {
                // Use a very small value for silent bins
                avgMagnitudesDb[n] = -200.0f;
            }
        }

        // The FFT output is in "standard" (not fftshift) order: DC at index 0,
        // positive frequencies up to Nyquist, then negative frequencies.
        // Rearrange to frequency-ascending order (fftshift): negative freqs first, then positive.
        float[] shiftedDb = fftShift(avgMagnitudesDb);

        // Compute bin width: sampleRate / FFT_SIZE
        long binWidthHz = Math.max(1L, (long)(sampleRate / FFT_SIZE));

        // Base frequency (center of the lowest bin after fftshift) = centerFreq - sampleRate/2
        long baseFrequencyHz = centerFreqHz - (long)(sampleRate / 2.0) + binWidthHz / 2;

        // Clamp baseFrequencyHz to be positive (in case the span starts very near DC)
        if(baseFrequencyHz <= 0)
        {
            baseFrequencyHz = binWidthHz;
        }

        return findPeaks(shiftedDb, binWidthHz, baseFrequencyHz, thresholdDb);
    }

    // -------------------------------------------------------------------------
    // SampleAccumulator inner class (avoids lambda capture of mutable locals)
    // -------------------------------------------------------------------------

    /**
     * Holds all mutable state needed by the sample-listener callback so it can be
     * accessed from a lambda without violating the "effectively final" requirement.
     *
     * <p>Maintains a ring buffer of the last {@code fftSize} I/Q samples. When
     * the ring fills for the first time, and each time new samples overwrite the
     * oldest entries, a new overlapping FFT frame is computed and its linear
     * magnitudes are accumulated.</p>
     *
     * <p><b>Not thread-safe</b> — accessed only from the source's delivery thread.</p>
     */
    private static final class SampleAccumulator
    {
        private final float[] mWindow;
        private final FloatFFT_1D mFft;
        private final int mFftSize;
        private final float[] mIRing;
        private final float[] mQRing;
        private final float[] mFftBuffer;
        private final double[] mMagnitudeAccumulator;
        private int mRingPos = 0;
        private boolean mRingFull = false;

        SampleAccumulator(float[] window, FloatFFT_1D fft, int fftSize)
        {
            mWindow = window;
            mFft = fft;
            mFftSize = fftSize;
            mIRing = new float[fftSize];
            mQRing = new float[fftSize];
            mFftBuffer = new float[fftSize * 2];
            mMagnitudeAccumulator = new double[fftSize];
        }

        /** Feeds a {@link ComplexSamples} buffer through the ring → FFT → accumulator pipeline. */
        void process(ComplexSamples samples)
        {
            float[] iArr = samples.i();
            float[] qArr = samples.q();

            for(int k = 0; k < iArr.length; k++)
            {
                mIRing[mRingPos] = iArr[k];
                mQRing[mRingPos] = qArr[k];
                mRingPos++;

                if(mRingPos >= mFftSize)
                {
                    mRingPos = 0;
                    mRingFull = true;
                }
            }

            // Compute one FFT frame if the ring has been filled at least once
            if(mRingFull)
            {
                // Read from mRingPos onward (oldest → newest), wrapping around
                for(int n = 0; n < mFftSize; n++)
                {
                    int idx = (mRingPos + n) % mFftSize;
                    float w = mWindow[n];
                    mFftBuffer[2 * n]     = mIRing[idx] * w;
                    mFftBuffer[2 * n + 1] = mQRing[idx] * w;
                }

                mFft.complexForward(mFftBuffer);

                for(int n = 0; n < mFftSize; n++)
                {
                    double re = mFftBuffer[2 * n];
                    double im = mFftBuffer[2 * n + 1];
                    mMagnitudeAccumulator[n] += Math.sqrt(re * re + im * im);
                }
            }
        }

        /** Returns the accumulated magnitude sums (one entry per FFT bin). */
        double[] getMagnitudeAccumulator()
        {
            return mMagnitudeAccumulator;
        }
    }

    // -------------------------------------------------------------------------
    // Private static helpers
    // -------------------------------------------------------------------------

    /**
     * Estimates the noise floor as the mean of the lowest percentile of bin values.
     *
     * @param magnitudesDb the input bin magnitudes
     * @return estimated noise floor in dB
     */
    static double estimateNoiseFloor(float[] magnitudesDb)
    {
        if(magnitudesDb.length == 0)
        {
            return 0.0;
        }

        // Sort a copy to find the low-percentile values
        float[] sorted = Arrays.copyOf(magnitudesDb, magnitudesDb.length);
        Arrays.sort(sorted);

        int percentileBins = Math.max(1, (int)(sorted.length * NOISE_FLOOR_PERCENTILE));
        double sum = 0.0;

        for(int i = 0; i < percentileBins; i++)
        {
            sum += sorted[i];
        }

        return sum / percentileBins;
    }

    /**
     * Converts a contiguous run of above-threshold bins to an {@link EnergyPeak}.
     *
     * <p>Center frequency is computed as the power-weighted centroid of the run.
     * Bandwidth is the run span widened by {@link #GUARD_BINS} on each side, converted to Hz.
     * Power is the maximum bin value in the run. SNR is peak − noiseFloor.</p>
     *
     * @param magnitudesDb  the full magnitude array
     * @param startIdx      inclusive start of the run
     * @param endIdx        inclusive end of the run
     * @param binWidthHz    Hz per bin
     * @param baseFreqHz    center frequency of bin 0
     * @param noiseFloor    estimated noise floor in dB
     * @return the corresponding {@link EnergyPeak}, or {@code null} if the run is degenerate
     */
    private static EnergyPeak runToPeak(float[] magnitudesDb, int startIdx, int endIdx,
                                         long binWidthHz, long baseFreqHz, double noiseFloor)
    {
        // Power-weighted centroid for center frequency
        double weightedBinSum = 0.0;
        double totalWeight = 0.0;
        double peakDb = -Double.MAX_VALUE;

        for(int i = startIdx; i <= endIdx; i++)
        {
            // Use linear power (not dB) for the centroid weighting
            double linearPower = Math.pow(10.0, magnitudesDb[i] / 10.0);
            double binCenterHz = baseFreqHz + (long) i * binWidthHz;
            weightedBinSum += binCenterHz * linearPower;
            totalWeight += linearPower;

            if(magnitudesDb[i] > peakDb)
            {
                peakDb = magnitudesDb[i];
            }
        }

        if(totalWeight <= 0.0)
        {
            return null;
        }

        long centerHz = (long)(weightedBinSum / totalWeight);

        if(centerHz <= 0)
        {
            return null;
        }

        // Bandwidth: run span + guard bins, converted to Hz, minimum 1 bin
        int guardedStart = Math.max(0, startIdx - GUARD_BINS);
        int guardedEnd   = Math.min(magnitudesDb.length - 1, endIdx + GUARD_BINS);
        int runBins = (guardedEnd - guardedStart) + 1;
        int bandwidthHz = (int) Math.max(1L, (long) runBins * binWidthHz);

        double snr = peakDb - noiseFloor;

        return new EnergyPeak(centerHz, bandwidthHz, peakDb, snr);
    }

    /**
     * Merges peaks whose center frequencies are within {@link #MIN_SEPARATION_BINS} bins of each other.
     *
     * <p>When two peaks are merged, the result has:
     * <ul>
     *   <li>center = power-weighted mean of the two centers</li>
     *   <li>bandwidth = span from the lower edge to the upper edge of both peaks</li>
     *   <li>power = the higher of the two peak powers</li>
     *   <li>snr = the higher of the two SNRs</li>
     * </ul>
     *
     * <p>Merge is applied iteratively until no two consecutive peaks are within the separation limit.
     *
     * @param peaks      input list of peaks (will be sorted by center frequency in-place if needed)
     * @param binWidthHz Hz per FFT bin (used to convert separation to Hz)
     * @return merged peak list, sorted by center frequency ascending
     */
    private static List<EnergyPeak> mergePeaks(List<EnergyPeak> peaks, long binWidthHz)
    {
        if(peaks.size() <= 1)
        {
            return peaks;
        }

        // Sort by center frequency ascending
        List<EnergyPeak> sorted = new ArrayList<>(peaks);
        sorted.sort((a, b) -> Long.compare(a.centerFrequencyHz(), b.centerFrequencyHz()));

        long minSeparationHz = MIN_SEPARATION_BINS * binWidthHz;
        boolean merged;

        do
        {
            merged = false;
            List<EnergyPeak> result = new ArrayList<>(sorted.size());
            int i = 0;

            while(i < sorted.size())
            {
                if(i + 1 < sorted.size())
                {
                    EnergyPeak a = sorted.get(i);
                    EnergyPeak b = sorted.get(i + 1);
                    long separation = b.centerFrequencyHz() - a.centerFrequencyHz();

                    if(separation < minSeparationHz)
                    {
                        // Merge a and b: power-weighted center, combined span, max power/snr
                        double linearA = Math.pow(10.0, a.powerDb() / 10.0);
                        double linearB = Math.pow(10.0, b.powerDb() / 10.0);
                        double totalPower = linearA + linearB;
                        long mergedCenter = (totalPower > 0.0)
                            ? (long)((a.centerFrequencyHz() * linearA + b.centerFrequencyHz() * linearB) / totalPower)
                            : (a.centerFrequencyHz() + b.centerFrequencyHz()) / 2;

                        // Span covers both peaks' full extent
                        long aMin = a.centerFrequencyHz() - a.occupiedBandwidthHz() / 2L;
                        long aMax = a.centerFrequencyHz() + a.occupiedBandwidthHz() / 2L;
                        long bMin = b.centerFrequencyHz() - b.occupiedBandwidthHz() / 2L;
                        long bMax = b.centerFrequencyHz() + b.occupiedBandwidthHz() / 2L;
                        int mergedBw = (int)(Math.max(aMax, bMax) - Math.min(aMin, bMin));
                        mergedBw = Math.max(mergedBw, 1);

                        double mergedPower = Math.max(a.powerDb(), b.powerDb());
                        double mergedSnr   = Math.max(a.snrDb(), b.snrDb());

                        result.add(new EnergyPeak(mergedCenter, mergedBw, mergedPower, mergedSnr));
                        i += 2; // skip both merged peaks
                        merged = true;
                        continue;
                    }
                }

                result.add(sorted.get(i));
                i++;
            }

            sorted = result;
        }
        while(merged);

        return sorted;
    }

    /**
     * Applies an FFT shift: moves the second half (negative frequencies) to the front
     * so that the output is in ascending frequency order (lowest frequency first).
     *
     * @param data the FFT output in standard order (DC at index 0)
     * @return a new array in frequency-ascending order
     */
    private static float[] fftShift(float[] data)
    {
        int n = data.length;
        int half = n / 2;
        float[] shifted = new float[n];
        System.arraycopy(data, half, shifted, 0, n - half);    // negative freqs → front
        System.arraycopy(data, 0, shifted, n - half, half);    // positive freqs → back
        return shifted;
    }

    // -------------------------------------------------------------------------
    // Nested types
    // -------------------------------------------------------------------------

    /**
     * Functional interface for receiving progress notifications from a survey.
     * Called on the survey's worker thread.
     */
    @FunctionalInterface
    public interface ProgressListener
    {
        /**
         * Called periodically during a survey.
         *
         * @param fraction the fraction of the dwell elapsed, in [0.0, 1.0]
         */
        void onProgress(double fraction);
    }

    /**
     * Seam for acquiring a wideband {@link ComplexSource} for the survey.
     *
     * <p>In production the binding is:
     * {@code (config, spec, name) -> (ComplexSource) tunerManager.getSource(config, spec, name)}.
     * In tests a fake implementation delivers canned buffers without a real tuner.</p>
     */
    @FunctionalInterface
    public interface SurveySourceProvider
    {
        /**
         * Acquires a complex source for the given configuration.
         *
         * @param config        source configuration specifying the center frequency
         * @param specification channel specification (sample rate, bandwidth, filter params)
         * @param threadName    suggested thread name
         * @return the acquired source, or {@code null} if no tuner can cover the requested span
         * @throws SourceException if a hardware or configuration error prevents acquisition
         */
        ComplexSource acquire(SourceConfigTuner config, ChannelSpecification specification,
                              String threadName) throws SourceException;
    }
}
