package nl.jessedezwart.piglinpanel.app;

import javafx.scene.Scene;
import javafx.stage.Stage;
import nl.jessedezwart.piglinpanel.view.MainView;
import org.springframework.stereotype.Component;

@Component
public class StageInitializer {
  private final MainView mainView;

  public StageInitializer(MainView mainView) {
    this.mainView = mainView;
  }

  public void initialize(Stage stage) {
    stage.setTitle("Piglinpanel");
    stage.setScene(new Scene(mainView.create(), 1180, 760));
    stage.show();
  }
}
