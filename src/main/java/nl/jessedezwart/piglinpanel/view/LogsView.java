package nl.jessedezwart.piglinpanel.view;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.FileService;
import nl.jessedezwart.piglinpanel.service.LogService;
import org.springframework.stereotype.Component;

@Component
class LogsView {
  private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
      .withZone(ZoneId.systemDefault());
  private final FileService fileService;
  private final LogService logService;

  LogsView(FileService fileService, LogService logService) {
    this.fileService = fileService;
    this.logService = logService;
  }

  Node create(ServerProfile profile, BooleanSupplier visible) {
    TextArea latest = ViewLayout.editor(false);
    TextField filter = new TextField();
    filter.setPromptText("Filter");
    TableView<FileEntry> files = logTable();
    files.setOnMouseClicked(
        event -> {
          FileEntry selected = files.getSelectionModel().getSelectedItem();
          if (selected != null && !selected.directory()) {
            UiTasks.run(
                () -> fileService.readText(profile, selected.path()),
                text -> setLogText(latest, text));
          }
        });
    SplitPane split = new SplitPane(files, latest);
    split.setDividerPositions(0.28);
    VBox box = ViewLayout.contentBox(new HBox(8, filter), split);
    VBox.setVgrow(split, Priority.ALWAYS);
    startAutoRefresh(profile, visible, latest, filter, files, box);
    refreshLogs(profile, latest, files, "");
    return box;
  }

  private void startAutoRefresh(
      ServerProfile profile,
      BooleanSupplier visible,
      TextArea latest,
      TextField filter,
      TableView<FileEntry> files,
      Node owner) {
    Timeline timeline = new Timeline(
        new KeyFrame(
            Duration.seconds(3),
            event -> {
              if (visible.getAsBoolean()) {
                refreshLogs(profile, latest, files, filter.getText());
              }
            }));
    timeline.setCycleCount(Timeline.INDEFINITE);
    owner
        .sceneProperty()
        .addListener(
            (ignored, oldScene, newScene) -> {
              if (newScene == null) {
                timeline.stop();
              } else {
                timeline.play();
              }
            });
  }

  private void refreshLogs(
      ServerProfile profile, TextArea latest, TableView<FileEntry> files, String filter) {
    UiTasks.run(
        () -> new LogData(logService.latestLog(profile), logService.logFiles(profile)),
        data -> {
          String text = data.latest();
          if (filter != null && !filter.isBlank()) {
            text = text.lines()
                .filter(line -> line.toLowerCase().contains(filter.toLowerCase()))
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
          }
          setLogText(latest, text);
          files.getItems().setAll(data.files().stream().filter(file -> !file.directory()).toList());
        });
  }

  private void setLogText(TextArea latest, String text) {
    boolean keepTail = isScrolledToBottom(latest);
    double scrollTop = latest.getScrollTop();
    latest.setText(text == null ? "" : text);
    if (keepTail) {
      latest.positionCaret(latest.getLength());
      latest.setScrollTop(Double.MAX_VALUE);
    } else {
      latest.setScrollTop(scrollTop);
    }
  }

  private boolean isScrolledToBottom(TextArea latest) {
    ScrollBar bar = (ScrollBar) latest.lookup(".scroll-bar:vertical");
    if (bar == null) {
      return true;
    }
    return bar.getValue() >= bar.getMax() - 0.5;
  }

  private TableView<FileEntry> logTable() {
    TableView<FileEntry> table = new TableView<>();
    table.getColumns().add(column("Name", FileEntry::name, 220));
    table
        .getColumns()
        .add(
            column(
                "Modified",
                entry -> entry.modifiedAt() == null
                    ? ""
                    : LOG_TIME_FORMATTER.format(entry.modifiedAt()),
                160));
    table
        .getColumns()
        .add(column("Size", entry -> humanSize(entry.size()), 120));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
    return table;
  }

  private TableColumn<FileEntry, String> column(
      String title, Value value, double width) {
    TableColumn<FileEntry, String> column = new TableColumn<>(title);
    column.setCellValueFactory(data -> new ReadOnlyStringWrapper(value.read(data.getValue())));
    column.setPrefWidth(width);
    return column;
  }

  private String humanSize(long bytes) {
    if (bytes < 1024) {
      return bytes + " B";
    }
    double value = bytes;
    String[] units = { "KB", "MB", "GB", "TB" };
    int index = -1;
    while (value >= 1024 && index < units.length - 1) {
      value /= 1024;
      index++;
    }
    return String.format("%.1f %s", value, units[index]);
  }

  private record LogData(String latest, List<FileEntry> files) {
  }

  private interface Value {
    String read(FileEntry entry);
  }
}
