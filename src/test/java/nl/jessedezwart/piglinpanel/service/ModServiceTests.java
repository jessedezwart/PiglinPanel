package nl.jessedezwart.piglinpanel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.client.ModrinthClient;
import nl.jessedezwart.piglinpanel.filesystem.LocalServerFileSystem;
import nl.jessedezwart.piglinpanel.model.ModFile;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModGroup;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ModServiceTests {
  @TempDir Path tempDir;

  private static ModService newService(ServerConnectionService conn, ModrinthClient client) {
    ModService service = new ModService(conn, client);
    service.self = service;
    return service;
  }

  private static void withCachingService(
      ServerConnectionService conn, ModrinthClient client, ThrowingConsumer<ModService> body)
      throws Exception {
    MocksConfig.connectionService = conn;
    MocksConfig.modrinthClient = client;
    try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
      ctx.register(CachingConfig.class, MocksConfig.class, ModService.class);
      ctx.refresh();
      body.accept(ctx.getBean(ModService.class));
    } finally {
      MocksConfig.connectionService = null;
      MocksConfig.modrinthClient = null;
    }
  }

  @FunctionalInterface
  private interface ThrowingConsumer<T> {
    void accept(T value) throws Exception;
  }

  @Configuration
  @EnableCaching
  static class CachingConfig {
    @Bean
    CacheManager cacheManager() {
      return new ConcurrentMapCacheManager("mod-hashes", "mod-updates");
    }
  }

  @Configuration
  static class MocksConfig {
    static ServerConnectionService connectionService;
    static ModrinthClient modrinthClient;

    @Bean
    ServerConnectionService serverConnectionService() {
      return connectionService;
    }

    @Bean
    ModrinthClient modrinthClient() {
      return modrinthClient;
    }
  }

  @Test
  void scansMultipleModFoldersAndSeparatesArchivedFiles() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.createDirectories(tempDir.resolve("client-mods"));
    Files.write(tempDir.resolve("mods/server.jar"), new byte[] {1, 2, 3});
    Files.write(tempDir.resolve("client-mods/client.jar.disabled"), new byte[] {4, 5, 6});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    profile.setModFolders(
        List.of(
            new ModFolder("server", "Server", "mods"),
            new ModFolder("client", "Client", "client-mods")));
    ModService service = newService(new ServerConnectionService(), mock(ModrinthClient.class));

    var mods = service.scan(profile, false);

    assertThat(mods).hasSize(2);
    assertThat(mods).extracting("folderPath").containsExactlyInAnyOrder("mods", "client-mods");
    assertThat(mods.stream().filter(mod -> mod.archived()).map(mod -> mod.fileName()))
        .containsExactly("client.jar.disabled");
  }

  @Test
  void attachesSuggestedModrinthUpdatesWhenAvailable() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.write(tempDir.resolve("mods/server.jar"), new byte[] {1, 2, 3});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    ModrinthClient client = mock(ModrinthClient.class);
    when(client.findSuggestedUpdates(anyList()))
        .thenReturn(
            java.util.Map.of(
                "7037807198c22a7d2b0807371d763779a84fdfcf",
                Optional.of(
                    new ModUpdate(
                        "project",
                        "Project",
                        "old",
                        "new",
                        "2.0.0",
                        Instant.parse("2026-06-10T12:00:00Z"),
                        URI.create("https://example.com/mod.jar"),
                        "mod.jar",
                        null,
                        null,
                        null,
                        0L))));
    ModService service = newService(new ServerConnectionService(), client);

    var mods = service.scan(profile, true);

    assertThat(mods)
        .singleElement()
        .extracting(mod -> mod.update().latestVersionName())
        .isEqualTo("2.0.0");
  }

  @Test
  void streamsBulkUpdateResultsToCallbackAfterLookupCompletes() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.write(tempDir.resolve("mods/server.jar"), new byte[] {1, 2, 3});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    ModrinthClient client = mock(ModrinthClient.class);
    when(client.findSuggestedUpdates(anyList()))
        .thenReturn(
            java.util.Map.of(
                "7037807198c22a7d2b0807371d763779a84fdfcf",
                Optional.of(
                    new ModUpdate(
                        "project",
                        "Project",
                        "old",
                        "new",
                        "2.0.0",
                        Instant.parse("2026-06-10T12:00:00Z"),
                        URI.create("https://example.com/mod.jar"),
                        "mod.jar",
                        null,
                        null,
                        null,
                        0L))));
    ModService service = newService(new ServerConnectionService(), client);
    List<nl.jessedezwart.piglinpanel.model.ModFile> streamed = new ArrayList<>();

    service.scan(profile, true, streamed::add);

    assertThat(streamed)
        .singleElement()
        .extracting(mod -> mod.update().latestVersionName())
        .isEqualTo("2.0.0");
  }

  @Test
  void reusesCachedHashWhenFileMetadataStillMatches() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.write(tempDir.resolve("mods/server.jar"), new byte[] {1, 2, 3});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    CountingLocalServerFileSystem fileSystem = new CountingLocalServerFileSystem(profile);
    ServerConnectionService connectionService = mock(ServerConnectionService.class);
    when(connectionService.fileSystem(profile)).thenReturn(fileSystem);
    ModrinthClient client = mock(ModrinthClient.class);
    when(client.findSuggestedUpdates(anyList()))
        .thenReturn(java.util.Map.of("7037807198c22a7d2b0807371d763779a84fdfcf", Optional.empty()));
    withCachingService(
        connectionService,
        client,
        service -> {
          service.scan(profile, true);
          service.scan(profile, true);
        });

    assertThat(fileSystem.readCount()).isEqualTo(1);
  }

  @Test
  void archiveNameAddsOldSuffix() {
    assertThat(ModService.nextArchiveName("mods/example.jar"))
        .isEqualTo("mods/example.jar.disabled");
    assertThat(ModService.nextArchiveName("mods/example.jar.disabled"))
        .isEqualTo("mods/example.jar.disabled");
  }

  @Test
  void enableNameStripsDisabledSuffix() {
    assertThat(ModService.enableName("mods/example.jar.disabled")).isEqualTo("mods/example.jar");
    assertThat(ModService.enableName("mods/example.jar")).isEqualTo("mods/example.jar");
  }

  @Test
  void cachesSuggestedUpdatesAndSkipsSecondLookup() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.write(tempDir.resolve("mods/server.jar"), new byte[] {1, 2, 3});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    ServerConnectionService connectionService = mock(ServerConnectionService.class);
    when(connectionService.fileSystem(profile)).thenReturn(new LocalServerFileSystem(profile));
    ModrinthClient client = mock(ModrinthClient.class);
    when(client.findSuggestedUpdates(anyList()))
        .thenReturn(
            java.util.Map.of(
                "7037807198c22a7d2b0807371d763779a84fdfcf",
                Optional.of(
                    new ModUpdate(
                        "project",
                        "Project",
                        "old",
                        "new",
                        "2.0.0",
                        Instant.parse("2026-06-10T12:00:00Z"),
                        URI.create("https://example.com/mod.jar"),
                        "mod.jar",
                        null,
                        null,
                        null,
                        0L))));

    withCachingService(
        connectionService,
        client,
        service -> {
          service.scan(profile, true);
          service.scan(profile, true);
        });

    verify(client, times(1)).findSuggestedUpdates(anyList());
  }

  @Test
  void groupsVersionsByProjectWithActiveSurfaced() {
    ModService service =
        newService(mock(ServerConnectionService.class), mock(ModrinthClient.class));
    ModUpdate update = projectUpdate("create");
    ModFile active =
        new ModFile(
            "mods",
            "mods",
            "mods",
            "mods/create-5.1.jar",
            "create-5.1.jar",
            20,
            false,
            "h1",
            update);
    ModFile archived =
        new ModFile(
            "mods",
            "mods",
            "mods",
            "mods/create-5.0.jar.disabled",
            "create-5.0.jar.disabled",
            19,
            true,
            "h2",
            update);

    List<ModGroup> groups = service.group(List.of(active, archived));

    assertThat(groups).hasSize(1);
    ModGroup group = groups.get(0);
    assertThat(group.active().fileName()).isEqualTo("create-5.1.jar");
    assertThat(group.archived())
        .extracting(ModFile::fileName)
        .containsExactly("create-5.0.jar.disabled");
    assertThat(group.displayName()).isEqualTo("Create");
  }

  @Test
  void activateEnablesArchivedAndDisablesCurrent() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.write(tempDir.resolve("mods/create-5.1.jar"), new byte[] {1});
    Files.write(tempDir.resolve("mods/create-5.0.jar.disabled"), new byte[] {2});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    ModService service = newService(new ServerConnectionService(), mock(ModrinthClient.class));

    service.activate(profile, "mods/create-5.0.jar.disabled", "mods/create-5.1.jar");

    assertThat(Files.exists(tempDir.resolve("mods/create-5.0.jar"))).isTrue();
    assertThat(Files.exists(tempDir.resolve("mods/create-5.1.jar.disabled"))).isTrue();
    assertThat(Files.exists(tempDir.resolve("mods/create-5.0.jar.disabled"))).isFalse();
    assertThat(Files.exists(tempDir.resolve("mods/create-5.1.jar"))).isFalse();
  }

  private static ModUpdate projectUpdate(String projectId) {
    return new ModUpdate(
        projectId,
        "Create",
        "v1",
        "v1",
        "5.1",
        Instant.parse("2026-06-10T12:00:00Z"),
        null,
        null,
        "https://example.com/icon.png",
        null,
        null,
        0L);
  }

  private static class CountingLocalServerFileSystem extends LocalServerFileSystem {
    private int readCount;

    CountingLocalServerFileSystem(ServerProfile profile) {
      super(profile);
    }

    @Override
    public byte[] readBytes(String relativePath) throws java.io.IOException {
      readCount++;
      return super.readBytes(relativePath);
    }

    int readCount() {
      return readCount;
    }
  }
}
