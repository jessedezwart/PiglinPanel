package nl.jessedezwart.piglinpanel.view;

import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.ModService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class ModsView {
  private static final Logger log = LoggerFactory.getLogger(ModsView.class);
  private static final DateTimeFormatter LAST_UPDATED_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
  private final ModService modService;

  ModsView(ModService modService) {
    this.modService = modService;
  }

  Node create(Context context) {
    ServerProfile profile = context.profile();
    final TableView<ModRow> installed = modTable(true);
    final TableView<ModRow> archived = modTable(true);
    final Button checkUpdates = new Button("Check updates");
    final Button update = new Button("Update");
    final Button delete = new Button("Delete");
    final Button upload = new Button("Upload jar");
    checkUpdates.setOnAction(event -> loadMods(profile, true, installed, archived, checkUpdates));
    update.setOnAction(
        event -> {
          List<ModFile> selected = installed.getItems().stream()
              .filter(ModRow::selected)
              .map(ModRow::mod)
              .filter(mod -> mod.update() != null && mod.update().available())
              .toList();
          if (selected.isEmpty()) {
            return;
          }
          log.info("Applying updates to {} mods in {}", selected.size(), profile.getName());
          UiTasks.run(
              () -> {
                for (ModFile mod : selected) {
                  modService.applyUpdate(profile, mod);
                }
                return null;
              },
              ignored -> loadMods(profile, false, installed, archived, checkUpdates),
              null,
              null);
        });
    delete.setOnAction(
        event -> {
          List<ModFile> selected = selectedMods(installed, archived).stream()
              .map(ModRow::mod)
              .toList();
          if (selected.isEmpty()) {
            return;
          }
          log.info("Deleting {} mods from {}", selected.size(), profile.getName());
          UiTasks.run(
              () -> {
                for (ModFile mod : selected) {
                  modService.delete(profile, mod);
                }
                return null;
              },
              ignored -> loadMods(profile, false, installed, archived, checkUpdates),
              null,
              null);
        });
    upload.setOnAction(event -> uploadMod(context, installed, archived, checkUpdates));
    TabPane tabs = new TabPane(new Tab("Installed", installed), new Tab("Archived", archived));
    tabs.getTabs().forEach(tab -> tab.setClosable(false));
    VBox box = ViewLayout.contentBox(
        new HBox(8, checkUpdates, update, delete, upload), tabs);
    VBox.setVgrow(tabs, Priority.ALWAYS);
    loadMods(profile, false, installed, archived, checkUpdates);
    return box;
  }

  private TableView<ModRow> modTable(boolean allowSelection) {
    TableView<ModRow> table = new TableView<>();
    table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    if (allowSelection) {
      table.setEditable(true);
      table.getColumns().add(selectionColumn(table));
    }
    table.getColumns().add(column("Folder", row -> row.mod().folderPath()));
    table.getColumns().add(column("File", row -> row.mod().fileName()));
    table
        .getColumns()
        .add(column("SHA-1", row -> row.mod().sha1() == null ? "" : row.mod().sha1()));
    table
        .getColumns()
        .add(
            column(
                "Update",
                row -> row.mod().update() == null || !row.mod().update().available()
                    ? ""
                    : row.mod().update().latestVersionName()));
    table
        .getColumns()
        .add(
            column(
                "Last Updated",
                row -> formatInstant(
                    row.mod().update() == null ? null : row.mod().update().lastUpdatedAt())));
    table
        .getColumns()
        .add(
            column(
                "Project",
                row -> row.mod().update() == null ? "" : row.mod().update().projectName()));
    return table;
  }

  private void loadMods(
      ServerProfile profile,
      boolean checkUpdates,
      TableView<ModRow> installed,
      TableView<ModRow> archived,
      Button checkUpdatesButton) {
    log.info("Loading mods for {} (checkUpdates={})", profile.getName(), checkUpdates);
    if (checkUpdates) {
      checkUpdatesButton.setDisable(true);
      installed.getItems().clear();
      archived.getItems().clear();
    }
    UiTasks.run(
        () -> modService.scan(
            profile,
            checkUpdates,
            mod -> {
              if (!checkUpdates) {
                return;
              }
              Platform.runLater(() -> addMod(installed, archived, mod));
            }),
        mods -> {
          if (!checkUpdates) {
            installed.getItems().setAll(mods.stream()
                .filter(mod -> !mod.archived())
                .map(ModRow::new)
                .toList());
            archived.getItems().setAll(mods.stream()
                .filter(ModFile::archived)
                .map(ModRow::new)
                .toList());
          }
          log.info(
              "Loaded {} installed and {} archived mods for {}",
              installed.getItems().size(),
              archived.getItems().size(),
              profile.getName());
        },
        null,
        () -> {
          if (checkUpdatesButton.isDisabled()) {
            checkUpdatesButton.setDisable(false);
          }
        });
  }

  private void addMod(TableView<ModRow> installed, TableView<ModRow> archived, ModFile mod) {
    if (mod.archived()) {
      archived.getItems().add(new ModRow(mod));
    } else {
      installed.getItems().add(new ModRow(mod));
    }
  }

  private void uploadMod(
      Context context,
      TableView<ModRow> installed,
      TableView<ModRow> archived,
      Button checkUpdatesButton) {
    ServerProfile profile = context.profile();
    FileChooser chooser = new FileChooser();
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Jar files", "*.jar"));
    java.io.File file = chooser.showOpenDialog(context.owner().getScene().getWindow());
    if (file == null || profile.getModFolders().isEmpty()) {
      return;
    }
    ModFolder folder = profile.getModFolders().getFirst();
    log.info("Uploading mod {} to {}", file.getName(), profile.getName());
    UiTasks.run(
        () -> {
          modService.upload(profile, folder, file.getName(), Files.readAllBytes(file.toPath()));
          return null;
        },
        ignored -> loadMods(profile, false, installed, archived, checkUpdatesButton),
        null,
        null);
  }

  private TableColumn<ModRow, Boolean> selectionColumn(TableView<ModRow> table) {
    CheckBox selectAll = new CheckBox();
    selectAll.setFocusTraversable(false);
    selectAll.setOnAction(event -> {
      boolean selected = selectAll.isSelected();
      table.getItems().forEach(row -> row.setSelected(selected));
      table.refresh();
    });
    table
        .getItems()
        .addListener((ListChangeListener<ModRow>) change -> syncSelectAll(table, selectAll));
    TableColumn<ModRow, Boolean> column = new TableColumn<>();
    column.setGraphic(selectAll);
    column.setCellValueFactory(data -> data.getValue().selectedProperty());
    column.setCellFactory(
        ignored ->
            new TableCell<>() {
              private final CheckBox checkBox = new CheckBox();

              {
                checkBox.setFocusTraversable(false);
                checkBox.setOnAction(
                    event -> {
                      ModRow row = getTableRow() == null ? null : getTableRow().getItem();
                      if (row != null) {
                        row.setSelected(checkBox.isSelected());
                        syncSelectAll(table, selectAll);
                      }
                    });
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
              }

              @Override
              protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                ModRow row = empty ? null : getTableRow().getItem();
                if (row == null) {
                  setGraphic(null);
                  return;
                }
                checkBox.setSelected(row.selected());
                setGraphic(checkBox);
              }
            });
    column.setEditable(true);
    column.setSortable(false);
    column.setPrefWidth(60);
    return column;
  }

  private List<ModRow> selectedMods(TableView<ModRow> installed, TableView<ModRow> archived) {
    return java.util.stream.Stream
        .concat(installed.getItems().stream(), archived.getItems().stream())
        .filter(ModRow::selected)
        .toList();
  }

  private void syncSelectAll(TableView<ModRow> table, CheckBox selectAll) {
    if (table.getItems().isEmpty()) {
      selectAll.setSelected(false);
      return;
    }
    selectAll.setSelected(table.getItems().stream().allMatch(ModRow::selected));
  }

  private TableColumn<ModRow, String> column(String title, Value value) {
    TableColumn<ModRow, String> column = new TableColumn<>(title);
    column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.read(data.getValue())));
    column.setPrefWidth(180);
    return column;
  }

  private static String formatInstant(Instant instant) {
    if (instant == null) {
      return "";
    }
    return LAST_UPDATED_FORMAT.format(instant);
  }

  record Context(ServerProfile profile, Node owner) {
  }

  private static final class ModRow {
    private final ModFile mod;
    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    private ModRow(ModFile mod) {
      this.mod = mod;
    }

    private ModFile mod() {
      return mod;
    }

    private BooleanProperty selectedProperty() {
      return selected;
    }

    private boolean selected() {
      return selected.get();
    }

    private void setSelected(boolean selected) {
      this.selected.set(selected);
    }
  }

  private interface Value {
    String read(ModRow modRow);
  }
}
