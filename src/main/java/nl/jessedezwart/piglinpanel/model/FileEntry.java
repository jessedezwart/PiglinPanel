package nl.jessedezwart.piglinpanel.model;

import java.time.Instant;

public record FileEntry(
    String path, String name, boolean directory, long size, Instant modifiedAt) {}
