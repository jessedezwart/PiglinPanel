package nl.jessedezwart.piglinpanel.filesystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import nl.jessedezwart.piglinpanel.model.ServerProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalServerFileSystemTests {
  @TempDir Path tempDir;

  @Test
  void readsAndListsInsideRoot() throws Exception {
    Files.writeString(tempDir.resolve("server.properties"), "motd=Piglinpanel");
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());

    LocalServerFileSystem fs = new LocalServerFileSystem(profile);

    assertThat(fs.list("")).extracting("name").contains("server.properties");
    assertThat(new String(fs.readBytes("server.properties"))).isEqualTo("motd=Piglinpanel");
  }

  @Test
  void rejectsPathsOutsideRoot() {
    ServerProfile profile = new ServerProfile();
    profile.setRootPath(tempDir.toString());
    LocalServerFileSystem fs = new LocalServerFileSystem(profile);

    assertThatThrownBy(() -> fs.readBytes("../outside.txt"))
        .hasMessageContaining("escapes server root");
  }
}
