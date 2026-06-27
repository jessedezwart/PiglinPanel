package nl.jessedezwart.piglinpanel.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import nl.jessedezwart.piglinpanel.filesystem.LocalServerFileSystem;
import nl.jessedezwart.piglinpanel.model.FileEntry;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ModHashCachingTests {

  @TempDir static Path cacheDir;

  @DynamicPropertySource
  static void cacheProperties(DynamicPropertyRegistry registry) {
    // Isolate the ehcache disk store from the real install so the test never fights for the lock.
    registry.add("piglinpanel.cache.dir", () -> cacheDir.resolve("cache").toString());
  }

  @Autowired ModService modService;

  @TempDir Path serverRoot;

  @Test
  void resolveSha1IsCachedByPathSizeAndModifiedTime() throws Exception {
    Path mods = Files.createDirectories(serverRoot.resolve("mods"));
    Path jar = mods.resolve("example.jar");
    Files.write(jar, "hello".getBytes(StandardCharsets.UTF_8));
    FileTime modifiedTime = Files.getLastModifiedTime(jar);

    ServerProfile profile = new ServerProfile();
    profile.setRootPath(serverRoot.toString());
    LocalServerFileSystem fileSystem = new LocalServerFileSystem(profile);
    FileEntry entry = fileSystem.list("mods").get(0);

    String firstHash = modService.resolveSha1(profile, fileSystem, entry);

    // Change the bytes but keep identical size + modified time so the cache key stays the same.
    Files.write(jar, "world".getBytes(StandardCharsets.UTF_8));
    Files.setLastModifiedTime(jar, modifiedTime);
    FileEntry unchangedKeyEntry = fileSystem.list("mods").get(0);

    String secondHash = modService.resolveSha1(profile, fileSystem, unchangedKeyEntry);

    // A cache hit returns the original hash; a cache miss would re-hash and return a different
    // value.
    assertThat(secondHash).isEqualTo(firstHash);
  }
}
