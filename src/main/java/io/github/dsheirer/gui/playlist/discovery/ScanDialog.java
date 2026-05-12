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
package io.github.dsheirer.gui.playlist.discovery;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.discovery.BandScanController;
import io.github.dsheirer.module.discovery.ScanRequest;
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modal dialog for configuring and launching a band scan.
 *
 * <p>The dialog shows a frequency range section (pre-filled from a spectral-view span when one
 * is provided), a decoder checkbox list, an advanced collapsible pane (survey dwell, threshold,
 * max-signals), a continuous scan toggle, and a prominent warning when the scan range would
 * require a stepped (retuning) sweep.</p>
 *
 * <h3>Stepped-sweep warning</h3>
 * <p>A static warning banner is shown whenever the span exceeds a conservative 2 MHz threshold.
 * The {@link BandScanController} automatically falls back to
 * {@link io.github.dsheirer.module.discovery.SpectralSurveyApi#surveyWide} when the in-band
 * survey cannot cover the requested span, stepping the spectral display's current tuner across
 * the range.  The Scan button is never disabled — even for wide spans the sweep runs normally
 * (provided a tuner is connected).</p>
 *
 * <h3>ETA / stepped computation</h3>
 * The static helper {@link #isLikelySteppedSweep(long, long)} is package-private for unit testing.
 */
public class ScanDialog extends Stage
{
    private static final Logger mLog = LoggerFactory.getLogger(ScanDialog.class);

    /**
     * Threshold for the stepped-sweep warning banner: if the requested span exceeds this,
     * the operator is warned that the survey will retune the displayed tuner and may disrupt
     * active decoding.  10 MHz matches typical wideband SDRs (Airspy R2, SDRplay RSP1A,
     * RTL-SDR with upsampling); smaller devices will still trigger the banner for narrower spans.
     *
     * <p>TODO: thread the active tuner's sample rate in from {@link io.github.dsheirer.module.discovery.TunerControl}
     * to make this threshold dynamic rather than a fixed constant.</p>
     */
    private static final long STEPPED_SWEEP_WARNING_HZ = 10_000_000L;

    private final BandScanController mBandScanController;
    private final DiscoveryPreference mDiscoveryPreference;

    // Frequency range
    private Spinner<Double> mMinMhzSpinner;
    private Spinner<Double> mMaxMhzSpinner;
    private Label mSteppedWarningLabel;

    // Decoder checkboxes
    private List<CheckBox> mDecoderCheckBoxes;

    // Advanced
    private Spinner<Integer> mDwellSpinner;
    private Spinner<Double> mThresholdSpinner;
    private Spinner<Integer> mMaxSignalsSpinner;

    // Continuous
    private CheckBox mContinuousCheckBox;
    private Spinner<Integer> mContinuousIntervalSpinner;

    /**
     * Constructs the dialog.
     *
     * @param bandScanController  controller to call when the operator clicks Scan
     * @param discoveryPreference user preferences for default values
     * @param preFilledMinHz      pre-fill value for the min frequency (0 = use 144.000 MHz default)
     * @param preFilledMaxHz      pre-fill value for the max frequency (0 = use 148.000 MHz default)
     */
    public ScanDialog(BandScanController bandScanController, DiscoveryPreference discoveryPreference,
                      long preFilledMinHz, long preFilledMaxHz)
    {
        mBandScanController = bandScanController;
        mDiscoveryPreference = discoveryPreference;

        initModality(Modality.APPLICATION_MODAL);
        setTitle("Band Scan Configuration");
        setResizable(true);

        double defaultMinMhz = preFilledMinHz > 0 ? preFilledMinHz / 1e6 : 144.0;
        double defaultMaxMhz = preFilledMaxHz > 0 ? preFilledMaxHz / 1e6 : 148.0;

        Scene scene = new Scene(buildContent(defaultMinMhz, defaultMaxMhz), 480, 600);
        setScene(scene);

        // Update the stepped warning whenever the range changes
        mMinMhzSpinner.valueProperty().addListener((obs, o, n) -> updateSteppedWarning());
        mMaxMhzSpinner.valueProperty().addListener((obs, o, n) -> updateSteppedWarning());
        updateSteppedWarning();
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private VBox buildContent(double defaultMinMhz, double defaultMaxMhz)
    {
        VBox root = new VBox(8);
        root.setPadding(new Insets(12));

        // --- Frequency range ---
        root.getChildren().add(buildFrequencySection(defaultMinMhz, defaultMaxMhz));

        // --- Stepped-sweep warning ---
        mSteppedWarningLabel = new Label(
            "⚠  This range is wider than your SDR can see at once. The scan will step the SDR "
            + "across the range, which interrupts decoding on that tuner for the duration. "
            + "Some SDRs may not see signals in the small region around each step's center "
            + "frequency (DC-spike avoidance zone).");
        mSteppedWarningLabel.setWrapText(true);
        mSteppedWarningLabel.setStyle(
            "-fx-background-color: #cc3300; -fx-text-fill: white; "
            + "-fx-padding: 6 8 6 8; -fx-background-radius: 4;");
        mSteppedWarningLabel.setVisible(false);
        mSteppedWarningLabel.setManaged(false);
        root.getChildren().add(mSteppedWarningLabel);

        // --- Decoder checkboxes ---
        root.getChildren().add(new Separator());
        root.getChildren().add(new Label("Decoder candidates:"));
        root.getChildren().add(buildDecoderSection());

        // --- Advanced (collapsible) ---
        root.getChildren().add(new Separator());
        root.getChildren().add(buildAdvancedSection());

        // --- Continuous ---
        root.getChildren().add(new Separator());
        root.getChildren().add(buildContinuousSection());

        // --- Buttons ---
        root.getChildren().add(new Separator());
        root.getChildren().add(buildButtonBar());

        return root;
    }

    private GridPane buildFrequencySection(double defaultMinMhz, double defaultMaxMhz)
    {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(0, 0, 4, 0));

        grid.add(new Label("Frequency range (MHz)"), 0, 0, 2, 1);

        grid.add(new Label("Min:"), 0, 1);
        mMinMhzSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, 6000.0, defaultMinMhz, 0.1));
        mMinMhzSpinner.setEditable(true);
        mMinMhzSpinner.setPrefWidth(120);
        grid.add(mMinMhzSpinner, 1, 1);

        grid.add(new Label("Max:"), 0, 2);
        mMaxMhzSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(0.1, 6000.0, defaultMaxMhz, 0.1));
        mMaxMhzSpinner.setEditable(true);
        mMaxMhzSpinner.setPrefWidth(120);
        grid.add(mMaxMhzSpinner, 1, 2);

        return grid;
    }

    private VBox buildDecoderSection()
    {
        VBox box = new VBox(4);
        mDecoderCheckBoxes = new ArrayList<>();
        Set<DecoderType> defaults = mDiscoveryPreference.getDefaultScanDecoders();
        Set<DecoderType> excluded = mDiscoveryPreference.getExcludedDecoders();

        for(DecoderType type : DecoderType.PRIMARY_DECODERS)
        {
            CheckBox cb = new CheckBox(type.getDisplayString());
            cb.setUserData(type);
            cb.setSelected(defaults.contains(type) && !excluded.contains(type));
            mDecoderCheckBoxes.add(cb);
            box.getChildren().add(cb);
        }

        return box;
    }

    private TitledPane buildAdvancedSection()
    {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.setPadding(new Insets(4, 0, 4, 0));

        int row = 0;

        grid.add(new Label("Survey dwell (s):"), 0, row);
        int defaultDwellS = (int) mDiscoveryPreference.getSurveyDwell().getSeconds();
        mDwellSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60, defaultDwellS));
        mDwellSpinner.setEditable(true);
        grid.add(mDwellSpinner, 1, row++);

        grid.add(new Label("Threshold (dB):"), 0, row);
        double defaultThreshDb = mDiscoveryPreference.getEnergyThresholdDb();
        mThresholdSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 40.0, defaultThreshDb, 0.5));
        mThresholdSpinner.setEditable(true);
        grid.add(mThresholdSpinner, 1, row++);

        grid.add(new Label("Max signals to probe:"), 0, row);
        mMaxSignalsSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 1000, ScanRequest.DEFAULT_MAX_SIGNALS_TO_PROBE));
        mMaxSignalsSpinner.setEditable(true);
        grid.add(mMaxSignalsSpinner, 1, row);

        TitledPane pane = new TitledPane("Advanced", grid);
        pane.setExpanded(false);
        return pane;
    }

    private VBox buildContinuousSection()
    {
        VBox box = new VBox(6);

        mContinuousCheckBox = new CheckBox("Continuous (re-survey after interval)");

        HBox intervalRow = new HBox(8);
        Label intervalLabel = new Label("Interval (minutes):");
        int defaultIntervalMin = (int) ScanRequest.DEFAULT_CONTINUOUS_INTERVAL.toMinutes();
        mContinuousIntervalSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 120, defaultIntervalMin));
        mContinuousIntervalSpinner.setEditable(true);
        mContinuousIntervalSpinner.setPrefWidth(80);
        mContinuousIntervalSpinner.disableProperty().bind(mContinuousCheckBox.selectedProperty().not());
        intervalRow.getChildren().addAll(intervalLabel, mContinuousIntervalSpinner);

        box.getChildren().addAll(mContinuousCheckBox, intervalRow);
        return box;
    }

    private HBox buildButtonBar()
    {
        Button scanButton = new Button("Scan");
        scanButton.setDefaultButton(true);
        scanButton.setOnAction(e -> startScan());

        Button cancelButton = new Button("Cancel");
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(e -> close());

        HBox bar = new HBox(8, scanButton, cancelButton);
        HBox.setHgrow(scanButton, Priority.NEVER);
        return bar;
    }

    // -------------------------------------------------------------------------
    // Logic
    // -------------------------------------------------------------------------

    /**
     * Returns whether the given frequency span is likely to require a stepped sweep.
     * This is package-private to allow unit testing.
     *
     * @param minHz lower bound in Hz
     * @param maxHz upper bound in Hz
     * @return {@code true} if the span exceeds the conservative stepped-sweep threshold
     */
    static boolean isLikelySteppedSweep(long minHz, long maxHz)
    {
        return (maxHz - minHz) > STEPPED_SWEEP_WARNING_HZ;
    }

    private void updateSteppedWarning()
    {
        if(mMinMhzSpinner == null || mMaxMhzSpinner == null || mSteppedWarningLabel == null)
        {
            return;
        }

        double minMhz = safeSpinnerValue(mMinMhzSpinner);
        double maxMhz = safeSpinnerValue(mMaxMhzSpinner);
        long minHz = (long) (minMhz * 1e6);
        long maxHz = (long) (maxMhz * 1e6);

        boolean stepped = isLikelySteppedSweep(minHz, maxHz);
        mSteppedWarningLabel.setVisible(stepped);
        mSteppedWarningLabel.setManaged(stepped);
    }

    private void startScan()
    {
        double minMhz = safeSpinnerValue(mMinMhzSpinner);
        double maxMhz = safeSpinnerValue(mMaxMhzSpinner);
        long minHz = (long) (minMhz * 1e6);
        long maxHz = (long) (maxMhz * 1e6);

        if(minHz >= maxHz)
        {
            mLog.warn("ScanDialog: min ({} Hz) >= max ({} Hz) — ignoring scan request", minHz, maxHz);
            return;
        }

        EnumSet<DecoderType> selectedDecoders = EnumSet.noneOf(DecoderType.class);

        for(CheckBox cb : mDecoderCheckBoxes)
        {
            if(cb.isSelected() && cb.getUserData() instanceof DecoderType dt)
            {
                selectedDecoders.add(dt);
            }
        }

        if(selectedDecoders.isEmpty())
        {
            selectedDecoders = EnumSet.copyOf(DecoderType.PRIMARY_DECODERS);
        }

        int dwellSeconds = safeSpinnerValue(mDwellSpinner);
        double thresholdDb = safeSpinnerValue(mThresholdSpinner);
        int maxSignals = safeSpinnerValue(mMaxSignalsSpinner);
        boolean continuous = mContinuousCheckBox.isSelected();
        int intervalMinutes = safeSpinnerValue(mContinuousIntervalSpinner);

        ScanRequest request = new ScanRequest(
            minHz,
            maxHz,
            selectedDecoders,
            Duration.ofSeconds(dwellSeconds),
            thresholdDb,
            maxSignals,
            continuous,
            Duration.ofMinutes(intervalMinutes));

        mLog.info("Starting band scan: {} – {} MHz, {} decoders, dwell {}s, threshold {}dB",
            String.format(Locale.ROOT, "%.3f", minMhz),
            String.format(Locale.ROOT, "%.3f", maxMhz),
            selectedDecoders.size(), dwellSeconds, thresholdDb);

        mBandScanController.startScan(request);
        close();
    }

    /** Safely reads a Spinner's value, falling back to the factory initial value on parse error. */
    @SuppressWarnings("unchecked")
    private static <T extends Number> T safeSpinnerValue(Spinner<T> spinner)
    {
        try
        {
            spinner.commitValue();
        }
        catch(Exception ignored) { /* malformed text — use the last good value */ }

        return spinner.getValue();
    }
}
