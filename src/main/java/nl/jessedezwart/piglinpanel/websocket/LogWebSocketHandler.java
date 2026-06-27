package nl.jessedezwart.piglinpanel.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.LogService;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class LogWebSocketHandler extends TextWebSocketHandler {

  private final LogService logService;
  private final ServerRegistryService registryService;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ConcurrentHashMap<String, TailingSession> sessions = new ConcurrentHashMap<>();

  public LogWebSocketHandler(LogService logService, ServerRegistryService registryService) {
    this.logService = logService;
    this.registryService = registryService;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    MultiValueMap<String, String> params =
        UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
    String serverId = params.getFirst("serverId");
    String filter = params.getFirst("filter") != null ? params.getFirst("filter") : "";
    ServerProfile profile = registryService.resolve(serverId);
    if (profile == null) {
      return;
    }
    TailingSession ts = new TailingSession(session, profile, filter);
    sessions.put(session.getId(), ts);
    Thread.ofVirtual().start(() -> ts.run(logService, objectMapper));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message)
      throws IOException {
    TailingSession ts = sessions.get(session.getId());
    if (ts == null) {
      return;
    }
    JsonNode data = objectMapper.readTree(message.getPayload());
    if (data.has("filter")) {
      ts.updateFilter(data.get("filter").asText());
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    TailingSession ts = sessions.remove(session.getId());
    if (ts != null) {
      ts.stop();
    }
  }

  private static class TailingSession {

    private final WebSocketSession wsSession;
    private final ServerProfile profile;
    private volatile String filter;
    private volatile boolean running = true;
    private volatile boolean filterChanged = false;
    private int lastLineCount = 0;

    TailingSession(WebSocketSession wsSession, ServerProfile profile, String filter) {
      this.wsSession = wsSession;
      this.profile = profile;
      this.filter = filter;
    }

    void updateFilter(String newFilter) {
      this.filter = newFilter;
      this.filterChanged = true;
    }

    void stop() {
      this.running = false;
    }

    void run(LogService logService, ObjectMapper mapper) {
      try {
        String content = logService.latestLog(profile);
        String[] lines = content.split("\n", -1);
        lastLineCount = lines.length;
        send(mapper, "init", LogService.applyFilter(content, filter));

        while (running && wsSession.isOpen()) {
          Thread.sleep(1000);
          if (!running || !wsSession.isOpen()) {
            break;
          }
          if (filterChanged) {
            filterChanged = false;
            content = logService.latestLog(profile);
            lines = content.split("\n", -1);
            lastLineCount = lines.length;
            send(mapper, "init", LogService.applyFilter(content, filter));
          } else {
            content = logService.latestLog(profile);
            String[] newLines = content.split("\n", -1);
            if (newLines.length > lastLineCount) {
              StringBuilder sb = new StringBuilder();
              for (int i = lastLineCount; i < newLines.length; i++) {
                String line = newLines[i];
                if (filter.isBlank() || line.toLowerCase().contains(filter.toLowerCase())) {
                  sb.append(line).append('\n');
                }
              }
              lastLineCount = newLines.length;
              if (!sb.isEmpty()) {
                send(mapper, "append", sb.toString());
              }
            } else {
              lastLineCount = newLines.length;
            }
          }
        }
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      } catch (Exception ex) {
        stop();
      }
    }

    private void send(ObjectMapper mapper, String type, String content) throws IOException {
      if (!wsSession.isOpen()) {
        return;
      }
      String msg = mapper.writeValueAsString(Map.of("type", type, "content", content));
      synchronized (wsSession) {
        wsSession.sendMessage(new TextMessage(msg));
      }
    }
  }
}
