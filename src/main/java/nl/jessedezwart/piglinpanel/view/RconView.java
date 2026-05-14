package nl.jessedezwart.piglinpanel.view;

import javafx.scene.Node;
import java.util.function.BooleanSupplier;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.LogService;
import nl.jessedezwart.piglinpanel.service.RconService;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class RconView {
  private static final Logger log = LoggerFactory.getLogger(RconView.class);
  private final RconService rconService;
  private final LogService logService;

  RconView(RconService rconService, LogService logService) {
    this.rconService = rconService;
    this.logService = logService;
  }

  Node create(ServerProfile profile) {
    TextArea output = ViewLayout.editor(false);
    TextField command = new TextField();
    command.setPromptText("Command");
    command.setOnAction(
        event -> {
          String value = command.getText();
          if (value == null || value.isBlank()) {
            return;
          }
          log.info("Sending RCON command to {}", profile.getName());
          UiTasks.run(
              () -> rconService.execute(profile, value),
              response -> command.clear(),
              error -> {
                String message = error.getMessage();
                if (message == null || message.isBlank()) {
                  message = "RCON request failed";
                }
                output.appendText("\n" + message + "\n");
                log.warn("RCON command failed for {}", profile.getName(), error);
              },
              null);
        });
    VBox box = ViewLayout.contentBox(output, command);
    VBox.setVgrow(output, Priority.ALWAYS);
    startAutoRefresh(profile, () -> true, output, box);
    refreshLogs(profile, output);
    return box;
  }

  private void startAutoRefresh(
      ServerProfile profile, BooleanSupplier visible, TextArea latest, Node owner) {
    Timeline timeline = new Timeline(
        new KeyFrame(
            Duration.seconds(2),
            event -> {
              if (visible.getAsBoolean()) {
                refreshLogs(profile, latest);
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

  private void refreshLogs(ServerProfile profile, TextArea latest) {
    UiTasks.run(() -> logService.latestLog(profile), text -> setLogText(latest, text));
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
}
