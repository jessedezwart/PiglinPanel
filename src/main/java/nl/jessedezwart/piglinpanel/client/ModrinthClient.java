package nl.jessedezwart.piglinpanel.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
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
  private static final int MAX_429_RETRIES = 6;
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

  private final ObjectMapper objectMapper;
  private final WebClient webClient;
  private final String apiBaseUrl;
  private final int max429Retries;
  private final Duration initialBackoff;
  private final Duration maxRetryDelay;

  @Autowired
  public ModrinthClient(ObjectMapper objectMapper) {
    this(
        objectMapper,
        webClient(),
        API_BASE_URL,
        MAX_429_RETRIES,
        INITIAL_BACKOFF,
        MAX_RETRY_DELAY);
  }

  private ModrinthClient(
      ObjectMapper objectMapper,
      WebClient webClient,
      String apiBaseUrl,
      int max429Retries,
      Duration initialBackoff,
      Duration maxRetryDelay) {
    this.objectMapper = objectMapper;
    this.webClient = webClient;
    this.apiBaseUrl = apiBaseUrl;
    this.max429Retries = max429Retries;
    this.initialBackoff = initialBackoff;
    this.maxRetryDelay = maxRetryDelay;
  }

  static ModrinthClient createForTesting(
      ObjectMapper objectMapper, WebClient webClient, String apiBaseUrl) {
    return new ModrinthClient(
        objectMapper,
        webClient,
        apiBaseUrl,
        MAX_429_RETRIES,
        INITIAL_BACKOFF,
        MAX_RETRY_DELAY);
  }

  static ModrinthClient createForTesting(
      ObjectMapper objectMapper,
      WebClient webClient,
      String apiBaseUrl,
      int max429Retries,
      Duration initialBackoff,
      Duration maxRetryDelay) {
    return new ModrinthClient(
        objectMapper, webClient, apiBaseUrl, max429Retries, initialBackoff, maxRetryDelay);
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
    Optional<Version> latest = latestVersionForHash(sha1, currentVersion);
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

  private Optional<Version> latestVersionForHash(String sha1, Version currentVersion)
      throws IOException, InterruptedException {
    Response response = postJson(
        apiBaseUrl + "/version_file/" + encode(sha1) + "/update?algorithm=sha1",
        Map.of(
            "loaders", requestedLoaders(currentVersion),
            "game_versions", requestedGameVersions(currentVersion)));
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    requireSuccess(response);
    return Optional.of(objectMapper.readValue(response.bodyAsString(), Version.class));
  }

  private static List<String> requestedLoaders(Version version) {
    if (version.loaders() != null && !version.loaders().isEmpty()) {
      return version.loaders();
    }
    return List.of("neoforge");
  }

  private static List<String> requestedGameVersions(Version version) {
    if (version.gameVersions() != null && !version.gameVersions().isEmpty()) {
      return version.gameVersions();
    }
    return List.of();
  }

  private Response get(String uri) throws IOException, InterruptedException {
    return exchange(webClient.get().uri(uri), uri);
  }

  private Response postJson(String uri, Object body) throws IOException, InterruptedException {
    return exchange(webClient.post().uri(uri).bodyValue(body), uri);
  }

  private Response exchange(WebClient.RequestHeadersSpec<?> request, String uri)
      throws IOException, InterruptedException {
    try {
      return request
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
              Retry.backoff(max429Retries, initialBackoff)
                  .maxBackoff(maxRetryDelay)
                  .jitter(0d)
                  .filter(RateLimitException.class::isInstance)
                  .doBeforeRetry(
                      signal -> log.warn(
                          "Modrinth rate limit hit for {}. Retrying {}/{}",
                          uri,
                          signal.totalRetries() + 1,
                          max429Retries + 1)))
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
      List<String> loaders,
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
