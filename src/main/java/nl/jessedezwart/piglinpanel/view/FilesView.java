package nl.jessedezwart.piglinpanel.view;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
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
    FileEntry rootEntry = new FileEntry(
        "",
        profile.getRootPath().isBlank() ? "Server root" : profile.getRootPath(),
        true,
        0,
        Instant.EPOCH);
    FileTreeItem rootItem = new FileTreeItem(new FileNode(rootEntry, false));
    TreeView<FileNode> files = new TreeView<>(rootItem);
    TextArea editor = ViewLayout.editor(true);
    TextField currentPath = new TextField("");
    Button open = new Button("Open");
    Button download = new Button("Download");
    Button save = new Button("Save");
    final String[] selectedFile = { null };
    final String[] selectedName = { "" };
    final String[] originalText = { "" };
    final byte[][] selectedBytes = { null };
    BooleanProperty dirty = new SimpleBooleanProperty(false);
    save.disableProperty().bind(dirty.not());
    download.setVisible(false);
    download.managedProperty().bind(download.visibleProperty());
    files.setShowRoot(true);
    files.setCellFactory(
        ignored -> new TreeCell<>() {
          @Override
          protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
              setText(null);
              return;
            }
            if (item.placeholder()) {
              setText("Loading...");
              return;
            }
            setText(item.entry().name());
          }
        });
    files
        .getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (ignored, oldValue, selected) -> {
              if (selected == null || selected.getValue().placeholder()) {
                return;
              }
              FileEntry entry = selected.getValue().entry();
              currentPath.setText(entry.path());
              selectedName[0] = entry.name();
              if (!entry.directory()) {
                selectedFile[0] = entry.path();
                openFile(profile, entry, editor, originalText, dirty, download, selectedBytes);
              } else {
                download.setVisible(false);
              }
            });
    open.setOnAction(
        event -> {
          TreeItem<FileNode> selected = files.getSelectionModel().getSelectedItem();
          if (selected == null || selected.getValue().placeholder()) {
            return;
          }
          FileEntry entry = selected.getValue().entry();
          if (entry.directory()) {
            selected.setExpanded(true);
            loadChildren(profile, selected);
            download.setVisible(false);
          } else {
            selectedFile[0] = entry.path();
            selectedName[0] = entry.name();
            openFile(profile, entry, editor, originalText, dirty, download, selectedBytes);
          }
        });
    download.setOnAction(
        event -> {
          if (selectedFile[0] == null || selectedBytes[0] == null) {
            return;
          }
          FileChooser chooser = new FileChooser();
          chooser.setInitialFileName(selectedName[0]);
          java.io.File target = chooser.showSaveDialog(editor.getScene().getWindow());
          if (target == null) {
            return;
          }
          UiTasks.runVoid(
              () -> Files.write(target.toPath(), selectedBytes[0]),
              () -> {
              });
        });
    save.setOnAction(
        event -> {
          if (selectedFile[0] != null) {
            UiTasks.runVoid(
                () -> fileService.saveText(profile, selectedFile[0], editor.getText()),
                () -> {
                  originalText[0] = editor.getText();
                  dirty.set(false);
                });
          }
        });
    editor
        .textProperty()
        .addListener(
            (ignored, oldValue, newValue) -> dirty.set(!newValue.equals(originalText[0])));
    files.setOnMouseClicked(
        event -> {
          if (event.getClickCount() == 2) {
            open.fire();
          }
        });
    rootItem.setExpanded(true);
    loadChildren(profile, rootItem);
    SplitPane split = new SplitPane(files, editor);
    split.setDividerPositions(0.32);
    VBox box = ViewLayout.contentBox(new HBox(8, currentPath, open, download, save), split);
    HBox.setHgrow(currentPath, Priority.ALWAYS);
    VBox.setVgrow(split, Priority.ALWAYS);
    return box;
  }

  private void loadChildren(ServerProfile profile, TreeItem<FileNode> item) {
    if (!(item instanceof FileTreeItem treeItem)
        || treeItem.loaded
        || item.getValue().placeholder()) {
      return;
    }
    treeItem.loaded = true;
    UiTasks.run(
        () -> fileService.list(profile, item.getValue().entry().path()),
        entries -> {
          item.getChildren().setAll(entries.stream().map(this::treeItem).toList());
          item.getChildren()
              .forEach(
                  child -> child.expandedProperty()
                      .addListener(
                          (ignored, wasExpanded, expanded) -> {
                            if (expanded) {
                              loadChildren(profile, child);
                            }
                          }));
        });
  }

  private FileTreeItem treeItem(FileEntry entry) {
    FileTreeItem item = new FileTreeItem(new FileNode(entry, false));
    if (entry.directory()) {
      item.getChildren()
          .add(
              new TreeItem<>(
                  new FileNode(new FileEntry("", "", false, 0, Instant.EPOCH), true)));
    }
    return item;
  }

  private static final class FileTreeItem extends TreeItem<FileNode> {
    private boolean loaded;

    private FileTreeItem(FileNode node) {
      super(node);
    }
  }

  private record FileNode(FileEntry entry, boolean placeholder) {
  }

  private void setEditorText(
      TextArea editor,
      String text,
      String[] originalText,
      BooleanProperty dirty) {
    String safe = text == null ? "" : text;
    editor.setText(safe);
    originalText[0] = safe;
    dirty.set(false);
  }

  private void openFile(
      ServerProfile profile,
      FileEntry entry,
      TextArea editor,
      String[] originalText,
      BooleanProperty dirty,
      Button download,
      byte[][] selectedBytes) {
    UiTasks.run(
        () -> fileService.readBytes(profile, entry.path()),
        bytes -> {
          selectedBytes[0] = bytes;
          if (isLikelyText(bytes)) {
            editor.setEditable(true);
            download.setVisible(false);
            setEditorText(editor, new String(bytes, StandardCharsets.UTF_8), originalText, dirty);
          } else {
            editor.setEditable(false);
            setEditorText(editor, "Binary file. Use Download.", originalText, dirty);
            download.setVisible(true);
          }
        });
  }

  private boolean isLikelyText(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return true;
    }
    int suspicious = 0;
    int checked = Math.min(bytes.length, 4096);
    for (int i = 0; i < checked; i++) {
      int value = bytes[i] & 0xFF;
      if (value == 0) {
        return false;
      }
      if (value < 0x09) {
        suspicious++;
      } else if (value > 0x0D && value < 0x20) {
        suspicious++;
      }
    }
    return suspicious < checked * 0.2;
  }
}
