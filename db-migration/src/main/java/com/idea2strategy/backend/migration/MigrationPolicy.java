package com.idea2strategy.backend.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class MigrationPolicy {
    public static final String BASELINE_FILE = "V1__initial_schema.sql";
    public static final String BASELINE_SHA256 = "333a39cb2fe1bb01e93b487e29a50c27ec9b9937cbef952974f385a9af9707ce";

    private static final Pattern TIMESTAMP_MIGRATION = Pattern.compile(
            "^V(?<version>\\d{14})__(?<owner>[a-z]+)_(?<description>[a-z0-9]+(?:_[a-z0-9]+)*)\\.sql$");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);

    private MigrationPolicy() {}

    public static MigrationPlan verifyDirectory(Path directory) {
        try (var entries = Files.list(directory)) {
            var migrationFiles = entries
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(".sql"))
                    .toList();
            var plan = verifyNames(migrationFiles);
            verifyBaselineChecksum(directory.resolve(BASELINE_FILE));
            return plan;
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to verify migration directory: " + directory, exception);
        }
    }

    public static MigrationPlan verifyNames(List<String> fileNames) {
        if (fileNames.stream().filter(BASELINE_FILE::equals).count() != 1) {
            throw new IllegalArgumentException("Exactly one immutable baseline is required: " + BASELINE_FILE);
        }

        var versions = new HashSet<String>();
        var ordered = new ArrayList<OwnedMigration>();
        for (var fileName : fileNames) {
            if (BASELINE_FILE.equals(fileName)) {
                continue;
            }
            var matcher = TIMESTAMP_MIGRATION.matcher(fileName);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Post-baseline migration must use VyyyyMMddHHmmss__owner_description.sql: " + fileName);
            }

            var version = matcher.group("version");
            try {
                LocalDateTime.parse(version, TIMESTAMP);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("Invalid UTC migration timestamp: " + version, exception);
            }
            MigrationOwner.fromKey(matcher.group("owner"));
            if (!versions.add(version)) {
                throw new IllegalArgumentException("Duplicate migration timestamp: " + version);
            }
            ordered.add(new OwnedMigration(version, fileName));
        }

        ordered.sort(Comparator.comparing(OwnedMigration::version));
        var result = new ArrayList<String>();
        result.add(BASELINE_FILE);
        ordered.stream().map(OwnedMigration::fileName).forEach(result::add);
        return new MigrationPlan(result);
    }

    private static void verifyBaselineChecksum(Path baseline) throws IOException {
        var normalized = Files.readString(baseline, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .getBytes(StandardCharsets.UTF_8);
        var actual = sha256(normalized);
        if (!BASELINE_SHA256.equals(actual)) {
            throw new IllegalArgumentException(
                    "Applied baseline must not change; expected " + BASELINE_SHA256 + ", actual " + actual);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record OwnedMigration(String version, String fileName) {}
}
