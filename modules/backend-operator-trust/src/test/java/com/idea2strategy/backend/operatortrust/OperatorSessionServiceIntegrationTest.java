package com.idea2strategy.backend.operatortrust;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class OperatorSessionServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");
    private static final UUID OPERATOR = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    JdbcTemplate jdbc;
    OperatorPasswordHasher hasher;
    OperatorTotp totp;
    byte[] seed;
    OperatorSessionService service;

    @BeforeEach
    void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Boolean migrated = jdbc.queryForObject("select to_regclass('public.flyway_schema_history') is not null", Boolean.class);
        if (!Boolean.TRUE.equals(migrated)) {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        } else {
            jdbc.execute("truncate operations.operator_sessions, operations.operator_login_credentials, operations.operator_accounts, operations.audit_events cascade");
        }
        hasher = new OperatorPasswordHasher(new OperatorPasswordHasher.Parameters(8192, 1, 1, 16, 32, 1));
        totp = new OperatorTotp();
        seed = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var aes = new SecretKeySpec(new byte[32], "AES");
        var cipher = new OperatorSecretCipher(1, Map.of(1, aes));
        byte[] hmac = new byte[32];
        hmac[0] = 9;
        var protector = new OperatorTokenProtector(1, Map.of(1, hmac));
        service = new OperatorSessionService(jdbc, new DataSourceTransactionManager(dataSource), hasher, totp,
                cipher, protector, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(15), Duration.ofHours(8));

        var encrypted = cipher.encrypt(OPERATOR, 1, seed);
        jdbc.update("insert into operations.operator_accounts (id,status,created_at) values (?, 'ACTIVE', ?)", OPERATOR, java.sql.Timestamp.from(NOW));
        jdbc.update("""
                insert into operations.operator_login_credentials
                  (operator_account_id, login_name, password_hash, password_parameters, password_version,
                   credential_version, totp_ciphertext, totp_nonce, totp_key_version, totp_enrolled_at,
                   failed_attempt_count, password_changed_at, created_at, updated_at)
                values (?, 'admin', ?, '{}'::jsonb, 1, 1, ?, ?, 1, ?, 0, ?, ?, ?)
                """, OPERATOR, hasher.hash("password-strong-enough".toCharArray()), encrypted.ciphertext(),
                encrypted.nonce(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
    }

    @Test
    void loginIssuesAnOpaqueSessionThatResolvesTheStableOperator() {
        String code = totp.code(seed, NOW.getEpochSecond() / 30);
        var issued = service.login(" ADMIN ", "password-strong-enough".toCharArray(), code, "127.0.0.1");

        var principal = service.authenticate(issued.rawSessionToken());

        assertEquals(OPERATOR, principal.operatorId());
        assertEquals(NOW, principal.mfaVerifiedAt());
        assertTrue(service.csrfMatches(issued.rawSessionToken(), issued.rawCsrfToken()));
        assertEquals(1, jdbc.queryForObject("select count(*) from operations.operator_sessions", Integer.class));
    }

    @Test
    void wrongCredentialsUseOneGenericRejectionAndDoNotCreateASession() {
        var failure = assertThrows(OperatorAuthenticationRejectedException.class,
                () -> service.login("admin", "wrong".toCharArray(), "000000", "127.0.0.1"));

        assertEquals("OPERATOR_AUTHENTICATION_REJECTED", failure.code());
        assertEquals(0, jdbc.queryForObject("select count(*) from operations.operator_sessions", Integer.class));
    }

    @Test
    void credentialVersionChangeImmediatelyRevokesAnExistingSession() {
        String code = totp.code(seed, NOW.getEpochSecond() / 30);
        var issued = service.login("admin", "password-strong-enough".toCharArray(), code, "127.0.0.1");
        jdbc.update("update operations.operator_login_credentials set credential_version=2 where operator_account_id=?", OPERATOR);

        assertThrows(OperatorAuthenticationRejectedException.class,
                () -> service.authenticate(issued.rawSessionToken()));
    }
}
