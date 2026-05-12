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
package io.github.dsheirer.gui.preference.discovery;

import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import io.github.dsheirer.preference.discovery.OverlayDisplay;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Preference editor pane for signal discovery settings.
 *
 * <p>Mirrors the pattern of other preference editors (e.g. {@link
 * io.github.dsheirer.gui.preference.call.CallManagementPreferenceEditor}): extends {@link HBox},
 * constructs a {@link GridPane} of labelled controls that read from and write to
 * {@link DiscoveryPreference}.</p>
 *
 * <p>Changes are applied immediately when each control fires its change listener.  The preference
 * system persists them automatically via {@code java.util.prefs.Preferences}.</p>
 */
public class DiscoveryPreferenceEditor extends HBox
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryPreferenceEditor.class);

    private final DiscoveryPreference mPreference;
    private GridPane mEditorPane;

    /**
     * Constructs the editor.
     *
     * @param userPreferences application user preferences
     */
    public DiscoveryPreferenceEditor(UserPreferences userPreferences)
    {
        mPreference = userPreferences.getDiscoveryPreference();
        HBox.setHgrow(getEditorPane(), Priority.ALWAYS);
        getChildren().add(getEditorPane());
    }

    private GridPane getEditorPane()
    {
        if(mEditorPane != null)
        {
            return mEditorPane;
        }

        int row = 0;
        mEditorPane = new GridPane();
        mEditorPane.setPadding(new Insets(10, 10, 10, 10));
        mEditorPane.setHgap(10);
        mEditorPane.setVgap(10);

        // ---- Section: Scan / Survey -----------------------------------------
        Label scanSectionLabel = new Label("Scan / Survey Settings");
        scanSectionLabel.setStyle("-fx-font-weight: bold;");
        GridPane.setConstraints(scanSectionLabel, 0, row, 2, 1);
        mEditorPane.getChildren().add(scanSectionLabel);

        // Survey dwell
        GridPane.setConstraints(new Label("Survey dwell (seconds):"), 0, ++row);
        mEditorPane.getChildren().add(new Label("Survey dwell (seconds):"));

        Spinner<Integer> dwellSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 60,
                (int) mPreference.getSurveyDwell().getSeconds()));
        dwellSpinner.setEditable(true);
        dwellSpinner.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setSurveyDwell(Duration.ofSeconds(n));
        });
        GridPane.setConstraints(dwellSpinner, 1, row);
        mEditorPane.getChildren().add(dwellSpinner);

        // Energy threshold
        mEditorPane.add(new Label("Energy threshold (dB):"), 0, ++row);
        Spinner<Double> threshSpinner = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(0.0, 40.0,
                mPreference.getEnergyThresholdDb(), 0.5));
        threshSpinner.setEditable(true);
        threshSpinner.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setEnergyThresholdDb(n);
        });
        mEditorPane.add(threshSpinner, 1, row);

        // Max concurrent probes
        mEditorPane.add(new Label("Max concurrent probes:"), 0, ++row);
        Spinner<Integer> probesSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 8,
                mPreference.getMaxConcurrentProbes()));
        probesSpinner.setEditable(true);
        probesSpinner.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setMaxConcurrentProbes(n);
        });
        mEditorPane.add(probesSpinner, 1, row);

        // Max concurrent classifications
        mEditorPane.add(new Label("Max concurrent classifications:"), 0, ++row);
        Spinner<Integer> classSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 8,
                mPreference.getMaxConcurrentClassifications()));
        classSpinner.setEditable(true);
        classSpinner.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setMaxConcurrentClassifications(n);
        });
        mEditorPane.add(classSpinner, 1, row);

        // Tuner headroom channels
        mEditorPane.add(new Label("Tuner headroom channels:"), 0, ++row);
        Spinner<Integer> headroomSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 10,
                mPreference.getTunerHeadroomChannels()));
        headroomSpinner.setEditable(true);
        headroomSpinner.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setTunerHeadroomChannels(n);
        });
        mEditorPane.add(headroomSpinner, 1, row);

        // ---- Separator --------------------------------------------------
        Separator sep1 = new Separator();
        GridPane.setConstraints(sep1, 0, ++row, 2, 1);
        GridPane.setHgrow(sep1, Priority.ALWAYS);
        mEditorPane.getChildren().add(sep1);

        // ---- Section: Click-to-tune -----------------------------------------
        Label clickSectionLabel = new Label("Click-to-Tune Settings");
        clickSectionLabel.setStyle("-fx-font-weight: bold;");
        mEditorPane.add(clickSectionLabel, 0, ++row, 2, 1);

        // Click default bandwidth
        mEditorPane.add(new Label("Click default bandwidth (Hz):"), 0, ++row);
        Spinner<Integer> clickBwSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(1000, 200_000,
                mPreference.getClickDefaultBandwidthHz(), 1000));
        clickBwSpinner.setEditable(true);
        clickBwSpinner.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setClickDefaultBandwidthHz(n);
        });
        mEditorPane.add(clickBwSpinner, 1, row);

        // Keep-listening duration
        mEditorPane.add(new Label("Keep-listening duration (seconds):"), 0, ++row);
        Spinner<Integer> keepListeningSpinner = new Spinner<>(
            new SpinnerValueFactory.IntegerSpinnerValueFactory(5, 300,
                (int) mPreference.getKeepListeningDuration().getSeconds()));
        keepListeningSpinner.setEditable(true);
        keepListeningSpinner.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setKeepListeningDuration(Duration.ofSeconds(n));
        });
        mEditorPane.add(keepListeningSpinner, 1, row);

        // ---- Separator --------------------------------------------------
        Separator sep2 = new Separator();
        GridPane.setConstraints(sep2, 0, ++row, 2, 1);
        GridPane.setHgrow(sep2, Priority.ALWAYS);
        mEditorPane.getChildren().add(sep2);

        // ---- Section: Overlay display ---------------------------------------
        Label overlaySectionLabel = new Label("Spectral-Display Overlay");
        overlaySectionLabel.setStyle("-fx-font-weight: bold;");
        mEditorPane.add(overlaySectionLabel, 0, ++row, 2, 1);

        mEditorPane.add(new Label("Discovery overlay display:"), 0, ++row);
        ComboBox<OverlayDisplay> overlayCombo = new ComboBox<>();
        overlayCombo.getItems().addAll(OverlayDisplay.values());
        overlayCombo.setValue(mPreference.getOverlayDisplay());
        overlayCombo.valueProperty().addListener((obs, o, n) -> {
            if(n != null) mPreference.setOverlayDisplay(n);
        });
        mEditorPane.add(overlayCombo, 1, row);

        // ---- Separator --------------------------------------------------
        Separator sep3 = new Separator();
        GridPane.setConstraints(sep3, 0, ++row, 2, 1);
        GridPane.setHgrow(sep3, Priority.ALWAYS);
        mEditorPane.getChildren().add(sep3);

        // ---- Section: Default scan decoders (note: stored in DiscoveryPreference Phase 4) ---
        // Note: the DiscoveryPreference Phase-1 stubs for getDefaultScanDecoders() and
        // getExcludedDecoders() always return all primaries / empty set.  Persisted
        // customisation of these sets is deferred to a follow-up; the checkboxes are shown
        // here for UI completeness but only apply within the current session until the
        // persistence layer is extended in a later phase.
        Label decoderSectionLabel = new Label("Default Scan Decoders  (session-only in Phase 4)");
        decoderSectionLabel.setStyle("-fx-font-weight: bold;");
        mEditorPane.add(decoderSectionLabel, 0, ++row, 2, 1);

        Map<DecoderType, CheckBox> decoderCbs = new EnumMap<>(DecoderType.class);

        for(DecoderType type : DecoderType.PRIMARY_DECODERS)
        {
            CheckBox cb = new CheckBox(type.getDisplayString());
            cb.setSelected(!mPreference.getExcludedDecoders().contains(type));
            // Note: writes back to excluded set — deferred to full persistence in a later phase
            decoderCbs.put(type, cb);
            mEditorPane.add(cb, (row % 2 == 0) ? 0 : 1, ++row);
        }

        return mEditorPane;
    }
}
