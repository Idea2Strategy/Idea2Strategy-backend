package com.idea2strategy.backend.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.adminmcp.AdminMcpAuthorizationPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionPort;
import com.idea2strategy.backend.application.adminmcp.AdminMcpExecutionResult;
import com.idea2strategy.backend.application.adminmcp.AdminMcpIdempotencyConflictException;
import com.idea2strategy.backend.application.adminmcp.AdminMcpInvocation;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(JdbcTemplate.class)
class AdminMcpJdbcConfiguration {
    @Bean
    AdminMcpAuthorizationPort adminMcpAuthorizationPort(JdbcTemplate jdbc) {
        return (context, permissionId, targetDomain, evaluatedAt) -> authorize(
                jdbc, context, permissionId, evaluatedAt);
    }

    @Bean
    AdminMcpExecutionPort adminMcpExecutionPort(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcExecutions(jdbc, objectMapper);
    }

    private static AdminMcpAuthorizationPort.Decision authorize(
            JdbcTemplate jdbc,
            OperatorRequestContext context,
            UUID permissionId,
            Instant evaluatedAt) {
        List<String> versions = jdbc.queryForList("""
                select distinct a.catalog_version
                from operations.operator_accounts o
                join operations.operator_role_assignments a on a.operator_account_id = o.id
                join operations.rbac_catalog_versions v on v.catalog_version = a.catalog_version
                join operations.rbac_catalog_role_permissions rp
                  on rp.catalog_version = a.catalog_version and rp.role_id = a.role_id
                join operations.rbac_catalog_permissions p
                  on p.catalog_version = rp.catalog_version and p.permission_id = rp.permission_id
                where o.id = ? and o.status = 'ACTIVE' and o.disabled_at is null
                  and o.mfa_enrolled_at is not null and o.last_mfa_verified_at is not null
                  and v.status = 'ACTIVE' and p.permission_status = 'ACTIVE'
                  and rp.permission_id = ? and a.granted_at <= ?
                  and (a.expires_at is null or a.expires_at > ?) and a.revoked_at is null
                """, String.class, context.operatorId(), permissionId,
                Timestamp.from(evaluatedAt), Timestamp.from(evaluatedAt));
        return versions.size() == 1
                ? AdminMcpAuthorizationPort.Decision.granted(versions.getFirst())
                : AdminMcpAuthorizationPort.Decision.rejected("ADMIN_MCP_PERMISSION_DENIED", null);
    }

    static final class JdbcExecutions implements AdminMcpExecutionPort {
        private final JdbcTemplate jdbc;
        private final ObjectMapper json;

        JdbcExecutions(JdbcTemplate jdbc, ObjectMapper json) {
            this.jdbc = jdbc;
            this.json = json;
        }

        @Override
        @Transactional
        public AdminMcpExecutionResult executeIdempotently(
                AdminMcpInvocation invocation, Instant evaluatedAt, Decision decision) {
            advisoryLock(invocation.idempotencyKey());
            List<Map<String, Object>> prior = jdbc.queryForList("""
                    select request_hash, evidence_document::text as evidence_document
                    from operations.audit_events where idempotency_key = ? for update
                    """, invocation.idempotencyKey());
            if (!prior.isEmpty()) {
                if (!invocation.requestHash().equals(prior.getFirst().get("request_hash"))) {
                    throw new AdminMcpIdempotencyConflictException();
                }
                return readResult(String.valueOf(prior.getFirst().get("evidence_document")));
            }

            AdminMcpExecutionResult result = decision.decide();
            if (result.status() == AdminMcpExecutionResult.Status.APPLIED) {
                long sequence = nextAggregateSequence(invocation.targetId());
                Map<String, Object> response = new java.util.LinkedHashMap<>(result.response());
                response.put("aggregateSequence", sequence);
                result = new AdminMcpExecutionResult(
                        result.status(), result.code(), response, result.auditEvidence());
            }
            String request = write(Map.of(
                    "toolName", invocation.toolName(),
                    "targetId", invocation.targetId(),
                    "decidedContentHash", invocation.decidedContentHash() == null
                            ? "" : invocation.decidedContentHash(),
                    "input", invocation.input()));
            String response = write(result.response());
            String before = write(result.auditEvidence().before());
            String after = write(result.auditEvidence().after());
            String evidence = write(result);
            jdbc.update("""
                    insert into operations.audit_events
                      (id, actor_type, actor_id, action_type, target_domain, target_id,
                       reason_code, correlation_id, idempotency_key, before_hash, after_hash,
                       occurred_at, rbac_catalog_version, resolved_rbac_catalog_version,
                       request_hash, decision_status, response_status, response_code,
                       evidence_hash, request_document, response_document, before_document,
                       after_document, evidence_document)
                    values (?, 'OPERATOR', ?, ?, 'CORPORATE_ACTION', ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                            cast(? as jsonb), cast(? as jsonb), cast(? as jsonb))
                    """,
                    invocation.correlationId(), invocation.requestContext().operatorId(),
                    invocation.toolName(), UUID.fromString(invocation.targetId()), result.code(),
                    invocation.correlationId(), invocation.idempotencyKey(), sha256(before),
                    sha256(after), Timestamp.from(evaluatedAt), result.auditEvidence().rbacCatalogVersion(),
                    result.auditEvidence().rbacCatalogVersion(), invocation.requestHash(),
                    result.status() == AdminMcpExecutionResult.Status.REJECTED ? "REJECTED" : "SUCCEEDED",
                    result.status() == AdminMcpExecutionResult.Status.REJECTED ? 403 : 200,
                    result.code(), sha256(evidence), request, response, before, after, evidence);
            if (result.status() == AdminMcpExecutionResult.Status.APPLIED) {
                writeOutbox(invocation, result, evaluatedAt);
            }
            return result;
        }

        private void advisoryLock(String identity) {
            jdbc.queryForObject(
                    "select pg_advisory_xact_lock(hashtextextended(?::text, 0))::text",
                    String.class,
                    identity);
        }

        private long nextAggregateSequence(String candidateId) {
            advisoryLock("corporate-action:" + candidateId);
            Long next = jdbc.queryForObject("""
                    select coalesce(max(aggregate_sequence), 0) + 1
                    from operations.outbox_messages
                    where owner_domain = 'CORPORATE_ACTION' and aggregate_id = ?
                    """, Long.class, UUID.fromString(candidateId));
            if (next == null || next < 1) {
                throw new IllegalStateException("corporate-action aggregate sequence unavailable");
            }
            return next;
        }

        private void writeOutbox(
                AdminMcpInvocation invocation,
                AdminMcpExecutionResult result,
                Instant evaluatedAt) {
            UUID deliveryId = UUID.fromString(String.valueOf(result.response().get("deliveryId")));
            long sequence = ((Number) result.response().get("aggregateSequence")).longValue();
            String command = write(Map.of(
                    "command", "APPLY_CORPORATE_ACTION_APPROVAL",
                    "command_id", deliveryId.toString(),
                    "payload", result.response(),
                    "issued_at", evaluatedAt.toString()));
            jdbc.update("""
                    insert into operations.outbox_messages
                      (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                       event_schema_version, payload_document, payload_hash,
                       producer_idempotency_key, idempotency_key, delivery_status, created_at)
                    values (?, 'CORPORATE_ACTION', ?, ?, 'CORPORATE_ACTION_APPROVAL_DECIDED',
                            'schema-v1', cast(? as jsonb), ?, ?, ?, 'PENDING', ?)
                    """, deliveryId, UUID.fromString(invocation.targetId()), sequence, command,
                    sha256(command), invocation.idempotencyKey(),
                    "corporate-action-approval:" + invocation.idempotencyKey(),
                    Timestamp.from(evaluatedAt));
        }

        private AdminMcpExecutionResult readResult(String document) {
            try {
                return json.readValue(document, AdminMcpExecutionResult.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("stored admin MCP evidence is unreadable", exception);
            }
        }

        private String write(Object value) {
            try {
                return json.writeValueAsString(value);
            } catch (JsonProcessingException exception) {
                throw new IllegalArgumentException("admin MCP evidence cannot be serialized", exception);
            }
        }

        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }
    }
}
