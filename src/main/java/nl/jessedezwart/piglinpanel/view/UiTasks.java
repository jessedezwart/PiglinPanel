package nl.jessedezwart.piglinpanel.view;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class UiTasks {
  private static final Logger log = LoggerFactory.getLogger(UiTasks.class);
  private static final ExecutorService EXECUTOR = Executors
      .newCachedThreadPool(Thread.ofVirtual().name("piglinpanel-", 0).factory());

  private UiTasks() {
  }

  static <T> void run(Work<T> work, Consumer<T> success) {
    EXECUTOR.submit(
        () -> {
          try {
            T result = work.run();
            Platform.runLater(() -> success.accept(result));
          } catch (Exception e) {
            Platform.runLater(() -> error(e));
          }
        });
  }

  static <T> void run(
      Work<T> work, Consumer<T> success, Consumer<Exception> failure, Runnable done) {
    EXECUTOR.submit(
        () -> {
          try {
            T result = work.run();
            Platform.runLater(() -> success.accept(result));
          } catch (Exception e) {
            Platform.runLater(() -> {
              if (failure == null) {
                error(e);
              } else {
                failure.accept(e);
              }
            });
          } finally {
            if (done != null) {
              Platform.runLater(done);
            }
          }
        });
  }

  static void runVoid(VoidWork work, Runnable success) {
    run(
        () -> {
          work.run();
          return null;
        },
        ignored -> success.run());
  }

  static void error(Exception e) {
    log.error("Background task failed", e);
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Piglinpanel");
    String header = e.getMessage();
    if (header == null || header.isBlank()) {
      header = e.getClass().getSimpleName();
    }
    String detail = e.getCause() == null ? "" : e.getCause().getMessage();
    if (detail == null) {
      detail = "";
    }
    alert.setHeaderText(header);
    alert.setContentText(detail);
    alert.showAndWait();
  }

  interface Work<T> {
    T run() throws Exception;
  }

  interface VoidWork {
    void run() throws Exception;
  }
}
