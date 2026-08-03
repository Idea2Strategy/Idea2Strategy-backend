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
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
                 "externalIdentityKeyVersion":1,"externalIdentityKeyHmac":"%s",
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
                """.formatted("c".repeat(64), POSTGRES.getUsername(), "d".repeat(64));
        Path file = temporary.resolve("reviewed.json");
        Files.writeString(file, manifest);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
        var output = new ByteArrayOutputStream(); var error = new ByteArrayOutputStream();
        int exit = Idea2StrategyCli.run(new String[] {"operator", "bootstrap", "--manifest", file.toString(),
                        "--expected-sha256", hash}, new ByteArrayInputStream(new byte[0]), output, error,
                Map.of("I2S_BOOTSTRAP_JDBC_URL", POSTGRES.getJdbcUrl(),
                        "I2S_BOOTSTRAP_DB_USER", POSTGRES.getUsername(),
                        "I2S_BOOTSTRAP_DB_PASSWORD", POSTGRES.getPassword()));
        String response = output.toString(StandardCharsets.UTF_8);
        assertThat(exit).isZero();
        assertThat(error.toString(StandardCharsets.UTF_8)).isEmpty();
        assertThat(response).contains("\"ok\":true", "cli-exact-bootstrap", hash)
                .doesNotContain(POSTGRES.getPassword(), POSTGRES.getJdbcUrl(), "approved-cli-exact-stack",
                        "d".repeat(64));
    }
}
