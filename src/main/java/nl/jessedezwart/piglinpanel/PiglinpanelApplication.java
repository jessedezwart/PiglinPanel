package nl.jessedezwart.piglinpanel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;

@SpringBootApplication
public class PiglinpanelApplication {

  public static void main(String[] args) {
    springApplication().run(args);
  }

  public static SpringApplication springApplication() {
    SpringApplication application = new SpringApplication(PiglinpanelApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    return application;
  }
}
