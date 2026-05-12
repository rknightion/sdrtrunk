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
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
    // Public method: stepped (wide-span) survey (Task 5.1)
    // -------------------------------------------------------------------------

    /**
     * Performs a wide stepped-sweep survey over a span that may exceed the tuner's
     * instantaneous bandwidth.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Compute the stride width as 80% of the tuner's usable bandwidth ({@code strideFraction = 0.8}).</li>
     *   <li>Compute step centers: first center = {@code minHz + strideHz/2}, advancing by {@code strideHz}
     *       until the span is covered.</li>
     *   <li>For each step: retune the tuner, wait a short settle (100 ms), run an in-band mini-survey
     *       (per-step dwell = {@code totalDwell / stepCount}, minimum 500 ms), accumulate the peaks.</li>
     *   <li>Translate each peak's center frequency: peaks from {@link #accumulateAndFindPeaks} are
     *       already in absolute Hz because the fake/real source reports the step's center frequency.</li>
     *   <li>After all steps: merge peaks from different steps whose centers are within the
     *       same step-edge overlap zone (handled by {@link #mergePeaks} at the end).</li>
     *   <li>{@code finally}: restore the tuner to its original center frequency, even on cancel/error.</li>
     * </ol>
     *
     * <h3>Disruption</h3>
     * <p>This method calls {@link TunerControl#setCenterFreqHz} repeatedly, which causes active channel
     * sources on the tuner to receive mistuned samples.  It must only be called after the operator
     * has confirmed the disruption warning in {@code ScanDialog}.</p>
     *
     * @param minHz        lower bound of the span in Hz (inclusive)
     * @param maxHz        upper bound of the span in Hz (inclusive)
     * @param dwell        total dwell budget divided equally across all steps
     * @param thresholdDb  SNR threshold in dB above estimated noise floor
     * @param tunerControl control seam for reading/setting the tuner center frequency
     * @param progress     progress listener (0.0..1.0); may be null
     * @return cancellable future resolving to the merged list of detected peaks (never null, may be empty)
     */
    @Override
    public CompletableFuture<List<EnergyPeak>> surveyWide(long minHz, long maxHz, Duration dwell,
                                                           double thresholdDb,
                                                           TunerControl tunerControl,
                                                           ProgressListener progress)
    {
        if(minHz >= maxHz)
        {
            CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException(
                "minHz (" + minHz + ") must be less than maxHz (" + maxHz + ")"));
            return failed;
        }

        if(tunerControl == null)
        {
            CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("tunerControl must not be null"));
            return failed;
        }

        if(!tunerControl.isAvailable())
        {
            CompletableFuture<List<EnergyPeak>> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException(
                "No tuner available for stepped sweep — tunerControl.isAvailable() returned false"));
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
                List<EnergyPeak> result = doSteppedSurvey(
                    minHz, maxHz, dwell, thresholdDb, tunerControl, progress, cancelledFlag);

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
    // Stepped sweep implementation (runs on executor thread)
    // -------------------------------------------------------------------------

    /**
     * The fraction of the usable bandwidth used for each step stride.
     * Using 80% ensures adjacent steps overlap by 20% so no edge frequencies are missed.
     */
    private static final double STEP_STRIDE_FRACTION = 0.80;

    /**
     * Minimum per-step dwell in milliseconds.  Even a very fast sweep must wait long enough
     * to accumulate at least a few FFT frames (the ring needs filling once, which takes
     * {@code FFT_SIZE / sampleRate} seconds ≈ 2 ms at 2 Msps; 500 ms gives ~1000 frames,
     * which is well above the minimum for a meaningful power average).
     */
    private static final long MIN_STEP_DWELL_MS = 500L;

    /**
     * Settle time in milliseconds after each retune before starting sample capture.
     * Gives the hardware AGC and PLL time to re-lock after a frequency change.
     */
    private static final long SETTLE_TIME_MS = 100L;

    /**
     * Executes the stepped sweep and returns the merged peak list.
     * Always restores the original center frequency in a {@code finally} block.
     *
     * @param minHz         lower bound of the span
     * @param maxHz         upper bound of the span
     * @param dwell         total dwell budget
     * @param thresholdDb   detection threshold
     * @param tunerControl  tuner frequency control seam
     * @param progress      progress listener; may be null
     * @param cancelledFlag cooperative cancellation flag
     * @return merged peak list from all steps
     */
    private List<EnergyPeak> doSteppedSurvey(long minHz, long maxHz, Duration dwell,
                                               double thresholdDb, TunerControl tunerControl,
                                               ProgressListener progress,
                                               AtomicBoolean cancelledFlag)
    {
        // Capture the original center frequency so we can restore it in finally
        long originalCenterHz = tunerControl.getCurrentCenterFreqHz();

        try
        {
            return executeSteps(minHz, maxHz, dwell, thresholdDb,
                tunerControl, progress, cancelledFlag);
        }
        finally
        {
            // Restore the original center frequency unconditionally
            try
            {
                tunerControl.setCenterFreqHz(originalCenterHz);
                mLog.debug("Stepped sweep: restored tuner center to {} Hz", originalCenterHz);
            }
            catch(Exception ex)
            {
                mLog.warn("Stepped sweep: failed to restore tuner center frequency to {} Hz: {}",
                    originalCenterHz, ex.getMessage());
            }
        }
    }

    /**
     * Core step loop.  Computes step centers, retunes at each, collects peaks, merges.
     */
    private List<EnergyPeak> executeSteps(long minHz, long maxHz, Duration dwell,
                                           double thresholdDb, TunerControl tunerControl,
                                           ProgressListener progress,
                                           AtomicBoolean cancelledFlag)
    {
        long usableBw = tunerControl.getUsableBandwidthHz();

        if(usableBw <= 0)
        {
            throw new RuntimeException(
                "Tuner reports zero usable bandwidth — cannot compute step stride");
        }

        // Stride = 80% of usable bandwidth; overlap at edges catches edge-leakage signals
        long strideHz = Math.max(1L, (long)(usableBw * STEP_STRIDE_FRACTION));

        // First step center: half a stride inside the left edge
        // This ensures the left edge of the first step's usable window sits at minHz
        long firstCenterHz = minHz + usableBw / 2L;

        // Build the list of step centers
        List<Long> stepCenters = new ArrayList<>();
        long centerHz = firstCenterHz;

        while(centerHz - usableBw / 2L < maxHz)
        {
            stepCenters.add(centerHz);
            centerHz += strideHz;
        }

        // Ensure the last center's right edge covers maxHz
        if(stepCenters.isEmpty() || stepCenters.get(stepCenters.size() - 1) + usableBw / 2L < maxHz)
        {
            stepCenters.add(maxHz - usableBw / 2L);
        }

        // Clamp each center to the tuner's tunable range
        long minTunable = tunerControl.getMinFrequencyHz();
        long maxTunable = tunerControl.getMaxFrequencyHz();
        stepCenters.replaceAll(c -> Math.max(minTunable, Math.min(maxTunable, c)));

        // Warn if the tunable range clips the requested span
        long firstWindowLow = stepCenters.get(0) - usableBw / 2L;
        long lastWindowHigh = stepCenters.get(stepCenters.size() - 1) + usableBw / 2L;
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

        // Remove duplicate centers (can happen if range is much smaller than usable bandwidth,
        // which shouldn't occur in a stepped sweep but is harmless to guard against)
        stepCenters = stepCenters.stream().distinct().toList();

        int stepCount = stepCenters.size();
        mLog.debug("Stepped sweep: {} steps over {} MHz span (stride {} kHz, usableBw {} kHz)",
            stepCount, (maxHz - minHz) / 1_000_000.0, strideHz / 1_000.0, usableBw / 1_000.0);

        // Per-step dwell: divide total dwell equally, enforce minimum
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

            // Retune the tuner
            try
            {
                tunerControl.setCenterFreqHz(stepCenter);
            }
            catch(Exception ex)
            {
                throw new RuntimeException(
                    "Stepped sweep: failed to retune to " + stepCenter + " Hz at step " +
                    (i + 1) + "/" + stepCount + ": " + ex.getMessage(), ex);
            }

            // Settle — give hardware AGC/PLL time to stabilise
            if(SETTLE_TIME_MS > 0 && !cancelledFlag.get())
            {
                try
                {
                    Thread.sleep(SETTLE_TIME_MS);
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

            // Build a per-step progress listener that maps this step's 0..1 progress
            // into the overall 0..1 progress range
            ProgressListener stepProgress = null;

            if(progress != null)
            {
                double stepStart = (double) stepIndex / stepCount;
                double stepRange = 1.0 / stepCount;
                stepProgress = fraction -> progress.onProgress(stepStart + fraction * stepRange);
            }

            // Acquire a source for this step's center frequency and run an in-band survey
            final long stepCenterFinal = stepCenter;
            final ProgressListener stepProgressFinal = stepProgress;

            try
            {
                // Use the same in-band acquisition path as the regular survey
                List<EnergyPeak> stepPeaks = doSurvey(
                    stepCenterFinal - usableBw / 2L,
                    stepCenterFinal + usableBw / 2L,
                    stepDwell,
                    thresholdDb,
                    stepProgressFinal,
                    cancelledFlag);

                allPeaks.addAll(stepPeaks);
            }
            catch(RuntimeException ex)
            {
                // A step survey failure should not abort the whole sweep — log and continue
                mLog.warn("Stepped sweep: step {}/{} survey failed (center={} Hz): {}",
                    i + 1, stepCount, stepCenter, ex.getMessage());
            }
        }

        if(progress != null)
        {
            progress.onProgress(1.0);
        }

        // Merge peaks across step boundaries using a wider tolerance to collapse duplicates
        // that arise in the ~20% overlap zone between adjacent steps (where the same signal
        // may appear in two consecutive steps with slightly different centroid positions).
        long binWidthHz = Math.max(1L, (usableBw / FFT_SIZE));
        return Collections.unmodifiableList(mergePeaksCrossStep(allPeaks, binWidthHz));
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

        if(!accumulator.hasData())
        {
            // No frames were accumulated (no samples received)
            return Collections.emptyList();
        }

        // Power-domain average: 10·log10(avgPower + epsilon) per bin
        float[] avgMagnitudesDb = accumulator.getAveragedPowerDb();

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
     * <p>Maintains a ring buffer of the last {@code fftSize} I/Q samples.  When the ring
     * fills for the first time, and each time new samples overwrite the oldest entries, a
     * new overlapping FFT frame is computed.  Each frame's per-bin power ({@code re²+im²})
     * is added to a running sum; a frame counter tracks how many frames have been processed.
     * The caller retrieves the <em>averaged power spectrum</em> via
     * {@link #getAveragedPowerDb()}, which divides the running sums by the frame count and
     * converts to dB ({@code 10·log10(avgPower + epsilon)}).</p>
     *
     * <p>This approach avoids the previous bug of {@code 20·log10(sum)} which returned
     * {@code 20·log10(N·|X|)} instead of a genuine spectral average.</p>
     *
     * <p><b>Not thread-safe</b> — accessed only from the source's delivery thread.</p>
     */
    private static final class SampleAccumulator
    {
        /** Tiny epsilon to avoid log(0) when a bin is silent. */
        private static final double EPSILON = 1e-20;

        private final float[] mWindow;
        private final FloatFFT_1D mFft;
        private final int mFftSize;
        private final float[] mIRing;
        private final float[] mQRing;
        private final float[] mFftBuffer;
        /** Running sum of per-bin power (re²+im²) across all frames processed so far. */
        private final double[] mPowerAccumulator;
        private int mRingPos = 0;
        private boolean mRingFull = false;
        /** Number of FFT frames accumulated. */
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

                // Accumulate power (re²+im²) per bin
                for(int n = 0; n < mFftSize; n++)
                {
                    double re = mFftBuffer[2 * n];
                    double im = mFftBuffer[2 * n + 1];
                    mPowerAccumulator[n] += re * re + im * im;
                }

                mFrameCount++;
            }
        }

        /**
         * Returns {@code true} if at least one FFT frame has been accumulated.
         */
        boolean hasData()
        {
            return mFrameCount > 0;
        }

        /**
         * Computes the power-averaged magnitude spectrum in dB.
         *
         * <p>Each bin's value is {@code 10·log10(avgPower)} — a genuine power-domain average
         * that removes the frame-count bias from the absolute dB values.  Bins with zero or
         * very low power (below {@link #EPSILON}) are clamped to {@code -200 dBFS} to avoid
         * log(0) and to keep the noise-floor estimator from being pulled to arbitrarily
         * negative values by epsilon-level bins.</p>
         *
         * @return array of averaged power in dBFS (one entry per FFT bin); never null
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
     * <p>This is the standard (tight) merge used within a single survey step.  For cross-step
     * merging after a stepped sweep, use {@link #mergePeaksCrossStep(List, long)} instead, which
     * applies a wider tolerance to collapse duplicates that arise in the 20% overlap region between
     * adjacent steps.</p>
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
     * Merges peaks from the combined cross-step peak list, using a wider tolerance that
     * accounts for the same signal being detected in two overlapping step windows with
     * slightly different centroid positions due to windowing offsets.
     *
     * <p>Two peaks are merged if their center frequencies differ by less than:</p>
     * <pre>max(MIN_SEPARATION_BINS * binWidthHz, (occupiedBwA + occupiedBwB) / 2)</pre>
     * <p>i.e. their occupied bands overlap or nearly overlap.  This collapses duplicates
     * that arise in the ~20% step-edge overlap region without merging genuinely distinct
     * signals that happen to be close together.</p>
     *
     * @param peaks      all peaks from all steps (may contain cross-step duplicates)
     * @param binWidthHz Hz per FFT bin
     * @return de-duplicated peak list, sorted by center frequency ascending
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

                    // Wider tolerance: merge if either the standard min-separation rule
                    // applies, OR if the occupied bandwidths overlap (their half-widths
                    // overlap, indicating the same physical signal seen from two steps).
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
