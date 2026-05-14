package nl.jessedezwart.piglinpanel.view;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.Alert;

final class UiTasks {
  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(Thread.ofVirtual().name("piglinpanel-", 0).factory());

  private UiTasks() {}

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

  static void runVoid(VoidWork work, Runnable success) {
    run(
        () -> {
          work.run();
          return null;
        },
        ignored -> success.run());
  }

  static void error(Exception e) {
    Alert alert = new Alert(Alert.AlertType.ERROR);
    alert.setTitle("Piglinpanel");
    alert.setHeaderText(e.getMessage());
    alert.setContentText(e.getCause() == null ? "" : e.getCause().getMessage());
    alert.showAndWait();
  }

  interface Work<T> {
    T run() throws Exception;
  }

  interface VoidWork {
    void run() throws Exception;
  }
}
