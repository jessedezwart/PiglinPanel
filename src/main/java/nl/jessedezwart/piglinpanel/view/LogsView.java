package nl.jessedezwart.piglinpanel.view;

import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
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
    ListView<FileEntry> files = new ListView<>();
    CheckBox autoRefresh = new CheckBox("Auto refresh");
    Button refresh = new Button("Refresh");
    refresh.setOnAction(event -> refreshLogs(profile, latest, files, filter.getText()));
    files.setOnMouseClicked(
        event -> {
          FileEntry selected = files.getSelectionModel().getSelectedItem();
          if (selected != null && !selected.directory()) {
            UiTasks.run(() -> fileService.readText(profile, selected.path()), latest::setText);
          }
        });
    SplitPane split = new SplitPane(files, latest);
    split.setDividerPositions(0.28);
    VBox box = ViewLayout.contentBox(new HBox(8, refresh, autoRefresh, filter), split);
    VBox.setVgrow(split, Priority.ALWAYS);
    startAutoRefresh(profile, visible, latest, filter, files, autoRefresh, box);
    refreshLogs(profile, latest, files, "");
    return box;
  }

  private void startAutoRefresh(
      ServerProfile profile,
      BooleanSupplier visible,
      TextArea latest,
      TextField filter,
      ListView<FileEntry> files,
      CheckBox autoRefresh,
      Node owner) {
    Timeline timeline =
        new Timeline(
            new KeyFrame(
                Duration.seconds(3),
                event -> {
                  if (autoRefresh.isSelected() && visible.getAsBoolean()) {
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
      ServerProfile profile, TextArea latest, ListView<FileEntry> files, String filter) {
    UiTasks.run(
        () -> new LogData(logService.latestLog(profile), logService.logFiles(profile)),
        data -> {
          String text = data.latest();
          if (filter != null && !filter.isBlank()) {
            text =
                text.lines()
                    .filter(line -> line.toLowerCase().contains(filter.toLowerCase()))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");
          }
          latest.setText(text);
          files.getItems().setAll(data.files());
        });
  }

  private record LogData(String latest, List<FileEntry> files) {}
}
