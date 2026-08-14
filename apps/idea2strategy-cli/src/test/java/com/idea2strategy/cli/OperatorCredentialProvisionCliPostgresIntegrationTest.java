package com.idea2strategy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.operatortrust.OperatorTotp;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class OperatorCredentialProvisionCliPostgresIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Test
    void provisionsOnlyAnExplicitExistingOperatorAndDoesNotPrintSecrets() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        String operatorId = "b22c0000-0000-4000-8000-000000000001";
        jdbc.update("insert into operations.operator_accounts (id, status, created_at) values (?::uuid, 'ACTIVE', now())", operatorId);
        byte[] seed = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        String encodedSeed = Base64.getEncoder().encodeToString(seed);
        String input = "provisioned-correct-horse-battery-staple\n" + encodedSeed + "\n"
                + new OperatorTotp().currentCode(seed, Instant.now()) + "\n";
        String totpKey = Base64.getEncoder().encodeToString(new byte[32]);
        var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
        int exit = Idea2StrategyCli.run(new String[] {"operator", "credential-provision",
                        "--operator-id", operatorId, "--login-name", "Second.Admin"},
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, error,
                Map.of("I2S_BOOTSTRAP_JDBC_URL", POSTGRES.getJdbcUrl(),
                        "I2S_BOOTSTRAP_DB_USER", POSTGRES.getUsername(),
                        "I2S_BOOTSTRAP_DB_PASSWORD", POSTGRES.getPassword(),
                        "I2S_OPERATOR_CREDENTIAL_DB_ROLE", POSTGRES.getUsername(),
                        "I2S_OPERATOR_CREDENTIAL_ACTOR_ID", "b22c0000-0000-4000-8000-000000000002",
                        "OPERATOR_AUTH_TOTP_KEY_VERSION", "1", "OPERATOR_AUTH_TOTP_KEY", totpKey));

        assertThat(exit).isZero();
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("OPERATOR_CREDENTIAL_PROVISION", "\"credentialVersion\":1")
                .doesNotContain("provisioned-correct-horse", encodedSeed);
        assertThat(jdbc.queryForObject("select login_name from operations.operator_login_credentials "
                + "where operator_account_id = ?::uuid", String.class, operatorId)).isEqualTo("second.admin");
    }
}
