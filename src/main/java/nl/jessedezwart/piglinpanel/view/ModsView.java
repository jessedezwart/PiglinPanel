package nl.jessedezwart.piglinpanel.view;

import java.nio.file.Files;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
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
    ProgressIndicator progress = new ProgressIndicator();
    progress.setVisible(false);
    progress.setManaged(false);
    progress.setPrefSize(18, 18);
    progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
    Button checkUpdates = new Button("Check updates");
    Button update = new Button("Update selected");
    Button delete = new Button("Delete selected");
    Button upload = new Button("Upload jar");
    checkUpdates.setOnAction(event -> loadMods(profile, true, installed, archived, progress));
    update.setOnAction(
        event -> {
          ModFile selected = installed.getSelectionModel().getSelectedItem();
          if (selected != null) {
            log.info("Applying update to {} in {}", selected.fileName(), profile.getName());
            progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            progress.setVisible(true);
            UiTasks.run(
                () -> {
                  modService.applyUpdate(profile, selected);
                  return null;
                },
                ignored -> loadMods(profile, false, installed, archived, progress),
                error -> progress.setVisible(false),
                null);
          }
        });
    delete.setOnAction(
        event -> {
          ModFile selected = installed.getSelectionModel().getSelectedItem();
          if (selected != null) {
            log.info("Deleting mod {} from {}", selected.fileName(), profile.getName());
            UiTasks.run(
                () -> {
                  modService.delete(profile, selected);
                  return null;
                },
                ignored -> loadMods(profile, false, installed, archived, progress),
                error -> progress.setVisible(false),
                null);
          }
        });
    upload.setOnAction(event -> uploadMod(context, installed, archived, progress));
    TabPane tabs = new TabPane(new Tab("Installed", installed), new Tab("Archived", archived));
    tabs.getTabs().forEach(tab -> tab.setClosable(false));
    progress.managedProperty().bind(progress.visibleProperty());
    VBox box = ViewLayout.contentBox(
        new HBox(8, checkUpdates, update, delete, upload, progress), tabs);
    VBox.setVgrow(tabs, Priority.ALWAYS);
    loadMods(profile, false, installed, archived, progress);
    return box;
  }

  private TableView<ModFile> modTable() {
    TableView<ModFile> table = new TableView<>();
    table.getColumns().add(column("Folder", ModFile::folderPath));
    table.getColumns().add(column("File", ModFile::fileName));
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
      ProgressIndicator progress) {
    log.info("Loading mods for {} (checkUpdates={})", profile.getName(), checkUpdates);
    if (checkUpdates) {
      progress.setVisible(true);
      progress.setProgress(0);
    }
    UiTasks.run(
        () -> modService.scan(
            profile,
            checkUpdates,
            (done, total) -> {
              if (!checkUpdates) {
                return;
              }
              double value = total == 0 ? 1.0 : (double) done / total;
              Platform.runLater(() -> progress.setProgress(value));
            }),
        mods -> {
          installed.getItems().setAll(mods.stream().filter(mod -> !mod.archived()).toList());
          archived.getItems().setAll(mods.stream().filter(ModFile::archived).toList());
          log.info(
              "Loaded {} installed and {} archived mods for {}",
              installed.getItems().size(),
              archived.getItems().size(),
              profile.getName());
        },
        null,
        () -> {
          if (checkUpdates || progress.isVisible()) {
            progress.setVisible(false);
          }
        });
  }

  private void uploadMod(
      Context context,
      TableView<ModFile> installed,
      TableView<ModFile> archived,
      ProgressIndicator progress) {
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
        ignored -> loadMods(profile, false, installed, archived, progress),
        error -> progress.setVisible(false),
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
