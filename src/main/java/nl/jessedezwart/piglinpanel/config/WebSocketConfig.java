package nl.jessedezwart.piglinpanel.config;

import nl.jessedezwart.piglinpanel.websocket.ConsoleWebSocketHandler;
import nl.jessedezwart.piglinpanel.websocket.LogWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final LogWebSocketHandler logWebSocketHandler;
  private final ConsoleWebSocketHandler consoleWebSocketHandler;

  public WebSocketConfig(
      LogWebSocketHandler logWebSocketHandler, ConsoleWebSocketHandler consoleWebSocketHandler) {
    this.logWebSocketHandler = logWebSocketHandler;
    this.consoleWebSocketHandler = consoleWebSocketHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(logWebSocketHandler, "/ws/logs").setAllowedOrigins("*");
    registry.addHandler(consoleWebSocketHandler, "/ws/console").setAllowedOrigins("*");
  }
}
