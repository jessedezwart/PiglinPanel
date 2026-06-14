package nl.jessedezwart.piglinpanel.model;

public record ModFile(
    String folderId,
    String folderName,
    String folderPath,
    String relativePath,
    String fileName,
    long size,
    boolean archived,
    String sha1,
    ModUpdate update) {}
