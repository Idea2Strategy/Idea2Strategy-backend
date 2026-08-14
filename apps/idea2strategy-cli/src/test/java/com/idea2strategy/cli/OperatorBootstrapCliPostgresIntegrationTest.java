package com.idea2strategy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.time.Instant;
import java.util.Base64;
import com.idea2strategy.backend.operatortrust.OperatorTotp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Testcontainers(disabledWithoutDocker = true)
class OperatorBootstrapCliPostgresIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    @TempDir Path temporary;

    @Test
    void productionRunnerAcceptsOnlyReviewedFileHashAndEnvironmentDatasource() throws Exception {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        String manifest = """
                {"bootstrapKey":"cli-exact-bootstrap","catalogVersion":"cli-catalog-v1",
                 "catalogContentHash":"%s","expectedDatabaseRole":"%s",
                 "loginName":"admin",
                 "operatorAccountId":"a22c0000-0000-4000-8000-000000000001",
                 "operatorRoleAssignmentId":"a22c0000-0000-4000-8000-000000000002",
                 "initialRoleId":"a22c0000-0000-4000-8000-000000000003",
                 "deploymentActorId":"a22c0000-0000-4000-8000-000000000004",
                 "grantProvenance":"approved-cli-exact-stack",
                 "correlationId":"a22c0000-0000-4000-8000-000000000005",
                 "auditEventId":"a22c0000-0000-4000-8000-000000000006",
                 "roles":[{"id":"a22c0000-0000-4000-8000-000000000003","code":"CLI_ROOT","hierarchyRank":100}],
                 "permissions":[
                   {"id":"e3000000-0000-4000-8000-000000000001","code":"COMPETITION_ROOM_READ","description":"Read operator-safe official competition room state and result provenance","sensitivity":"SENSITIVE"},
                   {"id":"e3000000-0000-4000-8000-000000000002","code":"COMPETITION_ROOM_MANAGE","description":"Cancel or invalidate official competition rooms through audited commands","sensitivity":"HIGH"},
                   {"id":"a22c0000-0000-4000-8000-000000000007","code":"CLI_READ","description":"CLI exact-stack read","sensitivity":"HIGH"}],
                 "rolePermissions":[{"roleId":"a22c0000-0000-4000-8000-000000000003","permissionId":"a22c0000-0000-4000-8000-000000000007","delegable":false}]}
                """.formatted("c".repeat(64), POSTGRES.getUsername());
        Path file = temporary.resolve("reviewed.json");
        Files.writeString(file, manifest);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
        var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
        byte[] seed = "01234567890123456789".getBytes(StandardCharsets.US_ASCII);
        String input = "correct-horse-battery-staple\n" + Base64.getEncoder().encodeToString(seed)
                + "\n" + new OperatorTotp().currentCode(seed, Instant.now()) + "\n";
        String totpKey = Base64.getEncoder().encodeToString(new byte[32]);
        int exit = Idea2StrategyCli.run(new String[] {"operator", "bootstrap", "--manifest", file.toString(),
                        "--expected-sha256", hash}, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, error,
                Map.of("I2S_BOOTSTRAP_JDBC_URL", POSTGRES.getJdbcUrl(),
                        "I2S_BOOTSTRAP_DB_USER", POSTGRES.getUsername(),
                        "I2S_BOOTSTRAP_DB_PASSWORD", POSTGRES.getPassword(),
                        "OPERATOR_AUTH_TOTP_KEY_VERSION", "1", "OPERATOR_AUTH_TOTP_KEY", totpKey));
        String response = output.toString(StandardCharsets.UTF_8);
        assertThat(exit).isZero();
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(response).contains("\"ok\":true", "cli-exact-bootstrap", hash)
                .doesNotContain(POSTGRES.getPassword(), POSTGRES.getJdbcUrl(), "approved-cli-exact-stack",
                        "d".repeat(64));

        String resetInput = "new-correct-horse-battery-staple\n" + Base64.getEncoder().encodeToString(seed)
                + "\n" + new OperatorTotp().currentCode(seed, Instant.now()) + "\n";
        output.reset(); error.reset();
        exit = Idea2StrategyCli.run(new String[] {"operator", "credential-reset",
                        "--operator-id", "a22c0000-0000-4000-8000-000000000001"},
                new ByteArrayInputStream(resetInput.getBytes(StandardCharsets.UTF_8)), output, error,
                Map.of("I2S_BOOTSTRAP_JDBC_URL", POSTGRES.getJdbcUrl(),
                        "I2S_BOOTSTRAP_DB_USER", POSTGRES.getUsername(),
                        "I2S_BOOTSTRAP_DB_PASSWORD", POSTGRES.getPassword(),
                        "I2S_OPERATOR_CREDENTIAL_DB_ROLE", POSTGRES.getUsername(),
                        "I2S_OPERATOR_CREDENTIAL_ACTOR_ID", "a22c0000-0000-4000-8000-000000000004",
                        "OPERATOR_AUTH_TOTP_KEY_VERSION", "1", "OPERATOR_AUTH_TOTP_KEY", totpKey));
        assertThat(exit).isZero();
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("OPERATOR_CREDENTIAL_RESET", "\"credentialVersion\":2")
                .doesNotContain("new-correct-horse", Base64.getEncoder().encodeToString(seed));
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(jdbc.queryForObject("select credential_version from operations.operator_login_credentials "
                + "where operator_account_id = 'a22c0000-0000-4000-8000-000000000001'", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("select count(*) from operations.audit_events "
                + "where action_type = 'OPERATOR_CREDENTIAL_RESET'", Integer.class)).isEqualTo(1);
    }
}
