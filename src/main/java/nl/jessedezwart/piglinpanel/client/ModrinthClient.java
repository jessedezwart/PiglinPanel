package nl.jessedezwart.piglinpanel.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class ModrinthClient {
  private static final String API_BASE_URL = "https://api.modrinth.com/v2";
  private static final Logger log = LoggerFactory.getLogger(ModrinthClient.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration INITIAL_BACKOFF = Duration.ofMillis(500);
  private static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);
  private static final int MAX_429_RETRIES = 4;
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  private final ObjectMapper objectMapper;
  private final WebClient webClient;
  private final String apiBaseUrl;

  @Autowired
  public ModrinthClient(ObjectMapper objectMapper) {
    this(objectMapper, webClient(), API_BASE_URL);
  }

  private ModrinthClient(ObjectMapper objectMapper, WebClient webClient, String apiBaseUrl) {
    this.objectMapper = objectMapper;
    this.webClient = webClient;
    this.apiBaseUrl = apiBaseUrl;
  }

  static ModrinthClient createForTesting(
      ObjectMapper objectMapper, WebClient webClient, String apiBaseUrl) {
    return new ModrinthClient(objectMapper, webClient, apiBaseUrl);
  }

  public Optional<ModUpdate> findSuggestedUpdate(String sha1)
      throws IOException, InterruptedException {
    Optional<Version> current = versionByHash(sha1);
    if (current.isEmpty()) {
      return Optional.empty();
    }
    Version currentVersion = current.get();
    Optional<Project> project = project(currentVersion.projectId());
    String projectName = project.map(Project::title).orElse(currentVersion.projectId());
    List<Version> versions =
        versionsForProject(currentVersion.projectId(), currentVersion.gameVersions());
    Optional<Version> latest =
        versions.stream()
            .max(
                Comparator.comparing(
                    Version::datePublished, Comparator.nullsFirst(String::compareTo)));
    if (latest.isEmpty()) {
      return Optional.of(
          new ModUpdate(
              currentVersion.projectId(),
              projectName,
              currentVersion.id(),
              currentVersion.id(),
              currentVersion.name(),
              currentVersion.publishedAtInstant(),
              null,
              null));
    }
    Version latestVersion = latest.get();
    File latestFile = latestVersion.primaryFile().orElse(null);
    URI downloadUrl = null;
    String fileName = null;
    if (latestFile != null && latestFile.url() != null && latestFile.filename() != null) {
      downloadUrl = URI.create(latestFile.url());
      fileName = latestFile.filename();
    }
    return Optional.of(
        new ModUpdate(
            currentVersion.projectId(),
            projectName,
            currentVersion.id(),
            latestVersion.id(),
            latestVersion.name(),
            latestVersion.publishedAtInstant(),
            downloadUrl,
            fileName));
  }

  public byte[] download(URI uri) throws IOException, InterruptedException {
    Response response = get(uri.toString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Download failed with HTTP " + response.statusCode());
    }
    return response.body();
  }

  private Optional<Version> versionByHash(String sha1) throws IOException, InterruptedException {
    Response response = get(apiBaseUrl + "/version_file/" + encode(sha1) + "?algorithm=sha1");
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    requireSuccess(response);
    return Optional.of(objectMapper.readValue(response.bodyAsString(), Version.class));
  }

  private Optional<Project> project(String projectId) throws IOException, InterruptedException {
    Response response = get(apiBaseUrl + "/project/" + encode(projectId));
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    requireSuccess(response);
    return Optional.of(objectMapper.readValue(response.bodyAsString(), Project.class));
  }

  private List<Version> versionsForProject(String projectId, List<String> gameVersions)
      throws IOException, InterruptedException {
    String loaders = encode("[\"neoforge\"]");
    String gameVersionParam = gameVersions == null || gameVersions.isEmpty()
        ? ""
        : "&game_versions=" + encode(objectMapper.writeValueAsString(gameVersions));
    Response response =
        get(
            apiBaseUrl
                + "/project/"
                + encode(projectId)
                + "/version?loaders="
                + loaders
                + gameVersionParam);
    requireSuccess(response);
    JsonNode node = objectMapper.readTree(response.bodyAsString());
    return objectMapper.readerForListOf(Version.class).readValue(node);
  }

  private Response get(String uri) throws IOException, InterruptedException {
    try {
      return webClient
          .get()
          .uri(uri)
          .exchangeToMono(
              response -> {
                if (response.statusCode().value() == 429) {
                  return response.releaseBody().then(Mono.error(new RateLimitException(uri)));
                }
                return response.bodyToMono(byte[].class)
                    .defaultIfEmpty(new byte[0])
                    .map(body -> new Response(response.statusCode().value(), body));
              })
          .timeout(REQUEST_TIMEOUT)
          .retryWhen(
              Retry.backoff(MAX_429_RETRIES, INITIAL_BACKOFF)
                  .maxBackoff(MAX_RETRY_DELAY)
                  .jitter(0d)
                  .filter(RateLimitException.class::isInstance)
                  .doBeforeRetry(
                      signal ->
                          log.warn(
                              "Modrinth rate limit hit for {}. Retrying {}/{}",
                              uri,
                              signal.totalRetries() + 1,
                              MAX_429_RETRIES + 1)))
          .block();
    } catch (RateLimitException e) {
      throw new IOException("Modrinth request failed with HTTP 429 after retries for " + uri, e);
    } catch (WebClientRequestException e) {
      throw new IOException("Modrinth request failed for " + uri, e);
    } catch (RuntimeException e) {
      if (hasCause(e, RateLimitException.class)) {
        throw new IOException("Modrinth request failed with HTTP 429 after retries for " + uri, e);
      }
      if (hasCause(e, java.util.concurrent.TimeoutException.class)) {
        throw new IOException("Modrinth request timed out for " + uri, e);
      }
      throw new IOException("Modrinth request failed for " + uri, e);
    }
  }

  private static void requireSuccess(Response response) throws IOException {
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Modrinth request failed with HTTP " + response.statusCode());
    }
  }

  private static WebClient webClient() {
    return WebClient.builder()
        .defaultHeader(HttpHeaders.USER_AGENT, "Piglinpanel/0.1")
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
        .build();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
    Throwable current = throwable;
    while (current != null) {
      if (type.isInstance(current)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private record Response(int statusCode, byte[] body) {
    private String bodyAsString() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private static final class RateLimitException extends RuntimeException {
    private RateLimitException(String uri) {
      super("Modrinth rate limited request to " + uri);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Project(String id, String title) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Version(
      String id,
      @JsonProperty("project_id") String projectId,
      String name,
      @JsonProperty("game_versions") List<String> gameVersions,
      List<File> files,
      @JsonProperty("date_published") String datePublished) {
    public Instant publishedAtInstant() {
      if (datePublished == null || datePublished.isBlank()) {
        return null;
      }
      try {
        return Instant.parse(datePublished);
      } catch (DateTimeParseException ignored) {
        return null;
      }
    }

    public Optional<File> primaryFile() {
      if (files == null || files.isEmpty()) {
        return Optional.empty();
      }
      return files.stream()
          .filter(File::primary)
          .findFirst()
          .or(() -> Optional.of(files.getFirst()));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record File(String filename, String url, boolean primary) {
  }
}
