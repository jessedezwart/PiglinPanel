package nl.jessedezwart.piglinpanel.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * In-memory sink for Piglin Panel's own log output. The {@link PanelConsoleAppender} (wired through
 * logback-spring.xml) feeds formatted log lines here; websocket sessions subscribe to receive the
 * recent history plus any new lines in real time. This is intentionally a plain singleton rather
 * than a Spring bean because logback instantiates the appender outside the Spring context.
 */
public final class PanelConsole {
  public static final PanelConsole INSTANCE = new PanelConsole();

  private static final int MAX_HISTORY = 500;

  private final Deque<String> history = new ArrayDeque<>(MAX_HISTORY);
  private final List<Consumer<String>> listeners = new ArrayList<>();

  private PanelConsole() {}

  /** Records a formatted log line and notifies all subscribers. */
  public void publish(String line) {
    if (line == null) {
      return;
    }
    List<Consumer<String>> snapshot;
    synchronized (this) {
      while (history.size() >= MAX_HISTORY) {
        history.pollFirst();
      }
      history.addLast(line);
      snapshot = new ArrayList<>(listeners);
    }
    for (Consumer<String> listener : snapshot) {
      try {
        listener.accept(line);
      } catch (RuntimeException ignored) {
        // A slow or broken subscriber must never break logging.
      }
    }
  }

  /** Returns a snapshot of the buffered history. */
  public synchronized List<String> history() {
    return new ArrayList<>(history);
  }

  public synchronized void subscribe(Consumer<String> listener) {
    listeners.add(listener);
  }

  public synchronized void unsubscribe(Consumer<String> listener) {
    listeners.remove(listener);
  }
}
