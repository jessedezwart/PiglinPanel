package nl.jessedezwart.piglinpanel.console;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;

/**
 * Logback appender that forwards formatted log lines to {@link PanelConsole} so the UI can stream
 * the panel's own console output. Configured in {@code logback-spring.xml}.
 */
public class PanelConsoleAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

  private Encoder<ILoggingEvent> encoder;

  public void setEncoder(Encoder<ILoggingEvent> encoder) {
    this.encoder = encoder;
  }

  public Encoder<ILoggingEvent> getEncoder() {
    return encoder;
  }

  @Override
  public void start() {
    if (encoder == null) {
      addError("No encoder set for PanelConsoleAppender");
      return;
    }
    super.start();
  }

  @Override
  protected void append(ILoggingEvent event) {
    if (!isStarted()) {
      return;
    }
    byte[] bytes = encoder.encode(event);
    String line = new String(bytes).stripTrailing();
    if (!line.isEmpty()) {
      PanelConsole.INSTANCE.publish(line);
    }
  }
}
