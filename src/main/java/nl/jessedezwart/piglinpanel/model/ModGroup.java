package nl.jessedezwart.piglinpanel.model;

import java.util.List;

/**
 * A mod and all of its on-disk versions grouped together. The {@code active} file is the enabled
 * {@code .jar}; {@code archived} holds the disabled ({@code .jar.disabled}) versions that can be
 * re-activated. {@code update} is the suggested Modrinth update for the active version, if any.
 */
public record ModGroup(
    String key,
    String displayName,
    String iconUrl,
    String folderName,
    ModFile active,
    List<ModFile> archived,
    ModUpdate update) {

  public boolean hasArchived() {
    return archived != null && !archived.isEmpty();
  }
}
