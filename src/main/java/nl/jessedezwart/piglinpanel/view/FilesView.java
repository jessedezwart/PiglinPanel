package nl.jessedezwart.piglinpanel.view;

import java.time.Instant;
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
    FileEntry rootEntry =
        new FileEntry(
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
    Button up = new Button("Up");
    Button save = new Button("Save");
    final String[] selectedFile = {null};
    files.setShowRoot(true);
    files.setCellFactory(
        ignored ->
            new TreeCell<>() {
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
                setText((item.entry().directory() ? "[DIR] " : "") + item.entry().name());
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
              currentPath.setText(selected.getValue().entry().path());
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
          } else {
            selectedFile[0] = entry.path();
            UiTasks.run(() -> fileService.readText(profile, entry.path()), editor::setText);
          }
        });
    up.setOnAction(
        event -> {
          TreeItem<FileNode> selected = files.getSelectionModel().getSelectedItem();
          if (selected != null && selected.getParent() != null) {
            files.getSelectionModel().select(selected.getParent());
          }
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
    rootItem.setExpanded(true);
    loadChildren(profile, rootItem);
    SplitPane split = new SplitPane(files, editor);
    split.setDividerPositions(0.32);
    VBox box = ViewLayout.contentBox(new HBox(8, up, currentPath, open, save), split);
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
                  child ->
                      child.expandedProperty()
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

  private record FileNode(FileEntry entry, boolean placeholder) {}
}
