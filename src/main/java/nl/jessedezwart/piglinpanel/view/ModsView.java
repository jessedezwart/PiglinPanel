package nl.jessedezwart.piglinpanel.view;

import java.nio.file.Files;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.Button;
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
import org.springframework.stereotype.Component;

@Component
class ModsView {
  private final ModService modService;

  ModsView(ModService modService) {
    this.modService = modService;
  }

  Node create(Context context) {
    ServerProfile profile = context.profile();
    TableView<ModFile> installed = modTable();
    TableView<ModFile> archived = modTable();
    Button scan = new Button("Scan");
    Button checkUpdates = new Button("Check updates");
    Button update = new Button("Update selected");
    Button delete = new Button("Delete selected");
    Button upload = new Button("Upload jar");
    scan.setOnAction(event -> loadMods(profile, false, installed, archived));
    checkUpdates.setOnAction(event -> loadMods(profile, true, installed, archived));
    update.setOnAction(
        event -> {
          ModFile selected = installed.getSelectionModel().getSelectedItem();
          if (selected != null) {
            UiTasks.runVoid(
                () -> modService.applyUpdate(profile, selected),
                () -> loadMods(profile, false, installed, archived));
          }
        });
    delete.setOnAction(
        event -> {
          ModFile selected = installed.getSelectionModel().getSelectedItem();
          if (selected != null) {
            UiTasks.runVoid(
                () -> modService.delete(profile, selected),
                () -> loadMods(profile, false, installed, archived));
          }
        });
    upload.setOnAction(event -> uploadMod(context, installed, archived));
    TabPane tabs = new TabPane(new Tab("Installed", installed), new Tab("Archived", archived));
    tabs.getTabs().forEach(tab -> tab.setClosable(false));
    VBox box = ViewLayout.contentBox(new HBox(8, scan, checkUpdates, update, delete, upload), tabs);
    VBox.setVgrow(tabs, Priority.ALWAYS);
    loadMods(profile, false, installed, archived);
    return box;
  }

  private TableView<ModFile> modTable() {
    TableView<ModFile> table = new TableView<>();
    table.getColumns().add(column("Folder", ModFile::folderPath));
    table.getColumns().add(column("File", ModFile::fileName));
    table
        .getColumns()
        .add(column("Update", mod -> mod.update() == null ? "" : mod.update().latestVersionName()));
    table
        .getColumns()
        .add(column("Project", mod -> mod.update() == null ? "" : mod.update().projectName()));
    return table;
  }

  private void loadMods(
      ServerProfile profile,
      boolean checkUpdates,
      TableView<ModFile> installed,
      TableView<ModFile> archived) {
    UiTasks.run(
        () -> modService.scan(profile, checkUpdates),
        mods -> {
          installed.getItems().setAll(mods.stream().filter(mod -> !mod.archived()).toList());
          archived.getItems().setAll(mods.stream().filter(ModFile::archived).toList());
        });
  }

  private void uploadMod(
      Context context, TableView<ModFile> installed, TableView<ModFile> archived) {
    ServerProfile profile = context.profile();
    FileChooser chooser = new FileChooser();
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Jar files", "*.jar"));
    java.io.File file = chooser.showOpenDialog(context.owner().getScene().getWindow());
    if (file == null || profile.getModFolders().isEmpty()) {
      return;
    }
    ModFolder folder = profile.getModFolders().getFirst();
    UiTasks.runVoid(
        () -> modService.upload(profile, folder, file.getName(), Files.readAllBytes(file.toPath())),
        () -> loadMods(profile, false, installed, archived));
  }

  private TableColumn<ModFile, String> column(String title, Value value) {
    TableColumn<ModFile, String> column = new TableColumn<>(title);
    column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.read(data.getValue())));
    column.setPrefWidth(180);
    return column;
  }

  record Context(ServerProfile profile, Node owner) {}

  private interface Value {
    String read(ModFile modFile);
  }
}
