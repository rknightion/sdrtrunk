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

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.gui.playlist.channel.ViewChannelRequest;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.discovery.BandScanController;
import io.github.dsheirer.module.discovery.Discovery;
import io.github.dsheirer.module.discovery.DiscoveryModel;
import io.github.dsheirer.module.discovery.DiscoveryState;
import io.github.dsheirer.module.discovery.ScanState;
import io.github.dsheirer.module.discovery.SignalKind;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavaFX editor tab showing band-scan discovery results.
 *
 * <p>Provides a toolbar with scan/stop/progress controls, an "add all ≥ N pips" action,
 * clear-finished, settings, and manage-ignored buttons, plus a {@link TableView} bound
 * directly to the observable list in {@link DiscoveryModel}.</p>
 *
 * <h3>Threading</h3>
 * All JavaFX UI interactions happen on the FX Application Thread. The {@link BandScanController}
 * and {@link DiscoveryModel} both marshal mutations to the FX thread automatically, so binding
 * to the model's observable list is safe.
 */
public class DiscoveryEditor extends BorderPane
{
    private static final Logger mLog = LoggerFactory.getLogger(DiscoveryEditor.class);
    private static final DateTimeFormatter TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    // ---- dependencies -------------------------------------------------------
    private final BandScanController mBandScanController;
    private final DiscoveryModel mDiscoveryModel;
    private final UserPreferences mUserPreferences;
    private final DiscoveryPreference mDiscoveryPreference;

    // ---- toolbar controls ---------------------------------------------------
    private Button mScanButton;
    private Button mStopButton;
    private ProgressBar mProgressBar;
    private Label mStateLabel;
    private ComboBox<Integer> mMinPipsCombo;
    private Button mAddAllButton;
    private Button mClearFinishedButton;
    private Button mClearAllButton;
    private Button mSettingsButton;
    private Button mManageIgnoredButton;

    // ---- table --------------------------------------------------------------
    private TableView<Discovery> mTable;

    // ---- stored ScanSpanRequest pre-fill (set before opening ScanDialog) ----
    private long mPreFillMinHz = 0;
    private long mPreFillMaxHz = 0;

    /**
     * Constructs the editor.
     *
     * @param bandScanController controller that drives scans and operator actions
     * @param userPreferences    application user preferences
     */
    public DiscoveryEditor(BandScanController bandScanController, UserPreferences userPreferences)
    {
        mBandScanController = bandScanController;
        mDiscoveryModel = bandScanController.getDiscoveryModel();
        mUserPreferences = userPreferences;
        mDiscoveryPreference = userPreferences.getDiscoveryPreference();

        setTop(buildToolBar());
        setCenter(buildTable());
        setPadding(new Insets(4));
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Pre-fills the scan-dialog frequency range and immediately opens it.
     * Called when a {@link io.github.dsheirer.gui.playlist.channel.ScanSpanRequest} arrives.
     *
     * @param minHz lower bound in Hz
     * @param maxHz upper bound in Hz
     */
    public void openScanDialogWithSpan(long minHz, long maxHz)
    {
        mPreFillMinHz = minHz;
        mPreFillMaxHz = maxHz;
        openScanDialog();
    }

    /**
     * Selects the table row closest to {@code focusFrequencyHz}.
     * Called when a {@link io.github.dsheirer.gui.playlist.channel.ShowDiscoveryRequest} arrives.
     *
     * <p>Thread safety: this method always runs on the JavaFX Application Thread.
     * The call chain is {@code JavaFxWindowManager.execute()} →
     * {@code PlaylistEditor.process(ShowDiscoveryRequest)} → this method.
     * {@code JavaFxWindowManager.execute()} wraps every invocation in
     * {@code Platform.runLater()} when not already on the FX thread, so no additional
     * threading guard is required here.</p>
     *
     * @param focusFrequencyHz frequency to focus; 0 = no-op
     */
    public void focusFrequency(long focusFrequencyHz)
    {
        if(focusFrequencyHz == 0 || mTable == null)
        {
            return;
        }

        Discovery best = null;
        long bestDelta = Long.MAX_VALUE;

        for(Discovery d : mDiscoveryModel.snapshot())
        {
            long delta = Math.abs(d.getCenterFrequencyHz() - focusFrequencyHz);

            if(delta < bestDelta)
            {
                bestDelta = delta;
                best = d;
            }
        }

        if(best != null)
        {
            mTable.getSelectionModel().select(best);
            mTable.scrollTo(best);
        }
    }

    // -------------------------------------------------------------------------
    // Toolbar
    // -------------------------------------------------------------------------

    private ToolBar buildToolBar()
    {
        mScanButton = new Button("Scan…");
        mScanButton.setTooltip(new Tooltip("Open the scan dialog to start a new band scan"));
        mScanButton.setOnAction(e -> openScanDialog());

        mStopButton = new Button("Stop");
        mStopButton.setTooltip(new Tooltip("Stop the current scan"));
        mStopButton.setOnAction(e -> mBandScanController.stop());
        // Enabled only while scanning
        mStopButton.disableProperty().bind(
            Bindings.createBooleanBinding(
                () -> !isActiveState(mBandScanController.getScanState()),
                mBandScanController.scanStateProperty()
            )
        );

        mProgressBar = new ProgressBar(0.0);
        mProgressBar.setPrefWidth(120);
        mProgressBar.progressProperty().bind(mBandScanController.progressProperty());
        mProgressBar.visibleProperty().bind(
            Bindings.createBooleanBinding(
                () -> isActiveState(mBandScanController.getScanState()),
                mBandScanController.scanStateProperty()
            )
        );

        mStateLabel = new Label("Idle");
        mStateLabel.textProperty().bind(
            Bindings.createStringBinding(
                () -> formatState(mBandScanController.getScanState(),
                    (int)(mBandScanController.getProgress() * 100.0)),
                mBandScanController.scanStateProperty(),
                mBandScanController.progressProperty()
            )
        );
        mStateLabel.setMinWidth(120);
        // Show error message as tooltip when state is ERROR
        mBandScanController.scanStateProperty().addListener((obs, oldState, newState) -> {
            if(newState == ScanState.ERROR)
            {
                String msg = mBandScanController.getLastErrorMessage();
                mStateLabel.setTooltip(msg != null && !msg.isEmpty()
                    ? new Tooltip(msg) : null);
            }
            else
            {
                mStateLabel.setTooltip(null);
            }
        });

        // "Add all ≥ N pips" combo + button
        mMinPipsCombo = new ComboBox<>();
        mMinPipsCombo.getItems().addAll(1, 2, 3, 4);
        mMinPipsCombo.setValue(2);
        mMinPipsCombo.setPrefWidth(60);
        mMinPipsCombo.setTooltip(new Tooltip("Minimum confidence pips for bulk-add"));

        mAddAllButton = new Button("Add all ≥");
        mAddAllButton.setTooltip(new Tooltip("Add all identified discoveries at or above the chosen confidence"));
        mAddAllButton.setOnAction(e -> {
            int minPips = mMinPipsCombo.getValue() != null ? mMinPipsCombo.getValue() : 2;
            mBandScanController.addAllAtLeast(minPips);
        });

        mClearFinishedButton = new Button("Clear finished");
        mClearFinishedButton.setTooltip(new Tooltip("Remove probed rows (IDENTIFIED, UNIDENTIFIED, ERROR, KNOWN)"));
        mClearFinishedButton.setOnAction(e -> mDiscoveryModel.clearFinished());

        mClearAllButton = new Button("Clear all");
        mClearAllButton.setTooltip(new Tooltip("Remove all discovery rows"));
        mClearAllButton.setOnAction(e -> mDiscoveryModel.clear());

        mSettingsButton = new Button("Settings…");
        mSettingsButton.setTooltip(new Tooltip("Open discovery preferences"));
        mSettingsButton.setOnAction(e -> openSettings());

        mManageIgnoredButton = new Button("Manage ignored…");
        mManageIgnoredButton.setTooltip(new Tooltip("View and edit the ignored-frequency list"));
        mManageIgnoredButton.setOnAction(e -> openManageIgnored());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        ToolBar toolbar = new ToolBar(
            mScanButton, mStopButton, mProgressBar, mStateLabel, spacer,
            mAddAllButton, mMinPipsCombo, mClearFinishedButton, mClearAllButton,
            mSettingsButton, mManageIgnoredButton
        );

        return toolbar;
    }

    private static boolean isActiveState(ScanState state)
    {
        return state == ScanState.SURVEYING || state == ScanState.PROBING;
    }

    private static String formatState(ScanState state, int pct)
    {
        if(state == null)
        {
            return "Idle";
        }

        return switch(state)
        {
            case IDLE           -> "Idle";
            case SURVEYING      -> "Surveying · " + pct + "%";
            case PROBING        -> "Probing · " + pct + "%";
            case DONE           -> "Done";
            case IDLE_CONTINUOUS -> "Continuous (waiting)";
            case CANCELLED      -> "Cancelled";
            case ERROR          -> "Error";
        };
    }

    // -------------------------------------------------------------------------
    // Table
    // -------------------------------------------------------------------------

    private TableView<Discovery> buildTable()
    {
        mTable = new TableView<>();
        mTable.setPlaceholder(new Label("No discoveries yet — start a scan."));
        mTable.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

        // Bind to the observable list so changes from the background thread (already
        // marshalled to the FX thread by DiscoveryModel) flow directly into the table.
        SortedList<Discovery> sorted = new SortedList<>(mDiscoveryModel.getDiscoveries());
        sorted.comparatorProperty().bind(mTable.comparatorProperty());
        mTable.setItems(sorted);

        // --- Column: State ---
        // The cell value is a composite of stateProperty + createdChannelProperty, so we use
        // the row's Discovery as the cell value and observe both observable properties.
        TableColumn<Discovery, Discovery> stateCol = new TableColumn<>("State");
        stateCol.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        stateCol.setCellFactory(col -> new TableCell<>()
        {
            {
                // Re-render whenever the row item's state or createdChannel changes
                itemProperty().addListener((obs, oldD, newD) -> {
                    if(oldD != null)
                    {
                        oldD.stateProperty().removeListener(this::invalidated);
                        oldD.createdChannelProperty().removeListener(this::invalidated);
                    }
                    if(newD != null)
                    {
                        newD.stateProperty().addListener(this::invalidated);
                        newD.createdChannelProperty().addListener(this::invalidated);
                    }
                    render(newD);
                });
            }

            private void invalidated(javafx.beans.Observable obs)
            {
                render(getItem());
            }

            private void render(Discovery d)
            {
                if(isEmpty() || d == null)
                {
                    setText(null);
                    return;
                }
                if(d.getCreatedChannel() != null)
                {
                    setText(d.getCreatedChannel().isTemporaryLive() ? "● live" : "● saved");
                    return;
                }
                DiscoveryState state = d.getState();
                setText(state == null ? null : switch(state)
                {
                    case ENERGY_DETECTED -> "⚡ energy";
                    case PROBING        -> "⏳ probing";
                    case IDENTIFIED     -> "✓";
                    case UNIDENTIFIED   -> "?";
                    case KNOWN          -> "known";
                    case ERROR          -> "✕";
                });
            }

            @Override
            protected void updateItem(Discovery d, boolean empty)
            {
                super.updateItem(d, empty);
                render(empty ? null : d);
            }
        });
        stateCol.setPrefWidth(90);

        // --- Column: Frequency (MHz) ---
        TableColumn<Discovery, Long> freqCol = new TableColumn<>("Frequency");
        freqCol.setCellValueFactory(f ->
            new ReadOnlyObjectWrapper<>(f.getValue().getCenterFrequencyHz()));
        freqCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Long freq, boolean empty)
            {
                super.updateItem(freq, empty);
                setText(empty || freq == null ? null
                    : String.format(Locale.ROOT, "%.5f MHz", freq / 1e6));
            }
        });
        freqCol.setComparator(Long::compare);
        freqCol.setPrefWidth(130);

        // --- Column: Bandwidth ---
        TableColumn<Discovery, Integer> bwCol = new TableColumn<>("BW");
        bwCol.setCellValueFactory(f ->
            new ReadOnlyObjectWrapper<>(f.getValue().getBandwidthHz()));
        bwCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Integer bw, boolean empty)
            {
                super.updateItem(bw, empty);
                setText(empty || bw == null ? null
                    : String.format(Locale.ROOT, "%.1f kHz", bw / 1000.0));
            }
        });
        bwCol.setPrefWidth(80);

        // --- Column: Detected decoder + kind ---
        TableColumn<Discovery, DecoderType> decoderCol = new TableColumn<>("Detected");
        decoderCol.setCellValueFactory(f -> f.getValue().detectedDecoderProperty());
        decoderCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(DecoderType dt, boolean empty)
            {
                super.updateItem(dt, empty);

                if(empty)
                {
                    setText(null);
                    return;
                }

                int idx = getIndex();
                if(idx < 0 || idx >= getTableView().getItems().size())
                {
                    setText(null);
                    return;
                }

                Discovery d = getTableView().getItems().get(idx);
                SignalKind kind = d.getKind();

                if(dt == null)
                {
                    setText(null);
                    return;
                }

                String kindStr = kind == null ? "" : switch(kind)
                {
                    case CONTROL      -> " · control";
                    case DATA         -> " · data";
                    case CONVENTIONAL -> " · conventional";
                    case TRAFFIC      -> " · traffic";
                    case UNKNOWN      -> "";
                };

                setText(dt.getShortDisplayString() + kindStr);
            }
        });
        decoderCol.setPrefWidth(160);

        // --- Column: Confidence pips ---
        TableColumn<Discovery, Integer> confCol = new TableColumn<>("Conf");
        confCol.setCellValueFactory(f -> f.getValue().confidenceProperty().asObject());
        confCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Integer conf, boolean empty)
            {
                super.updateItem(conf, empty);

                if(empty || conf == null)
                {
                    setText(null);
                    return;
                }

                int pips = Math.max(0, Math.min(4, conf));
                setText("●".repeat(pips) + "○".repeat(4 - pips));
            }
        });
        confCol.setPrefWidth(60);

        // --- Column: Power / SNR ---
        TableColumn<Discovery, Double> powerCol = new TableColumn<>("Power/SNR");
        powerCol.setCellValueFactory(f ->
            new ReadOnlyObjectWrapper<>(f.getValue().getPowerDb()));
        powerCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Double pwr, boolean empty)
            {
                super.updateItem(pwr, empty);

                if(empty || pwr == null)
                {
                    setText(null);
                    return;
                }

                int idx = getIndex();
                if(idx < 0 || idx >= getTableView().getItems().size())
                {
                    setText(null);
                    return;
                }

                Discovery d = getTableView().getItems().get(idx);
                setText(String.format(Locale.ROOT, "%.1f / %.1f dB", pwr, d.getSnrDb()));
            }
        });
        powerCol.setPrefWidth(120);

        // --- Column: First seen ---
        TableColumn<Discovery, Instant> firstSeenCol = new TableColumn<>("First seen");
        firstSeenCol.setCellValueFactory(f -> f.getValue().firstSeenProperty());
        firstSeenCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Instant t, boolean empty)
            {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : TIME_FMT.format(t));
            }
        });
        firstSeenCol.setPrefWidth(75);

        // --- Column: Last seen ---
        TableColumn<Discovery, Instant> lastSeenCol = new TableColumn<>("Last seen");
        lastSeenCol.setCellValueFactory(f -> f.getValue().lastSeenProperty());
        lastSeenCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Instant t, boolean empty)
            {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : TIME_FMT.format(t));
            }
        });
        lastSeenCol.setPrefWidth(75);

        // --- Column: Notes (metadata summary) ---
        // Binds to metadataVersionProperty() so the cell re-renders whenever metadata changes.
        TableColumn<Discovery, String> notesCol = new TableColumn<>("Notes");
        notesCol.setCellValueFactory(f -> {
            Discovery d = f.getValue();
            return Bindings.createStringBinding(
                () -> d.getMetadata().entrySet().stream()
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", ")),
                d.metadataVersionProperty());
        });
        notesCol.setPrefWidth(200);

        // --- Column: Actions (+ / save / remove / watch / ignore / reprobe) ---
        // Using Discovery as the cell value so that updateItem re-fires when the row's
        // observable properties change (state, createdChannel, watched).
        TableColumn<Discovery, Discovery> actionsCol = new TableColumn<>("Actions");
        actionsCol.setCellValueFactory(f -> new ReadOnlyObjectWrapper<>(f.getValue()));
        actionsCol.setCellFactory(col -> new TableCell<>()
        {
            private final Button mAddBtn    = new Button("+");
            private final Button mSaveBtn   = new Button("Save");
            private final Button mRemoveBtn = new Button("Remove");
            private final Button mWatchBtn  = new Button("👁");
            private final Button mIgnoreBtn = new Button("✕");
            private final Button mReprobeBtn= new Button("↻");
            private final HBox mBox = new HBox(2, mAddBtn, mSaveBtn, mRemoveBtn, mWatchBtn, mIgnoreBtn, mReprobeBtn);

            {
                mAddBtn.setTooltip(new Tooltip("Add as channel"));
                mSaveBtn.setTooltip(new Tooltip("Save live channel to playlist"));
                mRemoveBtn.setTooltip(new Tooltip("Remove live channel"));
                mWatchBtn.setTooltip(new Tooltip("Toggle watched"));
                mIgnoreBtn.setTooltip(new Tooltip("Ignore this frequency"));
                mReprobeBtn.setTooltip(new Tooltip("Re-probe"));

                mAddBtn.setOnAction(e -> {
                    Discovery d = getDiscovery();
                    if(d != null) mBandScanController.addAsChannel(d);
                });
                mSaveBtn.setOnAction(e -> {
                    Discovery d = getDiscovery();
                    if(d != null) mBandScanController.saveCreatedChannel(d);
                });
                mRemoveBtn.setOnAction(e -> {
                    Discovery d = getDiscovery();
                    if(d != null) mBandScanController.removeCreatedChannel(d);
                });
                mWatchBtn.setOnAction(e -> {
                    Discovery d = getDiscovery();
                    if(d != null) mBandScanController.setWatched(d, !d.isWatched());
                });
                mIgnoreBtn.setOnAction(e -> {
                    Discovery d = getDiscovery();
                    if(d != null) mBandScanController.ignore(d);
                });
                mReprobeBtn.setOnAction(e -> {
                    Discovery d = getDiscovery();
                    if(d != null) mBandScanController.reprobe(d);
                });
            }

            private Discovery getDiscovery()
            {
                // Prefer the cell's item (set by cellValueFactory) for correctness
                Discovery d = getItem();
                if(d != null) return d;
                int index = getIndex();
                if(index < 0 || index >= getTableView().getItems().size()) return null;
                return getTableView().getItems().get(index);
            }

            @Override
            protected void updateItem(Discovery item, boolean empty)
            {
                super.updateItem(item, empty);

                if(empty)
                {
                    setGraphic(null);
                    return;
                }

                Discovery d = item != null ? item : getDiscovery();

                if(d == null)
                {
                    setGraphic(null);
                    return;
                }

                // + button: only enabled when IDENTIFIED and not yet added
                mAddBtn.setDisable(d.getState() != DiscoveryState.IDENTIFIED || d.getCreatedChannel() != null);
                boolean hasTemporaryChannel = d.getCreatedChannel() != null && d.getCreatedChannel().isTemporaryLive();
                mSaveBtn.setDisable(!hasTemporaryChannel);
                mRemoveBtn.setDisable(!hasTemporaryChannel);
                // 👁 button: always available
                mWatchBtn.setStyle(d.isWatched() ? "-fx-font-weight:bold;" : "");
                // ✕ button: always available
                mIgnoreBtn.setDisable(false);
                // ↻ button: enabled unless already probing
                mReprobeBtn.setDisable(d.getState() == DiscoveryState.PROBING);

                setGraphic(mBox);
            }
        });
        actionsCol.setPrefWidth(260);
        actionsCol.setSortable(false);

        mTable.getColumns().addAll(stateCol, freqCol, bwCol, decoderCol, confCol,
            powerCol, firstSeenCol, lastSeenCol, notesCol, actionsCol);

        // --- Row right-click context menu ---
        mTable.setRowFactory(tv -> {
            TableRow<Discovery> row = new TableRow<>();

            row.setOnContextMenuRequested(e -> {
                if(row.isEmpty()) return;
                Discovery d = row.getItem();
                buildContextMenu(d).show(row, e.getScreenX(), e.getScreenY());
            });

            return row;
        });

        return mTable;
    }

    private ContextMenu buildContextMenu(Discovery discovery)
    {
        ContextMenu menu = new ContextMenu();

        MenuItem addItem = new MenuItem("+ Add as channel");
        addItem.setDisable(discovery.getState() != DiscoveryState.IDENTIFIED
            || discovery.getCreatedChannel() != null);
        addItem.setOnAction(e -> mBandScanController.addAsChannel(discovery));

        MenuItem watchItem = new MenuItem(discovery.isWatched() ? "Unwatch" : "Watch");
        watchItem.setOnAction(e -> mBandScanController.setWatched(discovery, !discovery.isWatched()));

        MenuItem ignoreItem = new MenuItem("Ignore");
        ignoreItem.setOnAction(e -> mBandScanController.ignore(discovery));

        MenuItem reprobeItem = new MenuItem("Re-probe");
        reprobeItem.setDisable(discovery.getState() == DiscoveryState.PROBING);
        reprobeItem.setOnAction(e -> mBandScanController.reprobe(discovery));

        menu.getItems().addAll(addItem, watchItem, ignoreItem, reprobeItem);

        // "View/Edit channel" only when channel has been created
        Channel created = discovery.getCreatedChannel();
        if(created != null)
        {
            menu.getItems().add(new SeparatorMenuItem());

            if(created.isTemporaryLive())
            {
                MenuItem saveChannelItem = new MenuItem("Save live channel to playlist");
                saveChannelItem.setOnAction(e -> mBandScanController.saveCreatedChannel(discovery));
                menu.getItems().add(saveChannelItem);

                MenuItem removeChannelItem = new MenuItem("Remove live channel");
                removeChannelItem.setOnAction(e -> mBandScanController.removeCreatedChannel(discovery));
                menu.getItems().add(removeChannelItem);
            }

            MenuItem viewChannelItem = new MenuItem("View/Edit channel");
            viewChannelItem.setOnAction(e ->
                MyEventBus.getGlobalEventBus().post(new ViewChannelRequest(created)));
            menu.getItems().add(viewChannelItem);
        }

        return menu;
    }

    // -------------------------------------------------------------------------
    // Dialog openers
    // -------------------------------------------------------------------------

    private void openScanDialog()
    {
        long minHz = mPreFillMinHz;
        long maxHz = mPreFillMaxHz;
        // Clear the pre-fill after use
        mPreFillMinHz = 0;
        mPreFillMaxHz = 0;

        ScanDialog dialog = new ScanDialog(mBandScanController, mDiscoveryPreference, minHz, maxHz);
        dialog.show();
    }

    private void openSettings()
    {
        io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest req =
            new io.github.dsheirer.gui.preference.ViewUserPreferenceEditorRequest(
                io.github.dsheirer.gui.preference.PreferenceEditorType.DISCOVERY);
        MyEventBus.getGlobalEventBus().post(req);
    }

    private void openManageIgnored()
    {
        ManageIgnoreListDialog dialog = new ManageIgnoreListDialog(mDiscoveryPreference);
        dialog.show();
    }
}
