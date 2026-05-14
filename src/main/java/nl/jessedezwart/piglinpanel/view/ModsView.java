package nl.jessedezwart.piglinpanel.view;

import java.nio.file.Files;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
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
  private final ModService modService;

  ModsView(ModService modService) {
    this.modService = modService;
  }

  Node create(Context context) {
    ServerProfile profile = context.profile();
    TableView<ModFile> installed = modTable();
    TableView<ModFile> archived = modTable();
    installed.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
    Button checkUpdates = new Button("Check updates");
    Button update = new Button("Update selected");
    Button delete = new Button("Delete selected");
    Button upload = new Button("Upload jar");
    checkUpdates.setOnAction(event -> loadMods(profile, true, installed, archived, checkUpdates));
    update.setOnAction(
        event -> {
          List<ModFile> selected = List.copyOf(installed.getSelectionModel().getSelectedItems());
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
          List<ModFile> selected = List.copyOf(installed.getSelectionModel().getSelectedItems());
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

  private TableView<ModFile> modTable() {
    TableView<ModFile> table = new TableView<>();
    table.getColumns().add(column("Folder", ModFile::folderPath));
    table.getColumns().add(column("File", ModFile::fileName));
    table.getColumns().add(column("SHA-1", mod -> mod.sha1() == null ? "" : mod.sha1()));
    table
        .getColumns()
        .add(
            column(
                "Update",
                mod -> mod.update() == null || !mod.update().available()
                    ? ""
                    : mod.update().latestVersionName()));
    table
        .getColumns()
        .add(column("Project", mod -> mod.update() == null ? "" : mod.update().projectName()));
    return table;
  }

  private void loadMods(
      ServerProfile profile,
      boolean checkUpdates,
      TableView<ModFile> installed,
      TableView<ModFile> archived,
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
            installed.getItems().setAll(mods.stream().filter(mod -> !mod.archived()).toList());
            archived.getItems().setAll(mods.stream().filter(ModFile::archived).toList());
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

  private void addMod(TableView<ModFile> installed, TableView<ModFile> archived, ModFile mod) {
    if (mod.archived()) {
      archived.getItems().add(mod);
    } else {
      installed.getItems().add(mod);
    }
  }

  private void uploadMod(
      Context context,
      TableView<ModFile> installed,
      TableView<ModFile> archived,
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

  private TableColumn<ModFile, String> column(String title, Value value) {
    TableColumn<ModFile, String> column = new TableColumn<>(title);
    column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.read(data.getValue())));
    column.setPrefWidth(180);
    return column;
  }

  record Context(ServerProfile profile, Node owner) {
  }

  private interface Value {
    String read(ModFile modFile);
  }
}
