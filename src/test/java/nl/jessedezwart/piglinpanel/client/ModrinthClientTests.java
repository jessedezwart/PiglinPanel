package nl.jessedezwart.piglinpanel.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class ModrinthClientTests {
  @Test
  void refreshesVersionMetadataWhenStoredDownloadUrlReturns404() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    AtomicInteger staleDownloadCalls = new AtomicInteger();
    server.createContext(
        "/stale/latest.jar",
        exchange -> {
          staleDownloadCalls.incrementAndGet();
          respond(exchange, 404, "{\"error\":\"not_found\"}");
        });
    server.createContext(
        "/v2/project/project/version/latest",
        exchange ->
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
                  "files":[{"filename":"latest.jar","url":"http://localhost:%d/downloads/latest.jar","primary":true}],
                  "date_published":"2026-06-10T12:00:00Z"
                }
                """
                    .formatted(server.getAddress().getPort())));
    server.createContext("/downloads/latest.jar", exchange -> respond(exchange, 200, "jar-bytes"));
    server.start();
    try {
      ModrinthClient client =
          ModrinthClient.createForTesting(
              new ObjectMapper(),
              restClient(),
              "http://localhost:" + server.getAddress().getPort() + "/v2");

      byte[] bytes =
          client.downloadUpdate(
              new ModUpdate(
                  "project",
                  "Project",
                  "current",
                  "latest",
                  "Latest",
                  Instant.parse("2026-06-10T12:00:00Z"),
                  URI.create(
                      "http://localhost:" + server.getAddress().getPort() + "/stale/latest.jar"),
                  "latest.jar",
                  null,
                  null,
                  null,
                  0L));

      assertThat(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("jar-bytes");
      assertThat(staleDownloadCalls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  private static RestClient restClient() {
    return RestClient.builder().defaultHeader(HttpHeaders.USER_AGENT, "Piglinpanel/0.1").build();
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
}
