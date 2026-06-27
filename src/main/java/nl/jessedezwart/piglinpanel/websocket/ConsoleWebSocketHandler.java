package nl.jessedezwart.piglinpanel.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import nl.jessedezwart.piglinpanel.console.PanelConsole;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** Streams Piglin Panel's own console output (from {@link PanelConsole}) to the UI bottom bar. */
@Component
public class ConsoleWebSocketHandler extends TextWebSocketHandler {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConcurrentHashMap<String, Consumer<String>> listeners = new ConcurrentHashMap<>();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    List<String> history = PanelConsole.INSTANCE.history();
    send(session, "init", String.join("\n", history));

    Consumer<String> listener =
        line -> {
          try {
            send(session, "append", line);
          } catch (IOException ignored) {
            // Session closed mid-send; cleanup happens in afterConnectionClosed.
          }
        };
    listeners.put(session.getId(), listener);
    PanelConsole.INSTANCE.subscribe(listener);
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    Consumer<String> listener = listeners.remove(session.getId());
    if (listener != null) {
      PanelConsole.INSTANCE.unsubscribe(listener);
    }
  }

  private void send(WebSocketSession session, String type, String content) throws IOException {
    if (!session.isOpen()) {
      return;
    }
    String msg = objectMapper.writeValueAsString(Map.of("type", type, "content", content));
    synchronized (session) {
      session.sendMessage(new TextMessage(msg));
    }
  }
}
