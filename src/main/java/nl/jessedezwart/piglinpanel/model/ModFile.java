package nl.jessedezwart.piglinpanel.model;

public record ModFile(
    String folderId,
    String folderPath,
    String relativePath,
    String fileName,
    long size,
    boolean archived,
    String sha1,
    String sha512,
    ModUpdate update) {}
