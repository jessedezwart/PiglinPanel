package nl.jessedezwart.piglinpanel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.client.ModrinthClient;
import nl.jessedezwart.piglinpanel.config.ModHashCacheStore;
import nl.jessedezwart.piglinpanel.filesystem.LocalServerFileSystem;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModServiceTests {
    @TempDir
    Path tempDir;

    @Test
    void scansMultipleModFoldersAndSeparatesArchivedFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("mods"));
        Files.createDirectories(tempDir.resolve("client-mods"));
        Files.write(tempDir.resolve("mods/server.jar"), new byte[] { 1, 2, 3 });
        Files.write(tempDir.resolve("client-mods/client.jar.disabled"), new byte[] { 4, 5, 6 });
        ServerProfile profile = new ServerProfile();
        profile.setRootPath(tempDir.toString());
        profile.setModFolders(
                List.of(
                        new ModFolder("server", "Server", "mods"),
                        new ModFolder("client", "Client", "client-mods")));
        ModService service = new ModService(
                new ServerConnectionService(),
                mock(ModrinthClient.class),
                new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json")));

        var mods = service.scan(profile, false);

        assertThat(mods).hasSize(2);
        assertThat(mods).extracting("folderPath").containsExactlyInAnyOrder("mods", "client-mods");
        assertThat(mods.stream().filter(mod -> mod.archived()).map(mod -> mod.fileName()))
                .containsExactly("client.jar.disabled");
    }

    @Test
    void attachesSuggestedModrinthUpdatesWhenAvailable() throws Exception {
        Files.createDirectories(tempDir.resolve("mods"));
        Files.write(tempDir.resolve("mods/server.jar"), new byte[] { 1, 2, 3 });
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
                                                "mod.jar"))));
        ModService service = new ModService(
                new ServerConnectionService(),
                client,
                new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json")));

        var mods = service.scan(profile, true);

        assertThat(mods)
                .singleElement()
                .extracting(ModFile -> ModFile.update().latestVersionName())
                .isEqualTo("2.0.0");
    }

    @Test
    void streamsBulkUpdateResultsToCallbackAfterLookupCompletes() throws Exception {
        Files.createDirectories(tempDir.resolve("mods"));
        Files.write(tempDir.resolve("mods/server.jar"), new byte[] { 1, 2, 3 });
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
                                                "mod.jar"))));
        ModService service = new ModService(
                new ServerConnectionService(),
                client,
                new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json")));
        List<nl.jessedezwart.piglinpanel.model.ModFile> streamed = new ArrayList<>();

        service.scan(profile, true, streamed::add);

        assertThat(streamed)
                .singleElement()
                .extracting(mod -> mod.update().latestVersionName())
                .isEqualTo("2.0.0");
    }

    @Test
    void reusesCachedProjectNameWithoutCheckingUpdates() throws Exception {
        Files.createDirectories(tempDir.resolve("mods"));
        Files.write(tempDir.resolve("mods/server.jar"), new byte[] { 1, 2, 3 });
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
                                                "old",
                                                "1.0.0",
                                                Instant.parse("2026-06-10T12:00:00Z"),
                                                null,
                                                null))));
        ModService service = new ModService(
                new ServerConnectionService(),
                client,
                new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json")));

        service.scan(profile, true);
        var mods = service.scan(profile, false);

        assertThat(mods)
                .singleElement()
                .extracting(mod -> mod.update().projectName())
                .isEqualTo("Project");
    }

    @Test
    void reusesCachedHashWhenFileMetadataStillMatches() throws Exception {
        Files.createDirectories(tempDir.resolve("mods"));
        Files.write(tempDir.resolve("mods/server.jar"), new byte[] { 1, 2, 3 });
        ServerProfile profile = new ServerProfile();
        profile.setRootPath(tempDir.toString());
        CountingLocalServerFileSystem fileSystem = new CountingLocalServerFileSystem(profile);
        ServerConnectionService connectionService = mock(ServerConnectionService.class);
        when(connectionService.fileSystem(profile)).thenReturn(fileSystem);
        ModrinthClient client = mock(ModrinthClient.class);
        when(client.findSuggestedUpdates(anyList()))
                .thenReturn(java.util.Map.of("7037807198c22a7d2b0807371d763779a84fdfcf", Optional.empty()));
        ModService service = new ModService(
                connectionService,
                client,
                new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json")));

        service.scan(profile, true);
        service.scan(profile, true);

        assertThat(fileSystem.readCount()).isEqualTo(1);
    }

    @Test
    void cachesUploadedJarHashClientSide() throws Exception {
        Files.createDirectories(tempDir.resolve("mods"));
        ServerProfile profile = new ServerProfile();
        profile.setRootPath(tempDir.toString());
        CountingLocalServerFileSystem fileSystem = new CountingLocalServerFileSystem(profile);
        ServerConnectionService connectionService = mock(ServerConnectionService.class);
        when(connectionService.fileSystem(profile)).thenReturn(fileSystem);
        ModrinthClient client = mock(ModrinthClient.class);
        when(client.findSuggestedUpdates(anyList()))
                .thenReturn(java.util.Map.of("e0eae4bc0bb3d8031aca9205092a66f1dd07113a", Optional.empty()));
        ModService service = new ModService(
                connectionService,
                client,
                new ModHashCacheStore(new ObjectMapper(), tempDir.resolve("mod-hashes.json")));

        service.upload(
                profile, profile.getModFolders().getFirst(), "uploaded.jar", new byte[] { 9, 8, 7 });
        service.scan(profile, true);

        assertThat(fileSystem.readCount()).isZero();
    }

    @Test
    void archiveNameAddsOldSuffix() {
        assertThat(ModService.nextArchiveName("mods/example.jar")).isEqualTo("mods/example.jar.disabled");
        assertThat(ModService.nextArchiveName("mods/example.jar.disabled"))
                .isEqualTo("mods/example.jar.disabled");
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
