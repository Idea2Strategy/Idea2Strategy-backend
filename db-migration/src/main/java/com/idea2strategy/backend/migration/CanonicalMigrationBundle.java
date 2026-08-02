package com.idea2strategy.backend.migration;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record CanonicalMigrationBundle(Path directory, List<String> orderedFileNames, String sha256) {
    public static final String MANIFEST_FILE = "migration-bundle.manifest";
    public static final String DIGEST_FILE = "migration-bundle.sha256";

    public CanonicalMigrationBundle {
        Objects.requireNonNull(directory, "directory");
        orderedFileNames = List.copyOf(orderedFileNames);
        Objects.requireNonNull(sha256, "sha256");
    }
}
