package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class InternalOperatorAuthSchemaIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @TempDir
    Path temporaryDirectory;

    @Test
    void baselineOwnsInternalCredentialsAndOpaqueSessionsWithoutExternalSubjects() throws Exception {
        var central = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("filesystem:" + central)
                .load()
                .migrate();

        try (var connection = POSTGRES.createConnection("")) {
            assertEquals(Set.of("id", "status", "created_at", "disabled_at"),
                    columns(connection, "operator_accounts"));
            assertTrue(columns(connection, "operator_login_credentials").containsAll(Set.of(
                    "operator_account_id", "login_name", "password_hash", "credential_version",
                    "totp_ciphertext", "totp_nonce", "totp_key_version", "last_accepted_totp_step",
                    "failed_attempt_count", "locked_until", "compromised_at")));
            assertTrue(columns(connection, "operator_sessions").containsAll(Set.of(
                    "operator_account_id", "credential_version", "session_token_hmac",
                    "csrf_token_hmac", "csrf_generation", "idle_expires_at", "absolute_expires_at",
                    "mfa_verified_at", "revoked_at", "revocation_reason_code")));
            assertFalse(columns(connection, "operator_accounts").stream()
                    .anyMatch(name -> name.contains("external") || name.contains("oidc")));
        }
    }

    private static Set<String> columns(java.sql.Connection connection, String table) throws Exception {
        var result = new HashSet<String>();
        try (var statement = connection.prepareStatement("""
                select column_name from information_schema.columns
                where table_schema = 'operations' and table_name = ?
                """)) {
            statement.setString(1, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) result.add(rows.getString(1));
            }
        }
        return result;
    }
}
