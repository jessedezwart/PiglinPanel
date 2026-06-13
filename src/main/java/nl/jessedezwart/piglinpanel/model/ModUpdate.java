package nl.jessedezwart.piglinpanel.model;

import java.net.URI;
import java.time.Instant;

public record ModUpdate(
    String projectId,
    String projectName,
    String currentVersionId,
    String latestVersionId,
    String latestVersionName,
    Instant lastUpdatedAt,
    URI downloadUrl,
    String fileName) {
  public boolean available() {
    return latestVersionId != null
        && !latestVersionId.equals(currentVersionId)
        && downloadUrl != null;
  }
}
