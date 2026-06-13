package nl.jessedezwart.piglinpanel.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import nl.jessedezwart.piglinpanel.service.LogService;
import nl.jessedezwart.piglinpanel.service.ServerRegistryService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

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
    Map<String, String> params = parseQuery(session.getUri().getQuery());
    String serverId = params.get("serverId");
    String filter = params.getOrDefault("filter", "");
    ServerProfile profile = resolveProfile(serverId);
    if (profile == null) {
      return;
    }
    TailingSession ts = new TailingSession(session, profile, filter);
    sessions.put(session.getId(), ts);
    Thread.ofVirtual().start(() -> ts.run(logService, objectMapper));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
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

  private ServerProfile resolveProfile(String serverId) {
    if (serverId != null && !serverId.isBlank()) {
      return registryService.servers().stream()
          .filter(s -> serverId.equals(s.getId()))
          .findFirst()
          .orElseGet(() -> registryService.selected().orElse(null));
    }
    return registryService.selected().orElse(null);
  }

  private static Map<String, String> parseQuery(String query) {
    Map<String, String> params = new HashMap<>();
    if (query == null || query.isBlank()) {
      return params;
    }
    for (String pair : query.split("&")) {
      String[] kv = pair.split("=", 2);
      String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
      String value = kv.length == 2 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
      params.put(key, value);
    }
    return params;
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
        send(mapper, "init", applyFilter(content, filter));

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
            send(mapper, "init", applyFilter(content, filter));
          } else {
            content = logService.latestLog(profile);
            String[] newLines = content.split("\n", -1);
            if (newLines.length > lastLineCount) {
              StringBuilder sb = new StringBuilder();
              for (int i = lastLineCount; i < newLines.length; i++) {
                String line = newLines[i];
                if (filter.isBlank()
                    || line.toLowerCase().contains(filter.toLowerCase())) {
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

    private static String applyFilter(String content, String filter) {
      if (filter == null || filter.isBlank()) {
        return content;
      }
      StringBuilder sb = new StringBuilder();
      for (String line : content.split("\n")) {
        if (line.toLowerCase().contains(filter.toLowerCase())) {
          sb.append(line).append('\n');
        }
      }
      return sb.toString();
    }
  }
}
