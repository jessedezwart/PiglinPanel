package nl.jessedezwart.piglinpanel.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

class ModrinthClientTests {
  @Test
  void retriesRateLimitedLookupsUsingExponentialBackoff() throws Exception {
    AtomicInteger versionFileCalls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v2/version_file/test-sha1",
        exchange -> {
          if (versionFileCalls.getAndIncrement() == 0) {
            respond(exchange, 429, "{\"error\":\"rate_limited\"}");
            return;
          }
          respond(
              exchange,
              200,
              """
              {
                "id":"current",
                "project_id":"project",
                "name":"Current",
                "loaders":["fabric"],
                "game_versions":["1.21.1"],
                "files":[{"filename":"current.jar","url":"https://example.com/current.jar","primary":true}],
                "date_published":"2026-06-01T10:00:00Z"
              }
              """);
        });
    server.createContext(
        "/v2/project/project",
        exchange -> respond(exchange, 200, "{\"id\":\"project\",\"title\":\"Project\"}"));
    server.createContext(
        "/v2/version_file/test-sha1/update",
        exchange -> {
          assertThat(exchange.getRequestMethod()).isEqualTo("POST");
          String requestBody = readRequestBody(exchange);
          assertThat(requestBody).contains("\"loaders\":[\"fabric\"]");
          assertThat(requestBody).contains("\"game_versions\":[\"1.21.1\"]");
          respond(
              exchange,
              200,
              """
              {
                "id":"latest",
                "project_id":"project",
                "name":"Latest",
                "loaders":["fabric"],
                "game_versions":["1.21.1"],
                "files":[{"filename":"latest.jar","url":"https://example.com/latest.jar","primary":true}],
                "date_published":"2026-06-10T12:00:00Z"
              }
              """);
        });
    server.start();
    try {
      ModrinthClient client =
          ModrinthClient.createForTesting(
              new ObjectMapper(),
              webClient(),
              "http://localhost:" + server.getAddress().getPort() + "/v2",
              1,
              Duration.ofMillis(1),
              Duration.ofMillis(1));

      Optional<ModUpdate> update = client.findSuggestedUpdate("test-sha1");

      assertThat(update).isPresent();
      assertThat(update.get().latestVersionName()).isEqualTo("Latest");
      assertThat(update.get().projectName()).isEqualTo("Project");
      assertThat(update.get().lastUpdatedAt())
          .isEqualTo(Instant.parse("2026-06-10T12:00:00Z"));
      assertThat(versionFileCalls.get()).isEqualTo(2);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void failsWhenRateLimitRetriesAreExhausted() throws Exception {
    AtomicInteger versionFileCalls = new AtomicInteger();
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/v2/version_file/test-sha1",
        exchange -> {
          versionFileCalls.incrementAndGet();
          respond(exchange, 429, "{\"error\":\"rate_limited\"}");
        });
    server.start();
    try {
      ModrinthClient client =
          ModrinthClient.createForTesting(
              new ObjectMapper(),
              webClient(),
              "http://localhost:" + server.getAddress().getPort() + "/v2",
              4,
              Duration.ofMillis(1),
              Duration.ofMillis(1));

      assertThatThrownBy(() -> client.findSuggestedUpdate("test-sha1"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("HTTP 429 after retries");
      assertThat(versionFileCalls.get()).isEqualTo(5);
    } finally {
      server.stop(0);
    }
  }

  private static WebClient webClient() {
    return WebClient.builder()
        .defaultHeader(HttpHeaders.USER_AGENT, "Piglinpanel/0.1")
        .build();
  }

  private static void respond(HttpExchange exchange, int status, String body, String... headers)
      throws IOException {
    for (int index = 0; index + 1 < headers.length; index += 2) {
      exchange.getResponseHeaders().add(headers[index], headers[index + 1]);
    }
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static String readRequestBody(HttpExchange exchange) throws IOException {
    try (InputStream body = exchange.getRequestBody()) {
      return new String(body.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }
}
