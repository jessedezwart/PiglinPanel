package nl.jessedezwart.piglinpanel.model;

import java.net.URI;

public record ModUpdate(
    String projectId,
    String projectName,
    String currentVersionId,
    String latestVersionId,
    String latestVersionName,
    URI downloadUrl,
    String fileName) {
  public boolean available() {
    return latestVersionId != null
        && !latestVersionId.equals(currentVersionId)
        && downloadUrl != null;
  }
}
