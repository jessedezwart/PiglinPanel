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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
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
    return findSuggestedUpdates(List.of(sha1)).getOrDefault(sha1, Optional.empty());
  }

  public Map<String, Optional<ModUpdate>> findSuggestedUpdates(List<String> sha1s)
      throws IOException, InterruptedException {
    if (sha1s == null || sha1s.isEmpty()) {
      return Map.of();
    }
    List<String> requestedHashes = sha1s.stream()
        .filter(hash -> hash != null && !hash.isBlank())
        .distinct()
        .toList();
    if (requestedHashes.isEmpty()) {
      return Map.of();
    }

    Map<String, Version> currentVersions = versionsByHashes(requestedHashes);
    Map<String, String> projectNames = new LinkedHashMap<>();
    Map<UpdateCriteria, List<String>> hashesByCriteria = new LinkedHashMap<>();
    for (Map.Entry<String, Version> entry : currentVersions.entrySet()) {
      Version version = entry.getValue();
      projectNames.computeIfAbsent(
          version.projectId(),
          projectId -> {
            try {
              return project(projectId).map(Project::title).orElse(projectId);
            } catch (IOException | InterruptedException e) {
              throw new ProjectLookupRuntimeException(projectId, e);
            }
          });
      hashesByCriteria
          .computeIfAbsent(UpdateCriteria.from(version), ignored -> new ArrayList<>())
          .add(entry.getKey());
    }

    Map<String, Version> latestVersions = new LinkedHashMap<>();
    try {
      for (Map.Entry<UpdateCriteria, List<String>> entry : hashesByCriteria.entrySet()) {
        UpdateCriteria criteria = entry.getKey();
        latestVersions.putAll(
            latestVersionsForHashes(entry.getValue(), criteria.loaders(), criteria.gameVersions()));
      }
    } catch (ProjectLookupRuntimeException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException ioException) {
        throw ioException;
      }
      if (cause instanceof InterruptedException interruptedException) {
        throw interruptedException;
      }
      throw new IOException("Modrinth project lookup failed for " + e.projectId(), cause);
    }

    Map<String, Optional<ModUpdate>> updates = new LinkedHashMap<>();
    for (String sha1 : requestedHashes) {
      Version currentVersion = currentVersions.get(sha1);
      if (currentVersion == null) {
        updates.put(sha1, Optional.empty());
        continue;
      }
      String projectName = projectNames.getOrDefault(currentVersion.projectId(), currentVersion.projectId());
      Version latestVersion = latestVersions.get(sha1);
      updates.put(sha1, Optional.of(toModUpdate(currentVersion, latestVersion, projectName)));
    }
    return updates;
  }

  public byte[] download(URI uri) throws IOException, InterruptedException {
    Response response = get(uri.toString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException(
          "Download failed with HTTP " + response.statusCode() + " for " + uri);
    }
    return response.body();
  }

  public byte[] downloadUpdate(ModUpdate update) throws IOException, InterruptedException {
    if (update.downloadUrl() != null) {
      try {
        return download(update.downloadUrl());
      } catch (IOException e) {
        if (!isHttpStatus(e, 404)
            || update.projectId() == null
            || update.latestVersionId() == null
            || update.fileName() == null
            || update.fileName().isBlank()) {
          throw e;
        }
        log.warn(
            "Stored Modrinth download URL returned 404 for project {} version {} file {}. "
                + "Refreshing version metadata and retrying.",
            update.projectId(),
            update.latestVersionId(),
            update.fileName());
      }
    }
    if (update.projectId() == null || update.latestVersionId() == null) {
      throw new IOException("No download metadata is available for update " + update);
    }
    log.info(
        "Refreshing Modrinth version metadata for project {} version {} after download fallback",
        update.projectId(),
        update.latestVersionId());
    Version version = version(update.projectId(), update.latestVersionId());
    File file = version.fileByName(update.fileName()).or(() -> version.primaryFile())
        .orElseThrow(
            () ->
                new IOException(
                    "No downloadable file found for Modrinth version " + update.latestVersionId()));
    if (file.url() == null || file.url().isBlank()) {
      throw new IOException(
          "Modrinth version " + update.latestVersionId() + " did not provide a download URL");
    }
    log.info(
        "Retrying Modrinth download for project {} version {} file {} from {}",
        update.projectId(),
        update.latestVersionId(),
        file.filename(),
        file.url());
    return download(URI.create(file.url()));
  }

  private Optional<Project> project(String projectId) throws IOException, InterruptedException {
    String uri = apiBaseUrl + "/project/" + encode(projectId);
    Response response = get(uri);
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    requireSuccess(response, uri);
    return Optional.of(objectMapper.readValue(response.bodyAsString(), Project.class));
  }

  private Version version(String projectId, String versionId) throws IOException, InterruptedException {
    String uri = apiBaseUrl + "/project/" + encode(projectId) + "/version/" + encode(versionId);
    Response response = get(uri);
    requireSuccess(response, uri);
    return objectMapper.readValue(response.bodyAsString(), Version.class);
  }

  private Map<String, Version> versionsByHashes(List<String> sha1s)
      throws IOException, InterruptedException {
    String uri = apiBaseUrl + "/version_files";
    Response response = postJson(uri, Map.of("hashes", sha1s, "algorithm", "sha1"));
    logBulkResponse(uri, response);
    requireSuccess(response, uri);
    return readVersionMap(response.bodyAsString());
  }

  private Map<String, Version> latestVersionsForHashes(
      List<String> sha1s, List<String> loaders, List<String> gameVersions)
      throws IOException, InterruptedException {
    String uri = apiBaseUrl + "/version_files/update";
    Response response = postJson(
        uri,
        Map.of(
            "hashes", sha1s,
            "algorithm", "sha1",
            "loaders", loaders,
            "game_versions", gameVersions));
    logBulkResponse(uri, response);
    requireSuccess(response, uri);
    return readVersionMap(response.bodyAsString());
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

  private ModUpdate toModUpdate(Version currentVersion, Version latestVersion, String projectName) {
    if (latestVersion == null) {
      return new ModUpdate(
          currentVersion.projectId(),
          projectName,
          currentVersion.id(),
          currentVersion.id(),
          currentVersion.name(),
          currentVersion.publishedAtInstant(),
          null,
          null);
    }
    File latestFile = latestVersion.primaryFile().orElse(null);
    URI downloadUrl = null;
    String fileName = null;
    if (latestFile != null && latestFile.url() != null && latestFile.filename() != null) {
      downloadUrl = URI.create(latestFile.url());
      fileName = latestFile.filename();
    }
    return new ModUpdate(
        currentVersion.projectId(),
        projectName,
        currentVersion.id(),
        latestVersion.id(),
        latestVersion.name(),
        latestVersion.publishedAtInstant(),
        downloadUrl,
        fileName);
  }

  private Map<String, Version> readVersionMap(String body) throws IOException {
    Map<String, Version> versionMap = objectMapper.readValue(
        body,
        objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Version.class));
    return versionMap.entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            Map.Entry::getValue,
            (left, right) -> left,
            LinkedHashMap::new));
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

  private static void requireSuccess(Response response, String uri) throws IOException {
    if (response.statusCode() / 100 != 2) {
      throw new IOException(
          "Modrinth request failed with HTTP " + response.statusCode() + " for " + uri);
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

  private static boolean isHttpStatus(IOException exception, int statusCode) {
    return exception.getMessage() != null
        && exception.getMessage().contains("HTTP " + statusCode);
  }

  private void logBulkResponse(String uri, Response response) {
    if (!uri.endsWith("/version_files") && !uri.endsWith("/version_files/update")) {
      return;
    }
    if (response.statusCode() / 100 == 2) {
      log.info("Modrinth bulk call {} -> HTTP {}", uri, response.statusCode());
      return;
    }
    log.warn(
        "Modrinth bulk call {} -> HTTP {} body: {}",
        uri,
        response.statusCode(),
        response.bodyAsString());
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

  private static final class ProjectLookupRuntimeException extends RuntimeException {
    private final String projectId;

    private ProjectLookupRuntimeException(String projectId, Throwable cause) {
      super(cause);
      this.projectId = projectId;
    }

    private String projectId() {
      return projectId;
    }
  }

  private record UpdateCriteria(List<String> loaders, List<String> gameVersions) {
    private static UpdateCriteria from(Version version) {
      return new UpdateCriteria(
          List.copyOf(requestedLoaders(version)),
          List.copyOf(requestedGameVersions(version)));
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

    public Optional<File> fileByName(String fileName) {
      if (fileName == null || fileName.isBlank() || files == null || files.isEmpty()) {
        return Optional.empty();
      }
      return files.stream()
          .filter(file -> fileName.equals(file.filename()))
          .findFirst();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record File(String filename, String url, boolean primary) {
  }
}
