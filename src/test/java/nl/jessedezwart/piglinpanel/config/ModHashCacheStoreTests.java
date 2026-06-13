package nl.jessedezwart.piglinpanel.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModHashCacheStoreTests {
  @TempDir Path tempDir;

  @Test
  void returnsHashWhenFileMetadataStillMatches() {
    ModHashCacheStore store =
        new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json"));
    ServerProfile profile = new ServerProfile();
    FileEntry entry = new FileEntry("mods/example.jar", "example.jar", false, 123, Instant.now());

    store.put(profile, entry, "abc123", "Example Project");

    assertThat(store.lookup(profile, entry))
        .contains(new ModHashCacheStore.CachedMetadata("abc123", "Example Project"));
  }

  @Test
  void dropsHashWhenFileMetadataChanges() {
    ModHashCacheStore store =
        new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json"));
    ServerProfile profile = new ServerProfile();
    Instant modifiedAt = Instant.now();
    FileEntry original = new FileEntry("mods/example.jar", "example.jar", false, 123, modifiedAt);

    store.put(profile, original, "abc123", "Example Project");

    FileEntry changed =
        new FileEntry("mods/example.jar", "example.jar", false, 456, modifiedAt.plusSeconds(1));

    assertThat(store.lookup(profile, changed)).isEmpty();
    assertThat(new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json"))
            .lookup(profile, changed))
        .isEmpty();
  }

  @Test
  void persistsProjectNameAcrossReloads() {
    ModHashCacheStore store =
        new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json"));
    ServerProfile profile = new ServerProfile();
    FileEntry entry = new FileEntry("mods/example.jar", "example.jar", false, 123, Instant.now());

    store.put(profile, entry, "abc123", "Example Project");

    assertThat(new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json"))
            .lookup(profile, entry))
        .contains(new ModHashCacheStore.CachedMetadata("abc123", "Example Project"));
  }
}
