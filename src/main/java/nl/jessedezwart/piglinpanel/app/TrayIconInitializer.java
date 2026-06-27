package nl.jessedezwart.piglinpanel.app;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Adds a system-tray icon with "Open" and "Exit" actions once the web server is ready. */
@Component
public class TrayIconInitializer implements ApplicationListener<ApplicationReadyEvent> {

  private static final Logger log = LoggerFactory.getLogger(TrayIconInitializer.class);
  private static final String TRAY_TOOLTIP = "Piglin Panel";

  private final Environment env;
  private final ConfigurableApplicationContext context;

  public TrayIconInitializer(Environment env, ConfigurableApplicationContext context) {
    this.env = env;
    this.context = context;
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    if (!SystemTray.isSupported()) {
      log.info("System tray is not supported; skipping tray icon.");
      return;
    }
    String url = "http://localhost:" + env.getProperty("local.server.port");
    try {
      SystemTray tray = SystemTray.getSystemTray();
      // Remove any icon left over from a previous DevTools restart so they don't accumulate.
      for (TrayIcon existing : tray.getTrayIcons()) {
        if (TRAY_TOOLTIP.equals(existing.getToolTip())) {
          tray.remove(existing);
        }
      }
      TrayIcon trayIcon = new TrayIcon(createIconImage(), TRAY_TOOLTIP, buildMenu(url));
      trayIcon.setImageAutoSize(true);
      trayIcon.addActionListener(e -> openBrowser(url));
      tray.add(trayIcon);
      log.info("Added system tray icon.");
    } catch (Exception e) {
      log.warn("Could not add system tray icon: {}", e.getMessage());
    }
  }

  private PopupMenu buildMenu(String url) {
    PopupMenu menu = new PopupMenu();

    MenuItem open = new MenuItem("Open Piglin Panel");
    open.addActionListener(e -> openBrowser(url));
    menu.add(open);

    menu.addSeparator();

    MenuItem exit = new MenuItem("Exit");
    exit.addActionListener(e -> exit());
    menu.add(exit);

    return menu;
  }

  private void openBrowser(String url) {
    try {
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI.create(url));
      }
    } catch (Exception e) {
      log.warn("Could not open browser: {}", e.getMessage());
    }
  }

  private void exit() {
    log.info("Exiting Piglin Panel from tray.");
    int exitCode = org.springframework.boot.SpringApplication.exit(context);
    System.exit(exitCode);
  }

  private static BufferedImage createIconImage() {
    int size = 32;
    BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = image.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    g.setColor(new Color(0xF6, 0x98, 0x99));
    g.fillRoundRect(0, 0, size, size, 10, 10);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
    var metrics = g.getFontMetrics();
    String text = "P";
    int x = (size - metrics.stringWidth(text)) / 2;
    int y = (size - metrics.getHeight()) / 2 + metrics.getAscent();
    g.drawString(text, x, y);
    g.dispose();
    return image;
  }
}
