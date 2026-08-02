package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class AccountPreferencesMigrationIntegrationTest {

    private static final UUID ACCOUNT_WITH_PREFERENCES =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_WITHOUT_PREFERENCES =
            UUID.fromString("10000000-0000-4000-8000-000000000002");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void addsThemePreferenceAndRepairsExistingAccountsWithoutPreferences() throws Exception {
        var centralDirectory = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        var baselineDirectory = Files.createDirectories(temporaryDirectory.resolve("baseline"));
        Files.copy(
                centralDirectory.resolve(MigrationPolicy.BASELINE_FILE),
                baselineDirectory.resolve(MigrationPolicy.BASELINE_FILE));

        flyway(baselineDirectory).migrate();
        seedExistingAccounts();

        var bundle = CanonicalMigrationBundleAssembler.assemble(
                centralDirectory, List.of(), temporaryDirectory.resolve("bundle"));
        var migrationResult = flyway(bundle.directory()).migrate();

        assertTrue(migrationResult.migrationsExecuted > 0);
        assertEquals(List.of("LIGHT", "DARK", "SYSTEM"), themePreferenceValues());
        assertThemeColumnContract();
        assertPreferences(ACCOUNT_WITH_PREFERENCES, "en", "Asia/Seoul", "SYSTEM");
        assertPreferences(ACCOUNT_WITHOUT_PREFERENCES, "ko", "America/New_York", "SYSTEM");
        assertEquals(0, accountsWithoutPreferences());
        assertEquals(0, flyway(bundle.directory()).migrate().migrationsExecuted);
    }

    private void seedExistingAccounts() throws Exception {
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.prepareStatement(
                    "insert into identity.accounts "
                            + "(id, lifecycle_status, status_changed_at, created_at) "
                            + "values (?, 'ACTIVE', now(), now()), (?, 'ACTIVE', now(), now())")) {
                statement.setObject(1, ACCOUNT_WITH_PREFERENCES);
                statement.setObject(2, ACCOUNT_WITHOUT_PREFERENCES);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement(
                    "insert into identity.account_preferences "
                            + "(account_id, language_code, timezone_name, created_at, updated_at) "
                            + "values (?, 'en', 'Asia/Seoul', now(), now())")) {
                statement.setObject(1, ACCOUNT_WITH_PREFERENCES);
                statement.executeUpdate();
            }
        }
    }

    private List<String> themePreferenceValues() throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "select enum_value.enumlabel "
                                + "from pg_type enum_type "
                                + "join pg_namespace namespace on namespace.oid = enum_type.typnamespace "
                                + "join pg_enum enum_value on enum_value.enumtypid = enum_type.oid "
                                + "where namespace.nspname = 'identity' "
                                + "and enum_type.typname = 'theme_preference' "
                                + "order by enum_value.enumsortorder");
                var result = statement.executeQuery()) {
            var values = new java.util.ArrayList<String>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private void assertThemeColumnContract() throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "select is_nullable, column_default, udt_schema, udt_name "
                                + "from information_schema.columns "
                                + "where table_schema = 'identity' "
                                + "and table_name = 'account_preferences' "
                                + "and column_name = 'theme_preference'");
                var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals("NO", result.getString("is_nullable"));
            assertTrue(result.getString("column_default").contains("SYSTEM"));
            assertEquals("identity", result.getString("udt_schema"));
            assertEquals("theme_preference", result.getString("udt_name"));
        }
    }

    private void assertPreferences(UUID accountId, String language, String timezone, String theme) throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "select language_code, timezone_name, theme_preference::text "
                                + "from identity.account_preferences where account_id = ?")) {
            statement.setObject(1, accountId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(language, result.getString(1));
                assertEquals(timezone, result.getString(2));
                assertEquals(theme, result.getString(3));
            }
        }
    }

    private int accountsWithoutPreferences() throws Exception {
        try (var connection = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.prepareStatement(
                        "select count(*) "
                                + "from identity.accounts account "
                                + "left join identity.account_preferences preferences "
                                + "on preferences.account_id = account.id "
                                + "where preferences.account_id is null");
                var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        }
    }

    private Flyway flyway(Path directory) {
        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + directory)
                .load();
    }
}
