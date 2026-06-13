package nl.jessedezwart.piglinpanel.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ModHashCacheStore {
  private static final TypeReference<LinkedHashMap<String, CacheEntry>> CACHE_TYPE =
      new TypeReference<>() {};

  private final ObjectMapper objectMapper;
  private final Path cachePath;
  private final Map<String, CacheEntry> entries;

  @Autowired
  public ModHashCacheStore(ObjectMapper objectMapper) {
    this(objectMapper, resolveCachePath());
  }

  public ModHashCacheStore(ObjectMapper objectMapper, Path cachePath) {
    this.objectMapper = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
    this.cachePath = cachePath;
    this.entries = loadEntries();
  }

  public synchronized Optional<CachedMetadata> lookup(ServerProfile profile, FileEntry entry) {
    String key = key(profile, entry.path());
    CacheEntry cached = entries.get(key);
    if (cached == null) {
      return Optional.empty();
    }
    if (cached.matches(entry)) {
      return Optional.of(new CachedMetadata(cached.getSha1(), cached.getProjectName()));
    }
    entries.remove(key);
    saveEntries();
    return Optional.empty();
  }

  public synchronized void put(ServerProfile profile, FileEntry entry, String sha1) {
    put(profile, entry, sha1, null);
  }

  public synchronized void put(
      ServerProfile profile, FileEntry entry, String sha1, String projectName) {
    entries.put(
        key(profile, entry.path()),
        new CacheEntry(sha1, projectName, entry.size(), entry.modifiedAt().toEpochMilli()));
    saveEntries();
  }

  public synchronized void move(
      ServerProfile profile, String sourceRelativePath, String targetRelativePath) {
    CacheEntry cached = entries.remove(key(profile, sourceRelativePath));
    if (cached != null) {
      entries.put(key(profile, targetRelativePath), cached);
      saveEntries();
    }
  }

  public synchronized void remove(ServerProfile profile, String relativePath) {
    if (entries.remove(key(profile, relativePath)) != null) {
      saveEntries();
    }
  }

  private Map<String, CacheEntry> loadEntries() {
    if (!Files.exists(cachePath)) {
      return new LinkedHashMap<>();
    }
    try {
      return objectMapper.readValue(cachePath.toFile(), CACHE_TYPE);
    } catch (IOException e) {
      throw new IllegalStateException("Could not read mod hash cache from " + cachePath, e);
    }
  }

  private void saveEntries() {
    try {
      Files.createDirectories(cachePath.getParent());
      objectMapper.writeValue(cachePath.toFile(), entries);
    } catch (IOException e) {
      throw new IllegalStateException("Could not save mod hash cache to " + cachePath, e);
    }
  }

  private static Path resolveCachePath() {
    String appData = System.getenv("APPDATA");
    if (appData != null && !appData.isBlank()) {
      return Path.of(appData, "Piglinpanel", "mod-hashes.json");
    }
    return Path.of(System.getProperty("user.home"), ".config", "piglinpanel", "mod-hashes.json");
  }

  private static String key(ServerProfile profile, String relativePath) {
    return profile.getId() + ":" + relativePath;
  }

  public record CachedMetadata(String sha1, String projectName) {
  }

  static class CacheEntry {
    private String sha1;
    private String projectName;
    private long size;
    private long modifiedAtEpochMilli;

    CacheEntry() {}

    CacheEntry(String sha1, String projectName, long size, long modifiedAtEpochMilli) {
      this.sha1 = sha1;
      this.projectName = projectName;
      this.size = size;
      this.modifiedAtEpochMilli = modifiedAtEpochMilli;
    }

    public String getSha1() {
      return sha1;
    }

    public void setSha1(String sha1) {
      this.sha1 = sha1;
    }

    public String getProjectName() {
      return projectName;
    }

    public void setProjectName(String projectName) {
      this.projectName = projectName;
    }

    public long getSize() {
      return size;
    }

    public void setSize(long size) {
      this.size = size;
    }

    public long getModifiedAtEpochMilli() {
      return modifiedAtEpochMilli;
    }

    public void setModifiedAtEpochMilli(long modifiedAtEpochMilli) {
      this.modifiedAtEpochMilli = modifiedAtEpochMilli;
    }

    boolean matches(FileEntry entry) {
      return size == entry.size()
          && modifiedAtEpochMilli == entry.modifiedAt().toEpochMilli()
          && sha1 != null
          && !sha1.isBlank();
    }
  }
}
