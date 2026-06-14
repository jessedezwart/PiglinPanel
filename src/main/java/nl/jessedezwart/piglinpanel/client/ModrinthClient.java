package nl.jessedezwart.piglinpanel.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.Serializable;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class ModrinthClient {
  private static final String API_BASE_URL = "https://api.modrinth.com/v2";
  private static final Logger log = LoggerFactory.getLogger(ModrinthClient.class);

  private final ObjectMapper objectMapper;
  private final RestClient restClient;
  private final String apiBaseUrl;

  @Autowired
  public ModrinthClient(ObjectMapper objectMapper) {
    this(objectMapper, restClient(), API_BASE_URL);
  }

  private ModrinthClient(ObjectMapper objectMapper, RestClient restClient, String apiBaseUrl) {
    this.objectMapper = objectMapper;
    this.restClient = restClient;
    this.apiBaseUrl = apiBaseUrl;
  }

  static ModrinthClient createForTesting(
      ObjectMapper objectMapper, RestClient restClient, String apiBaseUrl) {
    return new ModrinthClient(objectMapper, restClient, apiBaseUrl);
  }

  public Optional<ModUpdate> findSuggestedUpdate(String sha1) throws IOException {
    return findSuggestedUpdates(List.of(sha1)).getOrDefault(sha1, Optional.empty());
  }

  public Map<String, Optional<ModUpdate>> findSuggestedUpdates(List<String> sha1s)
      throws IOException {
    if (sha1s == null || sha1s.isEmpty()) {
      return Map.of();
    }
    List<String> requestedHashes =
        sha1s.stream().filter(hash -> hash != null && !hash.isBlank()).distinct().toList();
    if (requestedHashes.isEmpty()) {
      return Map.of();
    }

    Map<String, Version> currentVersions = versionsByHashes(requestedHashes);

    List<String> projectIds =
        currentVersions.values().stream()
            .map(Version::projectId)
            .filter(id -> id != null && !id.isBlank())
            .distinct()
            .toList();
    Map<String, Project> projects = getProjects(projectIds);

    Map<UpdateCriteria, List<String>> hashesByCriteria = new LinkedHashMap<>();
    for (Map.Entry<String, Version> entry : currentVersions.entrySet()) {
      hashesByCriteria
          .computeIfAbsent(UpdateCriteria.from(entry.getValue()), ignored -> new ArrayList<>())
          .add(entry.getKey());
    }

    Map<String, Version> latestVersions = new LinkedHashMap<>();
    for (Map.Entry<UpdateCriteria, List<String>> entry : hashesByCriteria.entrySet()) {
      UpdateCriteria criteria = entry.getKey();
      latestVersions.putAll(
          latestVersionsForHashes(entry.getValue(), criteria.loaders(), criteria.gameVersions()));
    }

    Map<String, Optional<ModUpdate>> updates = new LinkedHashMap<>();
    for (String sha1 : requestedHashes) {
      Version currentVersion = currentVersions.get(sha1);
      if (currentVersion == null) {
        updates.put(sha1, Optional.empty());
        continue;
      }
      Project project = projects.get(currentVersion.projectId());
      Version latestVersion = latestVersions.get(sha1);
      updates.put(sha1, Optional.of(toModUpdate(currentVersion, latestVersion, project)));
    }
    return updates;
  }

  @Cacheable(
      value = "modrinth-projects",
      key = "T(nl.jessedezwart.piglinpanel.client.ModrinthClient).projectsCacheKey(#projectIds)",
      condition = "!#projectIds.isEmpty()")
  public Map<String, Project> getProjects(List<String> projectIds) throws IOException {
    if (projectIds == null || projectIds.isEmpty()) {
      return Map.of();
    }
    String idsJson = objectMapper.writeValueAsString(projectIds);
    String uri = apiBaseUrl + "/projects?ids=" + URLEncoder.encode(idsJson, StandardCharsets.UTF_8);
    Response response = get(uri);
    requireSuccess(response, uri);
    List<Project> projectList =
        objectMapper.readValue(
            response.bodyAsString(),
            objectMapper.getTypeFactory().constructCollectionType(List.class, Project.class));
    return projectList.stream()
        .filter(p -> p.id() != null)
        .collect(Collectors.toMap(Project::id, p -> p, (a, b) -> a, LinkedHashMap::new));
  }

  public byte[] download(URI uri) throws IOException {
    Response response = get(uri.toString());
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Download failed with HTTP " + response.statusCode() + " for " + uri);
    }
    return response.body();
  }

  public byte[] downloadUpdate(ModUpdate update) throws IOException {
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
    File file =
        version
            .fileByName(update.fileName())
            .or(() -> version.primaryFile())
            .orElseThrow(
                () ->
                    new IOException(
                        "No downloadable file found for Modrinth version "
                            + update.latestVersionId()));
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

  private Version version(String projectId, String versionId) throws IOException {
    String uri = apiBaseUrl + "/project/" + encode(projectId) + "/version/" + encode(versionId);
    Response response = get(uri);
    requireSuccess(response, uri);
    return objectMapper.readValue(response.bodyAsString(), Version.class);
  }

  private Map<String, Version> versionsByHashes(List<String> sha1s) throws IOException {
    String uri = apiBaseUrl + "/version_files";
    Response response = postJson(uri, Map.of("hashes", sha1s, "algorithm", "sha1"));
    logBulkResponse(uri, response);
    requireSuccess(response, uri);
    return readVersionMap(response.bodyAsString());
  }

  private Map<String, Version> latestVersionsForHashes(
      List<String> sha1s, List<String> loaders, List<String> gameVersions) throws IOException {
    String uri = apiBaseUrl + "/version_files/update";
    Response response =
        postJson(
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

  private ModUpdate toModUpdate(Version currentVersion, Version latestVersion, Project project) {
    String projectName = project != null ? project.title() : null;
    String iconUrl = project != null ? project.iconUrl() : null;
    String clientSide = project != null ? project.clientSide() : null;
    String serverSide = project != null ? project.serverSide() : null;
    long downloads = project != null ? project.downloads() : 0L;
    if (latestVersion == null) {
      return new ModUpdate(
          currentVersion.projectId(),
          projectName,
          currentVersion.id(),
          currentVersion.id(),
          currentVersion.name(),
          currentVersion.publishedAtInstant(),
          null,
          null,
          iconUrl,
          clientSide,
          serverSide,
          downloads);
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
        fileName,
        iconUrl,
        clientSide,
        serverSide,
        downloads);
  }

  private Map<String, Version> readVersionMap(String body) throws IOException {
    Map<String, Version> versionMap =
        objectMapper.readValue(
            body,
            objectMapper
                .getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Version.class));
    return versionMap.entrySet().stream()
        .filter(entry -> entry.getKey() != null && entry.getValue() != null)
        .collect(
            Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left, LinkedHashMap::new));
  }

  private Response get(String uri) throws IOException {
    try {
      return restClient
          .get()
          .uri(URI.create(uri))
          .exchange(
              (req, resp) ->
                  new Response(resp.getStatusCode().value(), resp.getBody().readAllBytes()));
    } catch (RestClientException e) {
      throw new IOException("Modrinth request failed for " + uri, e);
    }
  }

  private Response postJson(String uri, Object body) throws IOException {
    try {
      return restClient
          .post()
          .uri(URI.create(uri))
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .exchange(
              (req, resp) ->
                  new Response(resp.getStatusCode().value(), resp.getBody().readAllBytes()));
    } catch (RestClientException e) {
      throw new IOException("Modrinth request failed for " + uri, e);
    }
  }

  private static void requireSuccess(Response response, String uri) throws IOException {
    if (response.statusCode() / 100 != 2) {
      throw new IOException(
          "Modrinth request failed with HTTP " + response.statusCode() + " for " + uri);
    }
  }

  private static RestClient restClient() {
    return RestClient.builder().defaultHeader(HttpHeaders.USER_AGENT, "Piglinpanel/0.1").build();
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static boolean isHttpStatus(IOException exception, int statusCode) {
    return exception.getMessage() != null && exception.getMessage().contains("HTTP " + statusCode);
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

  private record UpdateCriteria(List<String> loaders, List<String> gameVersions) {
    private static UpdateCriteria from(Version version) {
      return new UpdateCriteria(
          List.copyOf(requestedLoaders(version)), List.copyOf(requestedGameVersions(version)));
    }
  }

  public static String projectsCacheKey(List<String> ids) {
    return ids.stream().sorted().collect(Collectors.joining(","));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Project(
      String id,
      String title,
      @JsonProperty("icon_url") String iconUrl,
      @JsonProperty("client_side") String clientSide,
      @JsonProperty("server_side") String serverSide,
      long downloads)
      implements Serializable {}

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
      return files.stream().filter(file -> fileName.equals(file.filename())).findFirst();
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record File(String filename, String url, boolean primary) {}
}
