package com.idea2strategy.backend.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionResult;
import com.idea2strategy.backend.application.adminmcp.AdminMcpInvocation;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminMcpJdbcConfigurationTest {
    private static final UUID CANDIDATE = id(1);
    private static final UUID ACTOR = id(2);
    private static final UUID CORRELATION = id(3);
    private static final UUID DELIVERY = id(4);
    private static final Instant NOW = Instant.parse("2026-08-04T15:00:00Z");

    @Test
    void persistsAuditAndOutboxInTheSameExecutionCall() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(String.class), any())).thenReturn("");
        when(jdbc.queryForObject(anyString(), eq(Long.class), any())).thenReturn(1L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        var executions = new AdminMcpJdbcConfiguration.JdbcExecutions(jdbc, json);
        AdminMcpInvocation invocation = new AdminMcpInvocation(
                new OperatorRequestContext(ACTOR, true, true),
                "mcp-v1",
                "corporate_action_candidate.approve",
                "schema-v1",
                CANDIDATE.toString(),
                "a".repeat(64),
                Map.of("candidateId", CANDIDATE.toString()),
                CORRELATION,
                "approval-1",
                "c".repeat(64));

        AdminMcpExecutionResult result = executions.executeIdempotently(
                invocation,
                NOW,
                () -> appliedResult());

        assertThat(result.status()).isEqualTo(AdminMcpExecutionResult.Status.APPLIED);
        verify(jdbc, atLeast(2)).update(anyString(), any(Object[].class));
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(2)).update(anyString(), arguments.capture());
        assertThat(arguments.getAllValues().stream()
                .flatMap(values -> java.util.Arrays.stream(values))
                .map(String::valueOf))
                .anyMatch(value -> value.contains("APPLY_CORPORATE_ACTION_APPROVAL")
                        && value.contains("decidedContentHash")
                        && value.contains(DELIVERY.toString()));
    }

    private static AdminMcpExecutionResult appliedResult() {
        Map<String, Object> payload = Map.ofEntries(
                Map.entry("candidateId", CANDIDATE),
                Map.entry("decision", "APPROVE"),
                Map.entry("decidedContentHash", "a".repeat(64)),
                Map.entry("evidenceBindings", List.of("b".repeat(64))),
                Map.entry("actorId", ACTOR),
                Map.entry("auditId", CORRELATION),
                Map.entry("permissionId", id(12)),
                Map.entry("requestSchemaVersion", "schema-v1"),
                Map.entry("decidedAt", NOW),
                Map.entry("deliveryId", DELIVERY),
                Map.entry("aggregateSequence", 1));
        return new AdminMcpExecutionResult(
                AdminMcpExecutionResult.Status.APPLIED,
                "CORPORATE_ACTION_DECISION_ACCEPTED",
                payload,
                new AdminMcpExecutionResult.AuditEvidence(
                        ACTOR, "mcp-v1", "rbac-v1",
                        "corporate_action_candidate.approve", "CORPORATE_ACTION",
                        CANDIDATE.toString(), "a".repeat(64),
                        "CORPORATE_ACTION_DECISION_ACCEPTED", CORRELATION, NOW,
                        Map.of("status", "REVIEW_REQUIRED"), Map.of("status", "APPROVED")));
    }

    private static UUID id(long suffix) {
        return UUID.fromString("10000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
