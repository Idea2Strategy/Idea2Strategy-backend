package com.idea2strategy.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionResult;
import com.idea2strategy.backend.application.adminmcp.AdminMcpInvocation;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class AdminMcpJdbcPostgresIntegrationTest {
    private static final UUID CANDIDATE = id(1);
    private static final UUID ACTOR = id(2);
    private static final UUID CORRELATION = id(3);
    private static final UUID DELIVERY = id(4);
    private static final Instant NOW = Instant.parse("2026-08-04T15:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Test
    void approvalCommitsAuditAndRelayCommandTogether() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var executions = new AdminMcpJdbcConfiguration.JdbcExecutions(
                jdbc, new ObjectMapper().findAndRegisterModules());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transaction.executeWithoutResult(ignored -> executions.executeIdempotently(
                invocation(), NOW, AdminMcpJdbcPostgresIntegrationTest::appliedResult));

        assertThat(jdbc.queryForObject(
                "select count(*) from operations.audit_events where id = ?",
                Integer.class, CORRELATION)).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from operations.outbox_messages where id = ?",
                Integer.class, DELIVERY)).isOne();
        String command = jdbc.queryForObject(
                "select payload_document::text from operations.outbox_messages where id = ?",
                String.class, DELIVERY);
        assertThat(command)
                .contains("APPLY_CORPORATE_ACTION_APPROVAL")
                .contains("decidedContentHash")
                .contains(DELIVERY.toString());
    }

    private static AdminMcpInvocation invocation() {
        return new AdminMcpInvocation(
                new OperatorRequestContext(ACTOR, true, true), "mcp-v1",
                "corporate_action_candidate.approve", "schema-v1", CANDIDATE.toString(),
                "a".repeat(64), Map.of("candidateId", CANDIDATE.toString()), CORRELATION,
                "approval-postgres-1", "c".repeat(64));
    }

    private static AdminMcpExecutionResult appliedResult() {
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("candidateId", CANDIDATE), Map.entry("decision", "APPROVE"),
                Map.entry("decidedContentHash", "a".repeat(64)),
                Map.entry("evidenceBindings", List.of("b".repeat(64))),
                Map.entry("actorId", ACTOR), Map.entry("auditId", CORRELATION),
                Map.entry("permissionId", id(12)), Map.entry("requestSchemaVersion", "schema-v1"),
                Map.entry("decidedAt", NOW), Map.entry("deliveryId", DELIVERY),
                Map.entry("aggregateSequence", 1));
        return new AdminMcpExecutionResult(
                AdminMcpExecutionResult.Status.APPLIED, "CORPORATE_ACTION_DECISION_ACCEPTED", payload,
                new AdminMcpExecutionResult.AuditEvidence(
                        ACTOR, "mcp-v1", null, "corporate_action_candidate.approve",
                        "CORPORATE_ACTION", CANDIDATE.toString(), "a".repeat(64),
                        "CORPORATE_ACTION_DECISION_ACCEPTED", CORRELATION, NOW,
                        Map.of("status", "REVIEW_REQUIRED"), Map.of("status", "APPROVED")));
    }

    private static UUID id(long suffix) {
        return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
