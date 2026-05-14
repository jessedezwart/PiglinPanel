package nl.jessedezwart.piglinpanel.view;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.FileService;
import org.springframework.stereotype.Component;

@Component
class FilesView {
  private final FileService fileService;

  FilesView(FileService fileService) {
    this.fileService = fileService;
  }

  Node create(ServerProfile profile) {
    ListView<FileEntry> files = new ListView<>();
    TextArea editor = ViewLayout.editor(true);
    TextField currentPath = new TextField("");
    Button open = new Button("Open");
    Button up = new Button("Up");
    Button save = new Button("Save");
    final String[] selectedFile = {null};
    Runnable load =
        () ->
            UiTasks.run(
                () -> fileService.list(profile, currentPath.getText()),
                entries -> files.getItems().setAll(entries));
    open.setOnAction(
        event -> {
          FileEntry selected = files.getSelectionModel().getSelectedItem();
          if (selected == null) {
            return;
          }
          if (selected.directory()) {
            currentPath.setText(selected.path());
            load.run();
          } else {
            selectedFile[0] = selected.path();
            UiTasks.run(() -> fileService.readText(profile, selected.path()), editor::setText);
          }
        });
    up.setOnAction(
        event -> {
          String path = currentPath.getText();
          int slash = path.lastIndexOf('/');
          currentPath.setText(slash < 0 ? "" : path.substring(0, slash));
          load.run();
        });
    save.setOnAction(
        event -> {
          if (selectedFile[0] != null) {
            UiTasks.runVoid(
                () -> fileService.saveText(profile, selectedFile[0], editor.getText()), () -> {});
          }
        });
    files.setOnMouseClicked(
        event -> {
          if (event.getClickCount() == 2) {
            open.fire();
          }
        });
    SplitPane split = new SplitPane(files, editor);
    split.setDividerPositions(0.32);
    VBox box = ViewLayout.contentBox(new HBox(8, up, currentPath, open, save), split);
    HBox.setHgrow(currentPath, Priority.ALWAYS);
    VBox.setVgrow(split, Priority.ALWAYS);
    load.run();
    return box;
  }
}
