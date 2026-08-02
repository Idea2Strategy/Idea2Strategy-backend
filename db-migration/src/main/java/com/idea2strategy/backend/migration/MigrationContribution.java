package com.idea2strategy.backend.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public record MigrationContribution(
        MigrationOwner owner, Set<String> schemas, Path directory, String filenameRegex) {
    public static final String CONTRACT_FILE = "contribution.properties";

    public MigrationContribution {
        Objects.requireNonNull(owner, "owner");
        schemas = Set.copyOf(schemas);
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("Contribution must declare at least one schema");
        }
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(filenameRegex, "filenameRegex");
        try {
            Pattern.compile(filenameRegex);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("Invalid contribution filename.regex", exception);
        }
    }

    public MigrationContribution(MigrationOwner owner, Set<String> schemas, Path directory) {
        this(
                owner,
                schemas,
                directory,
                "^V\\d{14}__" + owner.key() + "_[a-z0-9]+(?:_[a-z0-9]+)*\\.sql$");
    }

    public static MigrationContribution load(Path contributionRoot) {
        var root = contributionRoot.toAbsolutePath().normalize();
        var contract = root.resolve(CONTRACT_FILE);
        var properties = new Properties();
        try (var input = Files.newInputStream(contract)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read contribution contract: " + contract, exception);
        }

        if (!"1".equals(required(properties, "contract.version"))) {
            throw new IllegalArgumentException("Unsupported contribution contract version");
        }
        var owner = MigrationOwner.fromKey(required(properties, "owner"));
        var schemas = Arrays.stream(required(properties, "schemas").split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        var migrations = requiredDirectory(root, required(properties, "migrations.directory"));
        requiredDirectory(root, required(properties, "fixtures.directory"));
        var filenameRegex = required(properties, "filename.regex");
        if (!"false".equalsIgnoreCase(required(properties, "runtime.flyway.enabled"))) {
            throw new IllegalArgumentException("Contribution runtime.flyway.enabled must be false");
        }
        return new MigrationContribution(owner, schemas, migrations, filenameRegex);
    }

    private static String required(Properties properties, String key) {
        var value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing contribution property: " + key);
        }
        return value.trim();
    }

    private static Path resolveInside(Path root, String relativePath) {
        var relative = Path.of(relativePath);
        var path = root.resolve(relative).normalize();
        if (relative.isAbsolute() || !path.startsWith(root)) {
            throw new IllegalArgumentException("Contribution path must stay inside its root: " + relativePath);
        }
        return path;
    }

    private static Path requiredDirectory(Path root, String relativePath) {
        var path = resolveInside(root, relativePath);
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Contribution directory does not exist: " + path);
        }
        return path;
    }
}
