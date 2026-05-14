package nl.jessedezwart.piglinpanel.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import nl.jessedezwart.piglinpanel.PiglinpanelApplication;
import org.springframework.context.ConfigurableApplicationContext;

public class PiglinpanelFxApplication extends Application {
  private ConfigurableApplicationContext context;

  public static void launchApp(String[] args) {
    launch(args);
  }

  @Override
  public void init() {
    context =
        PiglinpanelApplication.springApplication()
            .run(getParameters().getRaw().toArray(String[]::new));
  }

  @Override
  public void start(Stage stage) {
    context.getBean(StageInitializer.class).initialize(stage);
  }

  @Override
  public void stop() {
    if (context != null) {
      context.close();
    }
    Platform.exit();
  }
}
