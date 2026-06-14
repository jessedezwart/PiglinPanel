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
    String fileName,
    String iconUrl,
    String clientSide,
    String serverSide,
    long downloads) {
  public boolean available() {
    return latestVersionId != null
        && !latestVersionId.equals(currentVersionId)
        && downloadUrl != null;
  }

  public String formattedDownloads() {
    if (downloads >= 1_000_000) {
      return String.format("%.1fM", downloads / 1_000_000.0);
    }
    if (downloads >= 1_000) {
      return String.format("%.0fK", downloads / 1_000.0);
    }
    return String.valueOf(downloads);
  }
}
