package com.idea2strategy.backend.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class CanonicalMigrationBundleAssembler {
    private CanonicalMigrationBundleAssembler() {}

    public static CanonicalMigrationBundle assemble(
            Path centralMigrationDirectory,
            List<MigrationContribution> contributions,
            Path outputDirectory) {
        var central = normalizedDirectory(centralMigrationDirectory, "central migration");
        var output = outputDirectory.toAbsolutePath().normalize();
        requireSeparateOutput(output, central, contributions);

        MigrationPolicy.verifyDirectory(central);
        var sources = new HashMap<String, Path>();
        collectCentralSources(central, sources);
        for (var contribution : List.copyOf(contributions)) {
            collectContributionSources(contribution, sources);
        }

        var plan = MigrationPolicy.verifyNames(new ArrayList<>(sources.keySet()));
        verifyEmptyOutput(output);
        try {
            Files.createDirectories(output);
            var manifest = new StringBuilder("idea2strategy-flyway-bundle-v1\n");
            var migrationSql = new ArrayList<String>();
            for (var fileName : plan.orderedFileNames()) {
                var source = sources.get(fileName);
                var bytes = Files.readAllBytes(source);
                Files.write(output.resolve(fileName), bytes);
                migrationSql.add(new String(bytes, StandardCharsets.UTF_8));
                manifest.append(fileName).append('\t').append(sha256(bytes)).append('\n');
            }
            var runtimeGrants = DatabaseAccessPolicy.runtimeGrantSql(migrationSql)
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(output.resolve(DatabaseAccessPolicy.RUNTIME_GRANTS_FILE), runtimeGrants);
            manifest.append(DatabaseAccessPolicy.RUNTIME_GRANTS_FILE)
                    .append('\t').append(sha256(runtimeGrants)).append('\n');
            var manifestBytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            var bundleDigest = sha256(manifestBytes);
            Files.write(output.resolve(CanonicalMigrationBundle.MANIFEST_FILE), manifestBytes);
            Files.writeString(
                    output.resolve(CanonicalMigrationBundle.DIGEST_FILE),
                    bundleDigest + "\n",
                    StandardCharsets.UTF_8);
            var orderedFileNames = new ArrayList<>(plan.orderedFileNames());
            orderedFileNames.add(DatabaseAccessPolicy.RUNTIME_GRANTS_FILE);
            return new CanonicalMigrationBundle(output, orderedFileNames, bundleDigest);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to assemble canonical migration bundle", exception);
        }
    }

    private static void collectCentralSources(Path central, Map<String, Path> sources) {
        for (var source : sqlFiles(central)) {
            var fileName = source.getFileName().toString();
            if (!MigrationPolicy.BASELINE_FILE.equals(fileName)) {
                verifyMigration(MigrationPolicy.ownerFromFileName(fileName), source);
            }
            putUnique(sources, fileName, source);
        }
    }

    private static void collectContributionSources(
            MigrationContribution contribution, Map<String, Path> sources) {
        var directory = normalizedDirectory(contribution.directory(), contribution.owner().key() + " contribution");
        for (var source : sqlFiles(directory)) {
            var fileName = source.getFileName().toString();
            if (MigrationPolicy.BASELINE_FILE.equals(fileName)) {
                throw new IllegalArgumentException("Owner contributions must not contain the immutable baseline");
            }
            if (!fileName.matches(contribution.filenameRegex())) {
                throw new IllegalArgumentException(
                        "Migration filename violates contribution contract: " + fileName);
            }
            var fileOwner = MigrationPolicy.ownerFromFileName(fileName);
            if (fileOwner != contribution.owner()) {
                throw new IllegalArgumentException(
                        "Contribution declared as " + contribution.owner().key()
                                + " contains migration owned by " + fileOwner.key() + ": " + fileName);
            }
            verifyMigration(fileOwner, contribution.schemas(), source);
            putUnique(sources, fileName, source);
        }
    }

    private static void verifyMigration(MigrationOwner owner, Path source) {
        verifyMigration(owner, null, source);
    }

    private static void verifyMigration(MigrationOwner owner, java.util.Set<String> schemas, Path source) {
        try {
            var sql = Files.readString(source, StandardCharsets.UTF_8);
            DatabaseAccessPolicy.verifyNoApplicationDdlGrants(sql);
            DatabaseAccessPolicy.verifyMigrationOwnership(owner, schemas, sql);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to verify migration: " + source, exception);
        }
    }

    private static void putUnique(Map<String, Path> sources, String fileName, Path source) {
        var previous = sources.putIfAbsent(fileName, source);
        if (previous != null) {
            throw new IllegalArgumentException(
                    "Migration file occurs in more than one input: " + fileName);
        }
    }

    private static List<Path> sqlFiles(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted((left, right) -> left.getFileName().toString()
                            .compareTo(right.getFileName().toString()))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to list migration directory: " + directory, exception);
        }
    }

    private static Path normalizedDirectory(Path directory, String label) {
        var normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(label + " directory does not exist: " + normalized);
        }
        return normalized;
    }

    private static void requireSeparateOutput(
            Path output, Path central, List<MigrationContribution> contributions) {
        if (output.startsWith(central) || central.startsWith(output)) {
            throw new IllegalArgumentException("Bundle output must be separate from migration inputs");
        }
        for (var contribution : contributions) {
            var input = contribution.directory().toAbsolutePath().normalize();
            if (output.startsWith(input) || input.startsWith(output)) {
                throw new IllegalArgumentException("Bundle output must be separate from migration inputs");
            }
        }
    }

    private static void verifyEmptyOutput(Path output) {
        if (!Files.exists(output)) {
            return;
        }
        if (!Files.isDirectory(output)) {
            throw new IllegalArgumentException("Bundle output is not a directory: " + output);
        }
        try (var entries = Files.list(output)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Bundle output must be empty: " + output);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to inspect bundle output: " + output, exception);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
