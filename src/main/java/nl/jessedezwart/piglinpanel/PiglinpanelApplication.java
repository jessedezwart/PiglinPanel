package nl.jessedezwart.piglinpanel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PiglinpanelApplication {

  public static void main(String[] args) {
    // Desktop integration (browser autolaunch + tray icon) needs a non-headless AWT environment.
    System.setProperty("java.awt.headless", "false");
    SpringApplication.run(PiglinpanelApplication.class, args);
  }
}
