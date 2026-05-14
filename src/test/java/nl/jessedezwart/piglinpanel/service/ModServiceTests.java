package nl.jessedezwart.piglinpanel.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import nl.jessedezwart.piglinpanel.client.ModrinthClient;
import nl.jessedezwart.piglinpanel.model.ModFolder;
import nl.jessedezwart.piglinpanel.model.ModUpdate;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModServiceTests {
  @TempDir Path tempDir;

  @Test
  void scansMultipleModFoldersAndSeparatesArchivedFiles() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.createDirectories(tempDir.resolve("client-mods"));
    Files.write(tempDir.resolve("mods/server.jar"), new byte[] {1, 2, 3});
    Files.write(tempDir.resolve("client-mods/client.jar.old"), new byte[] {4, 5, 6});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    profile.setModFolders(
        List.of(
            new ModFolder("server", "Server", "mods"),
            new ModFolder("client", "Client", "client-mods")));
    ModService service = new ModService(new ServerConnectionService(), mock(ModrinthClient.class));

    var mods = service.scan(profile, false);

    assertThat(mods).hasSize(2);
    assertThat(mods).extracting("folderPath").containsExactlyInAnyOrder("mods", "client-mods");
    assertThat(mods.stream().filter(mod -> mod.archived()).map(mod -> mod.fileName()))
        .containsExactly("client.jar.old");
  }

  @Test
  void attachesSuggestedModrinthUpdatesWhenAvailable() throws Exception {
    Files.createDirectories(tempDir.resolve("mods"));
    Files.write(tempDir.resolve("mods/server.jar"), new byte[] {1, 2, 3});
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    ModrinthClient client = mock(ModrinthClient.class);
    when(client.findSuggestedUpdate(anyString()))
        .thenReturn(
            Optional.of(
                new ModUpdate(
                    "project",
                    "Project",
                    "old",
                    "new",
                    "2.0.0",
                    URI.create("https://example.com/mod.jar"),
                    "mod.jar")));
    ModService service = new ModService(new ServerConnectionService(), client);

    var mods = service.scan(profile, true);

    assertThat(mods)
        .singleElement()
        .extracting(ModFile -> ModFile.update().latestVersionName())
        .isEqualTo("2.0.0");
  }

  @Test
  void archiveNameAddsOldSuffix() {
    assertThat(ModService.nextArchiveName("mods/example.jar")).isEqualTo("mods/example.jar.old");
    assertThat(ModService.nextArchiveName("mods/example.jar.old"))
        .isEqualTo("mods/example.jar.old");
  }
}
