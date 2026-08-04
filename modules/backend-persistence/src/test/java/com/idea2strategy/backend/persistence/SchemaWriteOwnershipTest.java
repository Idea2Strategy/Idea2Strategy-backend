package com.idea2strategy.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.migration.DatabaseAccessPolicy;
import com.idea2strategy.backend.migration.DatabaseAccessPolicy.Access;
import com.idea2strategy.backend.migration.DatabaseAccessPolicy.ApplicationRole;
import com.idea2strategy.backend.migration.DatabaseAccessPolicy.QualifiedTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every schema-qualified DML statement in this module must target a table the backend role owns.
 *
 * <p>{@link DatabaseAccessPolicy} already declares one write owner per schema, but until this test
 * existed nothing compared the declaration to the SQL actually shipped: the release adapter wrote
 * {@code backtest.runs}, D's schema, for weeks (root #138). The policy is only a policy if
 * something reads it.
 *
 * <p>All raw DML in the backend lives in this module, so scanning this module's sources covers the
 * whole service. The scan joins adjacent Java string literals before matching, because these
 * statements are built by concatenation and a table name can land in the next literal.
 */
class SchemaWriteOwnershipTest {

    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");

    /** Adjacent Java string literals: {@code "insert into " + "bot.bots"}. */
    private static final Pattern LITERAL_JOIN = Pattern.compile("\"\\s*\\+\\s*\"");

    private static final Map<String, Access> DML_KEYWORDS = Map.of(
            "insert into", Access.INSERT,
            "update", Access.UPDATE,
            "delete from", Access.DELETE,
            "truncate table", Access.DELETE,
            "truncate", Access.DELETE);

    /**
     * Writes that are known to break ownership and are tracked elsewhere.
     *
     * <p>This set may only shrink. {@link #recordsOnlyViolationsThatStillExist()} fails if an entry
     * is no longer reachable, so a fixed violation cannot stay parked here, and
     * {@link #writesOnlyToBackendOwnedTables()} fails on any write that is not listed, so a new one
     * cannot be added silently.
     *
     * <p>{@code RoomEvaluationStartJooqAdapter} (E11, room evaluation start) seeds a participating
     * bot's opening capital by inserting F's official double-entry ledger rows and a TRADING-owned
     * {@code bot.bot_events} row directly. F owns {@code trading} and those four {@code bot} tables,
     * and F10 requires the official ledger to be reconstructible from F's own writes. E has to ask F
     * to open the account instead. Tracked on root #181.
     */
    private static final Set<QualifiedTable> KNOWN_VIOLATIONS = Set.of();

    @Test
    void writesOnlyToBackendOwnedTables() throws Exception {
        var offenders = new TreeSet<String>();
        for (var write : scanWrites()) {
            if (KNOWN_VIOLATIONS.contains(write.table())) {
                continue;
            }
            if (!DatabaseAccessPolicy.allows(
                    ApplicationRole.BACKEND, write.access(), write.table().schema(), write.table().table())) {
                offenders.add(write.table().schema() + "." + write.table().table()
                        + " (" + write.access() + " in " + write.source() + ")");
            }
        }

        assertThat(offenders)
                .as("backend must not write tables another service owns; see DatabaseAccessPolicy")
                .isEmpty();
    }

    @Test
    void recordsOnlyViolationsThatStillExist() throws Exception {
        Set<QualifiedTable> written = new LinkedHashSet<>();
        scanWrites().forEach(write -> written.add(write.table()));

        assertThat(written)
                .as("a fixed violation must be removed from KNOWN_VIOLATIONS, not left parked")
                .containsAll(KNOWN_VIOLATIONS);
    }

    @Test
    void scansTheSourcesItClaimsTo() throws Exception {
        assertThat(Files.isDirectory(MAIN_SOURCES))
                .as("expected to run with the module directory as the working directory: %s",
                        MAIN_SOURCES.toAbsolutePath())
                .isTrue();
        assertThat(scanWrites())
                .as("a scan that finds no DML would pass every other assertion vacuously")
                .isNotEmpty();
    }

    private List<Write> scanWrites() throws IOException {
        Set<String> schemas = registeredSchemas();
        var pattern = Pattern.compile(
                "(?i)\\b(" + String.join("|", DML_KEYWORDS.keySet()) + ")\\s+(?:only\\s+)?"
                        + "\"?(" + String.join("|", schemas) + ")\"?\\s*\\.\\s*\"?([a-z_][a-z0-9_]*)\"?");

        var writes = new java.util.ArrayList<Write>();
        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String joined = LITERAL_JOIN
                        .matcher(Files.readString(source, StandardCharsets.UTF_8))
                        .replaceAll("");
                Matcher matcher = pattern.matcher(joined.replaceAll("\\s+", " "));
                while (matcher.find()) {
                    Access access = DML_KEYWORDS.get(matcher.group(1).toLowerCase(java.util.Locale.ROOT));
                    writes.add(new Write(
                            new QualifiedTable(matcher.group(2).toLowerCase(java.util.Locale.ROOT),
                                    matcher.group(3).toLowerCase(java.util.Locale.ROOT)),
                            access,
                            MAIN_SOURCES.relativize(source).toString().replace('\\', '/')));
                }
            }
        }
        return writes;
    }

    /**
     * The schemas the canonical baseline declares, so a schema added later is scanned without this
     * test being edited.
     */
    private Set<String> registeredSchemas() throws IOException {
        String baseline;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V1__initial_schema.sql")) {
            baseline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var schemas = new TreeSet<String>();
        DatabaseAccessPolicy.verifyBaselineOwnership(baseline).tables()
                .forEach(table -> schemas.add(table.schema()));
        return schemas;
    }

    private record Write(QualifiedTable table, Access access, String source) {}
}
