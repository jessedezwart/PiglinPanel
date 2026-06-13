package nl.jessedezwart.piglinpanel.app;

import java.awt.Desktop;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class BrowserLauncher implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

  private final Environment env;

  public BrowserLauncher(Environment env) {
    this.env = env;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    String port = env.getProperty("local.server.port");
    String url = "http://localhost:" + port;
    log.info("Piglinpanel running at {}", url);
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI.create(url));
      }
    } catch (Exception e) {
      log.warn("Could not open browser automatically: {}", e.getMessage());
    }
  }
}
