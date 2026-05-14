package nl.jessedezwart.piglinpanel.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import org.springframework.stereotype.Component;

@Component
public class ModrinthClient {
  private static final String API_BASE_URL = "https://api.modrinth.com/v2";
  private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper objectMapper;

  public ModrinthClient(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
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
    List<Version> versions = versionsForProject(currentVersion.projectId(), currentVersion.gameVersions());
    Optional<Version> latest = versions.stream()
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
            downloadUrl,
            fileName));
  }

  public byte[] download(URI uri) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(Duration.ofSeconds(20))
        .header("User-Agent", "Piglinpanel/0.1")
        .GET()
        .build();
    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Download failed with HTTP " + response.statusCode());
    }
    return response.body();
  }

  private Optional<Version> versionByHash(String sha1) throws IOException, InterruptedException {
    HttpRequest request = request(API_BASE_URL + "/version_file/" + encode(sha1) + "?algorithm=sha1");
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    requireSuccess(response);
    return Optional.of(objectMapper.readValue(response.body(), Version.class));
  }

  private Optional<Project> project(String projectId) throws IOException, InterruptedException {
    HttpResponse<String> response = httpClient.send(
        request(API_BASE_URL + "/project/" + encode(projectId)),
        HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      return Optional.empty();
    }
    requireSuccess(response);
    return Optional.of(objectMapper.readValue(response.body(), Project.class));
  }

  private List<Version> versionsForProject(String projectId, List<String> gameVersions)
      throws IOException, InterruptedException {
    String loaders = encode("[\"neoforge\"]");
    String gameVersionParam = gameVersions == null || gameVersions.isEmpty()
        ? ""
        : "&game_versions=" + encode(objectMapper.writeValueAsString(gameVersions));
    HttpResponse<String> response = httpClient.send(
        request(
            API_BASE_URL
                + "/project/"
                + encode(projectId)
                + "/version?loaders="
                + loaders
                + gameVersionParam),
        HttpResponse.BodyHandlers.ofString());
    requireSuccess(response);
    JsonNode node = objectMapper.readTree(response.body());
    return objectMapper.readerForListOf(Version.class).readValue(node);
  }

  private static HttpRequest request(String uri) {
    return HttpRequest.newBuilder(URI.create(uri))
        .timeout(Duration.ofSeconds(10))
        .header("User-Agent", "Piglinpanel/0.1")
        .GET()
        .build();
  }

  private static void requireSuccess(HttpResponse<?> response) throws IOException {
    if (response.statusCode() / 100 != 2) {
      throw new IOException("Modrinth request failed with HTTP " + response.statusCode());
    }
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
