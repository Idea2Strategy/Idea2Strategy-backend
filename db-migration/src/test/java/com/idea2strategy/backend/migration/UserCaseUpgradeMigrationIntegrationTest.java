package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class UserCaseUpgradeMigrationIntegrationTest {
    private static final String A19 = "V20260802231300__backend_user_case_contract.sql";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @TempDir Path temporaryDirectory;

    @Test
    void upgradesAValidLegacyCaseIntoOneTypedHead() throws Exception {
        Path central = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        Path beforeA19 = Files.createDirectories(temporaryDirectory.resolve("before-a19"));
        try (var files = Files.list(central)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                if (!A19.equals(source.getFileName().toString())) {
                    Files.copy(source, beforeA19.resolve(source.getFileName()));
                }
            }
        }
        migrate(beforeA19);
        UUID accountId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var statement = connection.prepareStatement(
                    "insert into identity.accounts (id, lifecycle_status) values (?, 'ACTIVE')")) {
                statement.setObject(1, accountId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    insert into operations.cases
                        (id, account_id, case_type, status, subject)
                    values (?, ?, 'INQUIRY', 'OPEN', 'legacy')
                    """)) {
                statement.setObject(1, caseId);
                statement.setObject(2, accountId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    insert into operations.case_events
                        (id, case_id, event_sequence, actor_type, actor_id, event_type, payload_document)
                    values (?, ?, 1, 'ACCOUNT', ?, 'SUBMITTED', '{}'::jsonb)
                    """)) {
                statement.setObject(1, eventId);
                statement.setObject(2, caseId);
                statement.setObject(3, accountId);
                statement.executeUpdate();
            }
        }

        migrate(central);

        try (var connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.prepareStatement("""
                     select case_type::text, status::text, case_version,
                            current_event_sequence, last_case_event_id
                     from operations.cases where id = ?
                     """)) {
            statement.setObject(1, caseId);
            try (var result = statement.executeQuery()) {
                result.next();
                assertEquals("INQUIRY", result.getString(1));
                assertEquals("OPEN", result.getString(2));
                assertEquals(1L, result.getLong(3));
                assertEquals(1, result.getInt(4));
                assertEquals(eventId, result.getObject(5, UUID.class));
            }
        }
    }

    private void migrate(Path location) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + location.toAbsolutePath())
                .load()
                .migrate();
    }
}
