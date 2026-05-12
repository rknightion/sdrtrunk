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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spectral survey that finds energy peaks in a frequency span by tapping the tuner's
 * full-rate wideband I/Q buffer directly — the same stream the live spectrum display uses.
 *
 * <h3>Why wideband tap (not channelizer)</h3>
 * <p>The previous implementation used {@code TunerManager.getSource()} which allocates a
 * polyphase-channelizer DDC channel.  For typical 10 MHz tuners (Airspy R2, SDRplay) no
 * channelizer channel wide enough to cover the survey span exists, so the in-band path
 * always failed and the stepped path allocated per-step channels that also didn't exist.
 * Tapping the raw wideband I/Q buffer (via {@link TunerControl#addWidebandSampleListener})
 * bypasses the channelizer entirely and works on any tuner that has a live spectral
 * display running.</p>
 *
 * <h3>In-band path (non-disruptive)</h3>
 * <p>When the requested span fits within the tuner's sample rate, the survey accumulates
 * FFT frames over the dwell period without retuning.  The tuner center is read once at
 * start; peaks are filtered to the requested {@code [minHz, maxHz]} sub-span in case the
 * operator requested a sub-window of the tuner's view.</p>
 *
 * <h3>Stepped path (disruptive)</h3>
 * <p>When the span exceeds the sample rate, the survey steps the tuner's center across
 * the span and collects peaks at each step, then merges cross-step duplicates.  The
 * original center is always restored in a {@code finally} block.</p>
 *
 * <h3>DC mask</h3>
 * <p>The DC component of direct-conversion tuners (and to a lesser degree all SDRs) shows
 * up as a strong spike at the center frequency.  Bins within {@link #DC_MASK_BINS} of the
 * center bin are clamped to −200 dBFS before peak detection so they cannot be reported
 * as "signals".</p>
 *
 * <h3>Peak finding algorithm</h3>
 * <ol>
 *   <li>Estimate noise floor as the median of the bottom 30% of bins (low-percentile).</li>
 *   <li>Find contiguous runs of bins that exceed {@code floor + thresholdDb}.</li>
 *   <li>Map each run to an {@link EnergyPeak}: center = power-weighted centroid of the run,
 *       width = run span widened by a ±1-bin guard, power = peak bin value, snr = peak − floor.</li>
 *   <li>Merge adjacent peaks whose centers are closer than {@link #MIN_SEPARATION_BINS} bins.</li>
 * </ol>
 */
public class SpectralSurvey implements SpectralSurveyApi
{
    private static final Logger mLog = LoggerFactory.getLogger(SpectralSurvey.class);

    /** FFT size for the survey. Power of two; 4096 gives ~2.4 kHz resolution at 10 Msps. */
    private static final int FFT_SIZE = 4096;

    /**
     * Window type for spectral analysis. Hann window gives a good balance of frequency
     * resolution and side-lobe suppression for peak detection.
     */
    private static final WindowType WINDOW_TYPE = WindowType.HANN;

    /**
     * Minimum bin separation for distinct peaks within a single step.
     */
    private static final int MIN_SEPARATION_BINS = 3;

    /**
     * Guard bins added on each side of a detected run before computing the peak's
     * occupied bandwidth. Accounts for spectral leakage from sharp signals.
     */
    private static final int GUARD_BINS = 1;

    /**
     * The percentile of bins used to estimate the noise floor (0.30 = lowest 30%).
     */
    private static final double NOISE_FLOOR_PERCENTILE = 0.30;

    /**
     * Number of bins on each side of the DC bin to clamp to −200 dBFS.
     * This suppresses the DC spike that direct-conversion tuners produce at their center
     * frequency, preventing it from being reported as a signal peak.
     * Total masked bins = 2 * DC_MASK_BINS + 1 = 7.
     */
    private static final int DC_MASK_BINS = 3;

    /**
     * Stride as a fraction of sample rate.  0.85 means adjacent steps overlap by 15%
     * so signals near step edges are always captured by at least one step.
     */
    private static final double STEP_OVERLAP_FACTOR = 0.85;

    /**
     * Settle time in milliseconds after each retune before starting sample capture.
     * Gives the hardware AGC and PLL time to re-lock after a frequency change.
     */
    private static final long SETTLE_MS = 100L;

    /**
     * Minimum per-step dwell in milliseconds.
     */
    private static final long MIN_STEP_DWELL_MS = 500L;

    private final ExecutorService mExecutor;

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    /**
     * Constructs a SpectralSurvey.
     *
     * @param executor executor on which survey work runs
     */
    public SpectralSurvey(ExecutorService executor)
    {
        mExecutor = executor;
    }

    // -------------------------------------------------------------------------
    // SpectralSurveyApi implementation
    // -------------------------------------------------------------------------

    /**
     * Performs a spectral survey over the given frequency span.
     *
     * <p>Internally decides between in-band (non-disruptive, single accumulation at the
     * tuner's current center) and stepped (disruptive, retunes across the span) based on
     * whether {@code span = maxHz - minHz} fits within the tuner's current sample rate.</p>
     *
     * <p>Requires a non-null, available {@link TunerControl}; fails immediately with a
     * descriptive message otherwise.</p>
     *
     * @param minHz         lower bound of the span in Hz (inclusive)
     * @param maxHz         upper bound of the span in Hz (inclusive)
     * @param dwell         how long to accumulate FFT frames (total; divided across steps for swept)
     * @param thresholdDb   SNR threshold in dB above estimated noise floor
     * @param progress      progress listener (0.0..1.0); may be null
     * @param tunerControl  control seam for the active tuner; must be non-null and available
     * @return cancellable future resolving to the list of detected peaks (never null, may be empty)
     */
    @Override
    public CompletableFuture<List<EnergyPeak>> survey(long minHz, long maxHz, Duration dwell,
                                                       double thresholdDb, ProgressListener progress,
                                                       TunerControl tunerControl)
    {
        if(minHz >= maxHz)
        {
            CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException(
                "minHz (" + minHz + ") must be less than maxHz (" + maxHz + ")"));
            return failed;
        }

        if(tunerControl == null || !tunerControl.isAvailable())
        {
            CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException(
                "Band scan requires the spectral display to be showing a tuner."));
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

        Future<?> workerFuture = mExecutor.submit(() ->
        {
            try
            {
                List<EnergyPeak> result = doSurvey(
                    minHz, maxHz, dwell, thresholdDb, progress, tunerControl, cancelledFlag);

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
    // Pure static peak finder
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

        double noiseFloor = estimateNoiseFloor(magnitudesDb);
        double detectionThreshold = noiseFloor + thresholdDb;

        List<int[]> runs = new ArrayList<>();
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

        if(runStart >= 0)
        {
            runs.add(new int[]{runStart, magnitudesDb.length - 1});
        }

        if(runs.isEmpty())
        {
            return Collections.emptyList();
        }

        List<EnergyPeak> peaks = new ArrayList<>(runs.size());

        for(int[] run : runs)
        {
            EnergyPeak peak = runToPeak(magnitudesDb, run[0], run[1], binWidthHz, baseFrequencyHz, noiseFloor);
            if(peak != null)
            {
                peaks.add(peak);
            }
        }

        peaks = mergePeaks(peaks, binWidthHz);

        return Collections.unmodifiableList(peaks);
    }

    // -------------------------------------------------------------------------
    // Core survey logic (runs on executor thread)
    // -------------------------------------------------------------------------

    /**
     * Decides between in-band and stepped paths based on span vs sample rate, then executes.
     */
    private List<EnergyPeak> doSurvey(long minHz, long maxHz, Duration dwell, double thresholdDb,
                                       ProgressListener progress, TunerControl tunerControl,
                                       AtomicBoolean cancelledFlag)
    {
        long span = maxHz - minHz;
        double sampleRate = tunerControl.getCurrentSampleRateHz();
        long usableBandwidth = tunerControl.getUsableBandwidthHz();
        long instantaneousBandwidth = usableBandwidth > 0 ? usableBandwidth : (long)sampleRate;

        if(span <= instantaneousBandwidth)
        {
            // In-band path: the entire requested span fits within the tuner's instantaneous view
            return doInBandSurvey(minHz, maxHz, dwell, thresholdDb, progress, tunerControl, cancelledFlag);
        }
        else
        {
            // Stepped path: the span exceeds the tuner's bandwidth; retune across it
            return doSteppedSurvey(minHz, maxHz, dwell, thresholdDb, progress, tunerControl, cancelledFlag);
        }
    }

    /**
     * In-band survey: accumulates FFT frames over the dwell period without retuning.
     * Uses the tuner's current center; filters results to [minHz, maxHz].
     */
    private List<EnergyPeak> doInBandSurvey(long minHz, long maxHz, Duration dwell, double thresholdDb,
                                              ProgressListener progress, TunerControl tunerControl,
                                              AtomicBoolean cancelledFlag)
    {
        long centerHz = tunerControl.getCurrentCenterFreqHz();
        double sampleRate = tunerControl.getCurrentSampleRateHz();

        SampleAccumulator accumulator = buildAccumulator();

        Listener<ComplexSamples> listener = samples -> accumulator.process(samples);

        tunerControl.addWidebandSampleListener(listener);

        try
        {
            waitForDwell(dwell, progress, cancelledFlag);
        }
        finally
        {
            tunerControl.removeWidebandSampleListener(listener);
        }

        if(!accumulator.hasData())
        {
            return Collections.emptyList();
        }

        List<EnergyPeak> peaks = extractPeaks(accumulator, centerHz, sampleRate, thresholdDb);

        // Filter to requested sub-span
        return filterToSpan(peaks, minHz, maxHz);
    }

    /**
     * Stepped survey: retunes across the span at overlapping steps, collects peaks, restores center.
     */
    private List<EnergyPeak> doSteppedSurvey(long minHz, long maxHz, Duration dwell, double thresholdDb,
                                               ProgressListener progress, TunerControl tunerControl,
                                               AtomicBoolean cancelledFlag)
    {
        long originalCenterHz = tunerControl.getCurrentCenterFreqHz();

        try
        {
            return executeSteps(minHz, maxHz, dwell, thresholdDb, progress, tunerControl, cancelledFlag);
        }
        finally
        {
            try
            {
                tunerControl.setCenterFreqHz(originalCenterHz);
                mLog.debug("Stepped sweep: restored tuner center to {} Hz", originalCenterHz);
            }
            catch(Exception ex)
            {
                mLog.warn("Stepped sweep: failed to restore tuner center to {} Hz: {}",
                    originalCenterHz, ex.getMessage());
            }
        }
    }

    /**
     * Core step loop: computes step centers, retunes, collects peaks per step, merges.
     */
    private List<EnergyPeak> executeSteps(long minHz, long maxHz, Duration dwell, double thresholdDb,
                                           ProgressListener progress, TunerControl tunerControl,
                                           AtomicBoolean cancelledFlag)
    {
        double sampleRate = tunerControl.getCurrentSampleRateHz();
        long sampleRateHz = (long) sampleRate;

        // Stride = sampleRate * STEP_OVERLAP_FACTOR; overlap so edge signals are not missed
        long strideHz = Math.max(1L, (long)(sampleRate * STEP_OVERLAP_FACTOR));

        // Build step centers
        long minTunable = tunerControl.getMinFrequencyHz();
        long maxTunable = tunerControl.getMaxFrequencyHz();

        List<Long> stepCenters = new ArrayList<>();
        long center = minHz + sampleRateHz / 2L;

        while(center - sampleRateHz / 2L < maxHz)
        {
            stepCenters.add(center);
            center += strideHz;
        }

        // Ensure last center's right edge covers maxHz
        if(stepCenters.isEmpty() || stepCenters.get(stepCenters.size() - 1) + sampleRateHz / 2L < maxHz)
        {
            stepCenters.add(maxHz - sampleRateHz / 2L);
        }

        // Clamp to tuner's tunable range
        stepCenters.replaceAll(c -> Math.max(minTunable, Math.min(maxTunable, c)));

        // Warn if clamping truncates the requested span
        long firstWindowLow = stepCenters.get(0) - sampleRateHz / 2L;
        long lastWindowHigh = stepCenters.get(stepCenters.size() - 1) + sampleRateHz / 2L;

        if(firstWindowLow > minHz)
        {
            mLog.warn("Stepped sweep: span truncated at low end — tuner minimum ({} MHz) is above "
                + "requested start ({} MHz); coverage begins at {} MHz",
                minTunable / 1_000_000.0, minHz / 1_000_000.0, firstWindowLow / 1_000_000.0);
        }

        if(lastWindowHigh < maxHz)
        {
            mLog.warn("Stepped sweep: span truncated at high end — tuner maximum ({} MHz) is below "
                + "requested end ({} MHz); coverage ends at {} MHz",
                maxTunable / 1_000_000.0, maxHz / 1_000_000.0, lastWindowHigh / 1_000_000.0);
        }

        // Remove duplicates (can arise when range < sampleRate)
        stepCenters = stepCenters.stream().distinct().toList();

        int stepCount = stepCenters.size();
        mLog.debug("Stepped sweep: {} steps over {} MHz span (stride {} kHz, sampleRate {} MHz)",
            stepCount, (maxHz - minHz) / 1_000_000.0, strideHz / 1_000.0, sampleRate / 1_000_000.0);

        long totalDwellMs = dwell.toMillis();
        long stepDwellMs = Math.max(MIN_STEP_DWELL_MS, stepCount > 0 ? totalDwellMs / stepCount : totalDwellMs);
        Duration stepDwell = Duration.ofMillis(stepDwellMs);

        List<EnergyPeak> allPeaks = new ArrayList<>();

        for(int i = 0; i < stepCount; i++)
        {
            if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
            {
                break;
            }

            long stepCenter = stepCenters.get(i);
            int stepIndex = i;

            mLog.debug("Stepped sweep: step {}/{} center={} MHz", i + 1, stepCount,
                stepCenter / 1_000_000.0);

            try
            {
                tunerControl.setCenterFreqHz(stepCenter);
            }
            catch(Exception ex)
            {
                throw new RuntimeException(
                    "Stepped sweep: failed to retune to " + stepCenter + " Hz at step "
                    + (i + 1) + "/" + stepCount + ": " + ex.getMessage(), ex);
            }

            // Settle
            if(SETTLE_MS > 0 && !cancelledFlag.get())
            {
                try
                {
                    Thread.sleep(SETTLE_MS);
                }
                catch(InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if(cancelledFlag.get() || Thread.currentThread().isInterrupted())
            {
                break;
            }

            // Per-step progress listener mapping this step's 0..1 into the overall range
            final int si = stepIndex;
            ProgressListener stepProgress = null;
            if(progress != null)
            {
                double stepStart = (double) si / stepCount;
                double stepRange = 1.0 / stepCount;
                stepProgress = fraction -> progress.onProgress(stepStart + fraction * stepRange);
            }

            // Accumulate in-band at this step's center
            final ProgressListener stepProgressFinal = stepProgress;
            final long stepCenterFinal = stepCenter;

            SampleAccumulator accumulator = buildAccumulator();
            Listener<ComplexSamples> listener = samples -> accumulator.process(samples);

            tunerControl.addWidebandSampleListener(listener);

            try
            {
                waitForDwell(stepDwell, stepProgressFinal, cancelledFlag);
            }
            finally
            {
                tunerControl.removeWidebandSampleListener(listener);
            }

            if(accumulator.hasData())
            {
                List<EnergyPeak> stepPeaks = extractPeaks(accumulator, stepCenterFinal, sampleRate, thresholdDb);
                // Filter to requested span
                List<EnergyPeak> filtered = filterToSpan(stepPeaks, minHz, maxHz);
                allPeaks.addAll(filtered);
            }
        }

        if(progress != null)
        {
            progress.onProgress(1.0);
        }

        long binWidthHz = Math.max(1L, (long)(sampleRate / FFT_SIZE));
        return Collections.unmodifiableList(mergePeaksCrossStep(allPeaks, binWidthHz));
    }

    // -------------------------------------------------------------------------
    // Shared survey helpers
    // -------------------------------------------------------------------------

    /** Builds a new {@link SampleAccumulator} with the pre-allocated FFT structures. */
    private SampleAccumulator buildAccumulator()
    {
        float[] window = WindowFactory.getWindow(WINDOW_TYPE, FFT_SIZE);
        FloatFFT_1D fft = new FloatFFT_1D(FFT_SIZE);
        return new SampleAccumulator(window, fft, FFT_SIZE);
    }

    /**
     * Waits for the given dwell duration, polling for cancellation every 50 ms and reporting
     * progress to the listener.
     */
    private void waitForDwell(Duration dwell, ProgressListener progress, AtomicBoolean cancelledFlag)
    {
        long dwellMs = dwell.toMillis();
        long dwellEndMs = System.currentTimeMillis() + dwellMs;

        while(System.currentTimeMillis() < dwellEndMs
            && !cancelledFlag.get()
            && !Thread.currentThread().isInterrupted())
        {
            long remaining = dwellEndMs - System.currentTimeMillis();

            if(remaining <= 0)
            {
                break;
            }

            if(progress != null)
            {
                double elapsed = dwellMs - remaining;
                progress.onProgress(Math.min(1.0, elapsed / dwellMs));
            }

            try
            {
                Thread.sleep(Math.min(remaining, 50));
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Extracts peaks from an accumulator, applying fftshift, DC masking, and bin→Hz mapping.
     *
     * @param accumulator  the accumulated FFT data
     * @param centerHz     the tuner center frequency during accumulation
     * @param sampleRate   sample rate in Hz
     * @param thresholdDb  detection threshold
     * @return list of peaks in absolute Hz
     */
    private List<EnergyPeak> extractPeaks(SampleAccumulator accumulator, long centerHz,
                                           double sampleRate, double thresholdDb)
    {
        float[] avgPowerDb = accumulator.getAveragedPowerDb();

        // fftshift: move negative-frequency half to the front so bin 0 = lowest frequency
        float[] shiftedDb = fftShift(avgPowerDb);

        // DC mask: clamp bins around the center (DC bin after shift = FFT_SIZE/2)
        int dcBin = FFT_SIZE / 2;
        int dcStart = Math.max(0, dcBin - DC_MASK_BINS);
        int dcEnd = Math.min(FFT_SIZE - 1, dcBin + DC_MASK_BINS);
        for(int i = dcStart; i <= dcEnd; i++)
        {
            shiftedDb[i] = -200.0f;
        }

        // Bin width and base frequency
        long binWidthHz = Math.max(1L, (long)(sampleRate / FFT_SIZE));

        // After fftshift, bin 0 corresponds to centerHz - sampleRate/2 + binWidth/2
        long baseFrequencyHz = centerHz - (long)(sampleRate / 2.0) + binWidthHz / 2;

        if(baseFrequencyHz <= 0)
        {
            baseFrequencyHz = binWidthHz;
        }

        return findPeaks(shiftedDb, binWidthHz, baseFrequencyHz, thresholdDb);
    }

    /**
     * Filters a peak list to those whose centers fall within [minHz, maxHz].
     */
    private List<EnergyPeak> filterToSpan(List<EnergyPeak> peaks, long minHz, long maxHz)
    {
        if(peaks.isEmpty())
        {
            return peaks;
        }

        List<EnergyPeak> filtered = new ArrayList<>(peaks.size());
        for(EnergyPeak peak : peaks)
        {
            if(peak.centerFrequencyHz() >= minHz && peak.centerFrequencyHz() <= maxHz)
            {
                filtered.add(peak);
            }
        }
        return filtered;
    }

    // -------------------------------------------------------------------------
    // SampleAccumulator inner class
    // -------------------------------------------------------------------------

    /**
     * Holds all mutable state needed by the sample-listener callback.
     *
     * <p>Maintains a ring buffer of the last {@code fftSize} I/Q samples.  Each time the ring
     * fills, a new FFT frame is computed and its per-bin power ({@code re²+im²}) is added to
     * a running sum.  The caller retrieves the averaged power spectrum via
     * {@link #getAveragedPowerDb()}.</p>
     *
     * <p><b>Not thread-safe</b> — accessed only from the sample-delivery thread.</p>
     */
    static final class SampleAccumulator
    {
        /** Tiny epsilon to avoid log(0) when a bin is silent. */
        private static final double EPSILON = 1e-20;

        private final float[] mWindow;
        private final FloatFFT_1D mFft;
        private final int mFftSize;
        private final float[] mIRing;
        private final float[] mQRing;
        private final float[] mFftBuffer;
        private final double[] mPowerAccumulator;
        private int mRingPos = 0;
        private boolean mRingFull = false;
        private int mFrameCount = 0;

        SampleAccumulator(float[] window, FloatFFT_1D fft, int fftSize)
        {
            mWindow = window;
            mFft = fft;
            mFftSize = fftSize;
            mIRing = new float[fftSize];
            mQRing = new float[fftSize];
            mFftBuffer = new float[fftSize * 2];
            mPowerAccumulator = new double[fftSize];
        }

        /** Feeds a {@link ComplexSamples} buffer through the ring → FFT → power-accumulator pipeline. */
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
                    mPowerAccumulator[n] += re * re + im * im;
                }

                mFrameCount++;
            }
        }

        boolean hasData()
        {
            return mFrameCount > 0;
        }

        /**
         * Computes the power-averaged magnitude spectrum in dB.
         * Returns a new array — caller may mutate it (e.g. for DC masking).
         */
        float[] getAveragedPowerDb()
        {
            float[] result = new float[mFftSize];
            double divisor = (mFrameCount > 0) ? mFrameCount : 1.0;

            for(int n = 0; n < mFftSize; n++)
            {
                double avgPower = mPowerAccumulator[n] / divisor;

                if(avgPower < EPSILON)
                {
                    result[n] = -200.0f;
                }
                else
                {
                    result[n] = (float)(10.0 * Math.log10(avgPower));
                }
            }

            return result;
        }
    }

    // -------------------------------------------------------------------------
    // Private static helpers
    // -------------------------------------------------------------------------

    /**
     * Estimates the noise floor as the mean of the lowest percentile of bin values.
     */
    static double estimateNoiseFloor(float[] magnitudesDb)
    {
        if(magnitudesDb.length == 0)
        {
            return 0.0;
        }

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
     */
    private static EnergyPeak runToPeak(float[] magnitudesDb, int startIdx, int endIdx,
                                         long binWidthHz, long baseFreqHz, double noiseFloor)
    {
        double weightedBinSum = 0.0;
        double totalWeight = 0.0;
        double peakDb = -Double.MAX_VALUE;

        for(int i = startIdx; i <= endIdx; i++)
        {
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

        int guardedStart = Math.max(0, startIdx - GUARD_BINS);
        int guardedEnd   = Math.min(magnitudesDb.length - 1, endIdx + GUARD_BINS);
        int runBins = (guardedEnd - guardedStart) + 1;
        int bandwidthHz = (int) Math.max(1L, (long) runBins * binWidthHz);

        double snr = peakDb - noiseFloor;

        return new EnergyPeak(centerHz, bandwidthHz, peakDb, snr);
    }

    /**
     * Merges peaks whose center frequencies are within {@link #MIN_SEPARATION_BINS} bins of each other.
     */
    private static List<EnergyPeak> mergePeaks(List<EnergyPeak> peaks, long binWidthHz)
    {
        if(peaks.size() <= 1)
        {
            return peaks;
        }

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
                        double linearA = Math.pow(10.0, a.powerDb() / 10.0);
                        double linearB = Math.pow(10.0, b.powerDb() / 10.0);
                        double totalPower = linearA + linearB;
                        long mergedCenter = (totalPower > 0.0)
                            ? (long)((a.centerFrequencyHz() * linearA + b.centerFrequencyHz() * linearB) / totalPower)
                            : (a.centerFrequencyHz() + b.centerFrequencyHz()) / 2;

                        long aMin = a.centerFrequencyHz() - a.occupiedBandwidthHz() / 2L;
                        long aMax = a.centerFrequencyHz() + a.occupiedBandwidthHz() / 2L;
                        long bMin = b.centerFrequencyHz() - b.occupiedBandwidthHz() / 2L;
                        long bMax = b.centerFrequencyHz() + b.occupiedBandwidthHz() / 2L;
                        int mergedBw = (int)(Math.max(aMax, bMax) - Math.min(aMin, bMin));
                        mergedBw = Math.max(mergedBw, 1);

                        double mergedPower = Math.max(a.powerDb(), b.powerDb());
                        double mergedSnr   = Math.max(a.snrDb(), b.snrDb());

                        result.add(new EnergyPeak(mergedCenter, mergedBw, mergedPower, mergedSnr));
                        i += 2;
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
     * Merges peaks from the combined cross-step peak list, using a wider tolerance that
     * accounts for the same signal being detected in two overlapping step windows.
     */
    private static List<EnergyPeak> mergePeaksCrossStep(List<EnergyPeak> peaks, long binWidthHz)
    {
        if(peaks.size() <= 1)
        {
            return peaks;
        }

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

                    long overlapTolerance = (a.occupiedBandwidthHz() + b.occupiedBandwidthHz()) / 2L;
                    long mergeThreshold = Math.max(minSeparationHz, overlapTolerance);

                    if(separation < mergeThreshold)
                    {
                        double linearA = Math.pow(10.0, a.powerDb() / 10.0);
                        double linearB = Math.pow(10.0, b.powerDb() / 10.0);
                        double totalPower = linearA + linearB;
                        long mergedCenter = (totalPower > 0.0)
                            ? (long)((a.centerFrequencyHz() * linearA + b.centerFrequencyHz() * linearB)
                                / totalPower)
                            : (a.centerFrequencyHz() + b.centerFrequencyHz()) / 2;

                        long aMin = a.centerFrequencyHz() - a.occupiedBandwidthHz() / 2L;
                        long aMax = a.centerFrequencyHz() + a.occupiedBandwidthHz() / 2L;
                        long bMin = b.centerFrequencyHz() - b.occupiedBandwidthHz() / 2L;
                        long bMax = b.centerFrequencyHz() + b.occupiedBandwidthHz() / 2L;
                        int mergedBw = (int)(Math.max(aMax, bMax) - Math.min(aMin, bMin));
                        mergedBw = Math.max(mergedBw, 1);

                        double mergedPower = Math.max(a.powerDb(), b.powerDb());
                        double mergedSnr   = Math.max(a.snrDb(), b.snrDb());

                        result.add(new EnergyPeak(mergedCenter, mergedBw, mergedPower, mergedSnr));
                        i += 2;
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
        System.arraycopy(data, half, shifted, 0, n - half);
        System.arraycopy(data, 0, shifted, n - half, half);
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
}
