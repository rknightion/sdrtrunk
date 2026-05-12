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

import io.github.dsheirer.module.discovery.IgnoreRange;
import io.github.dsheirer.preference.discovery.DiscoveryPreference;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Small modal dialog for viewing and removing entries from the discovery ignore list.
 *
 * <p>The ignore list is sourced from {@link DiscoveryPreference#getIgnoreList()} and is
 * displayed in a {@link TableView}.  Selecting a row and clicking "Remove" calls
 * {@link DiscoveryPreference#removeIgnoreRange(IgnoreRange)} and refreshes the table.</p>
 */
public class ManageIgnoreListDialog extends Stage
{
    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final DiscoveryPreference mDiscoveryPreference;
    private final ObservableList<IgnoreRange> mItems;
    private TableView<IgnoreRange> mTable;
    private Button mRemoveButton;

    /**
     * Constructs the dialog.
     *
     * @param discoveryPreference the preference holding the ignore list
     */
    public ManageIgnoreListDialog(DiscoveryPreference discoveryPreference)
    {
        mDiscoveryPreference = discoveryPreference;

        // Create an observable copy of the current ignore list so the TableView is live
        mItems = FXCollections.observableArrayList(discoveryPreference.getIgnoreList());

        initModality(Modality.APPLICATION_MODAL);
        setTitle("Manage Ignored Frequencies");
        setResizable(true);

        Scene scene = new Scene(buildContent(), 560, 400);
        setScene(scene);
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private BorderPane buildContent()
    {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(8));

        root.setTop(new Label("Frequency ranges ignored during band scans:"));
        BorderPane.setMargin(root.getTop(), new Insets(0, 0, 6, 0));

        mTable = buildTable();
        root.setCenter(mTable);

        root.setBottom(buildButtonBar());
        BorderPane.setMargin(root.getBottom(), new Insets(6, 0, 0, 0));

        return root;
    }

    private TableView<IgnoreRange> buildTable()
    {
        TableView<IgnoreRange> table = new TableView<>(mItems);
        table.setPlaceholder(new Label("No ignored ranges."));

        TableColumn<IgnoreRange, Long> minCol = new TableColumn<>("Min (MHz)");
        minCol.setCellValueFactory(f ->
            new javafx.beans.property.ReadOnlyObjectWrapper<>(f.getValue().minHz()));
        minCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Long v, boolean empty)
            {
                super.updateItem(v, empty);
                setText(empty || v == null ? null
                    : String.format(Locale.ROOT, "%.5f", v / 1e6));
            }
        });
        minCol.setPrefWidth(110);

        TableColumn<IgnoreRange, Long> maxCol = new TableColumn<>("Max (MHz)");
        maxCol.setCellValueFactory(f ->
            new javafx.beans.property.ReadOnlyObjectWrapper<>(f.getValue().maxHz()));
        maxCol.setCellFactory(col -> new TableCell<>()
        {
            @Override
            protected void updateItem(Long v, boolean empty)
            {
                super.updateItem(v, empty);
                setText(empty || v == null ? null
                    : String.format(Locale.ROOT, "%.5f", v / 1e6));
            }
        });
        maxCol.setPrefWidth(110);

        TableColumn<IgnoreRange, String> noteCol = new TableColumn<>("Note");
        noteCol.setCellValueFactory(f ->
            new javafx.beans.property.ReadOnlyStringWrapper(
                f.getValue().note() != null ? f.getValue().note() : ""));
        noteCol.setPrefWidth(180);

        TableColumn<IgnoreRange, String> addedCol = new TableColumn<>("Added");
        addedCol.setCellValueFactory(f -> {
            java.time.Instant added = f.getValue().addedAt();
            return new javafx.beans.property.ReadOnlyStringWrapper(
                added != null ? DATE_FMT.format(added) : "");
        });
        addedCol.setPrefWidth(130);

        table.getColumns().addAll(minCol, maxCol, noteCol, addedCol);

        // Enable/disable remove button based on selection
        table.getSelectionModel().selectedItemProperty().addListener(
            (obs, o, n) -> mRemoveButton.setDisable(n == null));

        return table;
    }

    private HBox buildButtonBar()
    {
        mRemoveButton = new Button("Remove selected");
        mRemoveButton.setDisable(true);
        mRemoveButton.setOnAction(e -> removeSelected());

        Button closeButton = new Button("Close");
        closeButton.setDefaultButton(true);
        closeButton.setOnAction(e -> close());

        return new HBox(8, mRemoveButton, closeButton);
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void removeSelected()
    {
        IgnoreRange selected = mTable.getSelectionModel().getSelectedItem();

        if(selected == null)
        {
            return;
        }

        // Persist the removal through the preference
        mDiscoveryPreference.removeIgnoreRange(selected);

        // Refresh the observable list from the now-updated preference
        mItems.setAll(mDiscoveryPreference.getIgnoreList());
    }
}
