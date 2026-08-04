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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @Test
    void concurrentAbsentIdempotencyRowConvergesToOneAuditAndOutbox() throws Exception {
        UUID candidate = id(21);
        UUID correlation = id(22);
        UUID delivery = id(23);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> executeConcurrent(start, candidate, correlation, delivery));
            var second = executor.submit(() -> executeConcurrent(start, candidate, correlation, delivery));
            start.countDown();
            AdminMcpExecutionResult firstResult = first.get(20, TimeUnit.SECONDS);
            AdminMcpExecutionResult secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResult.status()).isEqualTo(firstResult.status());
            assertThat(secondResult.code()).isEqualTo(firstResult.code());
            assertThat(secondResult.response()).containsEntry("aggregateSequence", 1);
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "select count(*) from operations.audit_events where idempotency_key = ?",
                Integer.class, "approval-concurrent-1")).isOne();
        assertThat(jdbc.queryForObject(
                "select count(*) from operations.outbox_messages where aggregate_id = ?",
                Integer.class, candidate)).isOne();
    }

    private static AdminMcpExecutionResult executeConcurrent(
            CountDownLatch start, UUID candidate, UUID correlation, UUID delivery) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var executions = new AdminMcpJdbcConfiguration.JdbcExecutions(
                jdbc, new ObjectMapper().findAndRegisterModules());
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return transaction.execute(ignored -> executions.executeIdempotently(
                invocation(candidate, correlation, "approval-concurrent-1"),
                NOW,
                () -> appliedResult(candidate, correlation, delivery)));
    }

    private static AdminMcpInvocation invocation() {
        return invocation(CANDIDATE, CORRELATION, "approval-postgres-1");
    }

    private static AdminMcpInvocation invocation(
            UUID candidate, UUID correlation, String idempotencyKey) {
        return new AdminMcpInvocation(
                new OperatorRequestContext(ACTOR, true, true), "mcp-v1",
                "corporate_action_candidate.approve", "schema-v1", candidate.toString(),
                "a".repeat(64), Map.of("candidateId", candidate.toString()), correlation,
                idempotencyKey, "c".repeat(64));
    }

    private static AdminMcpExecutionResult appliedResult() {
        return appliedResult(CANDIDATE, CORRELATION, DELIVERY);
    }

    private static AdminMcpExecutionResult appliedResult(
            UUID candidate, UUID correlation, UUID delivery) {
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("candidateId", candidate), Map.entry("decision", "APPROVE"),
                Map.entry("decidedContentHash", "a".repeat(64)),
                Map.entry("evidenceBindings", List.of("b".repeat(64))),
                Map.entry("actorId", ACTOR), Map.entry("auditId", correlation),
                Map.entry("permissionId", id(12)), Map.entry("requestSchemaVersion", "schema-v1"),
                Map.entry("decidedAt", NOW), Map.entry("deliveryId", delivery),
                Map.entry("aggregateSequence", 1));
        return new AdminMcpExecutionResult(
                AdminMcpExecutionResult.Status.APPLIED, "CORPORATE_ACTION_DECISION_ACCEPTED", payload,
                new AdminMcpExecutionResult.AuditEvidence(
                        ACTOR, "mcp-v1", null, "corporate_action_candidate.approve",
                        "CORPORATE_ACTION", candidate.toString(), "a".repeat(64),
                        "CORPORATE_ACTION_DECISION_ACCEPTED", correlation, NOW,
                        Map.of("status", "REVIEW_REQUIRED"), Map.of("status", "APPROVED")));
    }

    private static UUID id(long suffix) {
        return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
