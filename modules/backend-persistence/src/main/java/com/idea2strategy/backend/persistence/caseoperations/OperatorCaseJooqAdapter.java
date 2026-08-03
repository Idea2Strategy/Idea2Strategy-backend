package com.idea2strategy.backend.persistence.caseoperations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.caseoperations.CaseNotificationOutboxPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthorizationPort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseAssigneePort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseAuthorizationPort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommand;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommandPort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseDecisionResult;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseIdempotencyConflictException;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueuePort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueryRejectedException;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseState;
import com.idea2strategy.backend.application.operatorrbac.OperatorRequestContext;
import com.idea2strategy.backend.application.usercase.UserCaseStatus;
import com.idea2strategy.backend.application.usercase.UserCaseType;
import com.idea2strategy.backend.application.usercase.UserCaseView;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OperatorCaseJooqAdapter implements
        OperatorCaseCommandPort,
        OperatorCaseQueuePort,
        OperatorCaseAssigneePort,
        OperatorCaseAuthorizationPort,
        AccountSanctionAuthorizationPort,
        CaseNotificationOutboxPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OperatorCaseJooqAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public OperatorCaseDecisionResult executeAtomically(
            OperatorCaseCommand command,
            Instant evaluatedAt,
            OperatorCaseCommandPort.Decision decision) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                command.requestContext().operatorId() + ":" + command.action() + ":" + command.idempotencyKey());
        OperatorCaseDecisionResult replay = replay(command);
        if (replay != null) {
            return replay;
        }

        OperatorCaseState state = findCaseForUpdate(command.caseId())
                .orElseThrow(() -> new OperatorCaseQueryRejectedException("CASE_NOT_AVAILABLE"));
        OperatorCaseDecisionResult result = decision.decide(state);
        UUID eventId = null;
        if (result.mutation() != null) {
            eventId = applyMutation(command, state, result, state.databaseNow());
        }
        insertReceipt(command, result, eventId, state.databaseNow());
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Page findQueue(Query query, Instant evaluatedAt) {
        StringBuilder sql = new StringBuilder("""
                select id, case_type::text as case_type, status::text as status,
                       case_version, assignee_operator_id, updated_at
                from operations.cases where case_type::text in (
                """);
        List<Object> arguments = new ArrayList<>();
        appendIn(sql, arguments, query.caseTypes().stream().map(Enum::name).toList());
        sql.append(")");
        if (!query.statuses().isEmpty()) {
            sql.append(" and status::text in (");
            appendIn(sql, arguments, query.statuses().stream().map(Enum::name).toList());
            sql.append(")");
        }
        if (query.assigneeOperatorId() != null) {
            sql.append(" and assignee_operator_id = ?");
            arguments.add(query.assigneeOperatorId());
        }
        if (query.cursor() != null && !query.cursor().isBlank()) {
            sql.append(" and id < ?");
            arguments.add(UUID.fromString(query.cursor()));
        }
        sql.append(" order by updated_at desc, id desc limit ?");
        arguments.add(query.limit() + 1);
        List<Item> rows = jdbc.query(sql.toString(), (result, row) -> new Item(
                result.getObject("id", UUID.class),
                UserCaseType.valueOf(result.getString("case_type")),
                UserCaseStatus.valueOf(result.getString("status")),
                result.getLong("case_version"),
                result.getObject("assignee_operator_id", UUID.class),
                result.getObject("updated_at", OffsetDateTime.class).toInstant()), arguments.toArray());
        String next = rows.size() > query.limit() ? rows.get(query.limit() - 1).caseId().toString() : null;
        return new Page(rows.stream().limit(query.limit()).toList(), next);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OperatorCaseState> findCase(UUID caseId, Instant evaluatedAt) {
        return loadCase(caseId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveAssignableOperator(UUID operatorId, Instant evaluatedAt) {
        if (operatorId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                select count(*) from operations.operator_accounts
                where id = ? and status = 'ACTIVE' and disabled_at is null
                """, Integer.class, operatorId);
        return count != null && count == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public OperatorCaseAuthorizationPort.Decision authorize(
            OperatorRequestContext context,
            UUID requiredPermissionId,
            UserCaseType caseType,
            String action,
            Instant evaluatedAt) {
        String catalog = jdbc.query("""
                select catalog_version from operations.rbac_catalog_versions
                where status = 'ACTIVE' order by activated_at desc limit 1
                """, result -> result.next() ? result.getString(1) : null);
        if (catalog == null) {
            return OperatorCaseAuthorizationPort.Decision.rejected("RBAC_CATALOG_NOT_ACTIVE", null);
        }
        if (!context.mfaCompleted()) {
            return OperatorCaseAuthorizationPort.Decision.rejected("OPERATOR_MFA_REQUIRED", catalog);
        }
        Integer granted = jdbc.queryForObject("""
                select count(*)
                from operations.operator_accounts operator
                join operations.operator_role_assignments assignment
                  on assignment.operator_account_id = operator.id
                join operations.rbac_catalog_roles role
                  on role.catalog_version = assignment.catalog_version
                 and role.role_id = assignment.role_id and role.role_status = 'ACTIVE'
                join operations.rbac_catalog_role_permissions mapping
                  on mapping.catalog_version = role.catalog_version and mapping.role_id = role.role_id
                join operations.rbac_catalog_permissions permission
                  on permission.catalog_version = mapping.catalog_version
                 and permission.permission_id = mapping.permission_id
                 and permission.permission_status = 'ACTIVE'
                where operator.id = ? and operator.status = 'ACTIVE' and operator.disabled_at is null
                  and assignment.catalog_version = ? and assignment.revoked_at is null
                  and (assignment.expires_at is null or assignment.expires_at > ?)
                  and permission.permission_id = ?
                """, Integer.class, context.operatorId(), catalog, Timestamp.from(evaluatedAt), requiredPermissionId);
        return granted != null && granted > 0
                ? OperatorCaseAuthorizationPort.Decision.granted(catalog)
                : OperatorCaseAuthorizationPort.Decision.rejected("CASE_PERMISSION_DENIED", catalog);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSanctionAuthorizationPort.Decision authorize(
            OperatorRequestContext context,
            UUID requiredPermissionId,
            Instant evaluatedAt) {
        String catalog = jdbc.query("""
                select catalog_version from operations.rbac_catalog_versions
                where status = 'ACTIVE' order by activated_at desc limit 1
                """, result -> result.next() ? result.getString(1) : null);
        boolean activeOperator = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from operations.operator_accounts
                where id = ? and status = 'ACTIVE' and disabled_at is null)
                """, Boolean.class, context.operatorId()));
        if (catalog == null) {
            return new AccountSanctionAuthorizationPort.Decision(
                    false, "RBAC_CATALOG_NOT_ACTIVE", null, Set.of(), Set.of(), activeOperator,
                    context.mfaCompleted());
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select assignment.role_id, mapping.permission_id
                from operations.operator_role_assignments assignment
                join operations.rbac_catalog_roles role
                  on role.catalog_version = assignment.catalog_version
                 and role.role_id = assignment.role_id and role.role_status = 'ACTIVE'
                join operations.rbac_catalog_role_permissions mapping
                  on mapping.catalog_version = role.catalog_version and mapping.role_id = role.role_id
                join operations.rbac_catalog_permissions permission
                  on permission.catalog_version = mapping.catalog_version
                 and permission.permission_id = mapping.permission_id
                 and permission.permission_status = 'ACTIVE'
                where assignment.operator_account_id = ? and assignment.catalog_version = ?
                  and assignment.revoked_at is null
                  and (assignment.expires_at is null or assignment.expires_at > ?)
                """, context.operatorId(), catalog, Timestamp.from(evaluatedAt));
        Set<UUID> roleIds = new HashSet<>();
        Set<UUID> permissionIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            roleIds.add((UUID) row.get("role_id"));
            permissionIds.add((UUID) row.get("permission_id"));
        }
        boolean mfa = context.mfaCompleted();
        boolean granted = activeOperator && mfa && permissionIds.contains(requiredPermissionId);
        String code = granted ? "SANCTION_PERMISSION_GRANTED"
                : !activeOperator ? "OPERATOR_NOT_ACTIVE"
                : !mfa ? "OPERATOR_MFA_REQUIRED"
                : "SANCTION_PERMISSION_DENIED";
        return new AccountSanctionAuthorizationPort.Decision(
                granted, code, catalog, roleIds, permissionIds, activeOperator, mfa);
    }

    @Override
    @Transactional
    public void stageInCurrentTransaction(Intent intent) {
        jdbc.update("""
                insert into operations.outbox_messages
                    (id, owner_domain, aggregate_id, aggregate_sequence, event_type,
                     event_schema_version, payload_document, idempotency_key, created_at)
                values (?, 'OPERATIONS_CASE', ?, ?, ?, '1', cast(? as jsonb), ?, clock_timestamp())
                on conflict (idempotency_key) do nothing
                """, UUID.randomUUID(), intent.caseId(), intent.caseVersion(), intent.eventType(),
                write(intent.publicPayload()), intent.idempotencyKey());
    }

    private Optional<OperatorCaseState> findCaseForUpdate(UUID caseId) {
        return loadCase(caseId, true);
    }

    private Optional<OperatorCaseState> loadCase(UUID caseId, boolean lock) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, account_id, case_type::text, status::text, case_version,
                       assignee_operator_id, updated_at, response_deadline_at,
                       deadline_policy_version, clock_timestamp() as database_now
                from operations.cases where id = ?
                """ + (lock ? " for update" : ""), caseId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.getFirst();
        UUID accountId = (UUID) row.get("account_id");
        List<OperatorCaseState.Evidence> evidence = jdbc.query("""
                select reference.storage_object_id, reference.source_domain,
                       reference.owner_account_id, reference.ownership_policy_version,
                       reference.ownership_verified_at, reference.attached_at,
                       object.status::text as object_status, object.media_type,
                       object.content_hash, object.retention_policy_version
                from operations.case_evidence_references reference
                join storage.objects object on object.id = reference.storage_object_id
                where reference.case_id = ? order by reference.attached_at, reference.storage_object_id
                """, (result, index) -> {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("contentHash", result.getString("content_hash"));
                    attributes.put("policyVersion", result.getString("ownership_policy_version"));
                    attributes.put("capturedAt", result.getObject("ownership_verified_at", OffsetDateTime.class)
                            .toInstant().toString());
                    attributes.put("retentionCategory", result.getString("retention_policy_version"));
                    return new OperatorCaseState.Evidence(
                            result.getObject("storage_object_id", UUID.class),
                            result.getString("media_type"), result.getString("object_status"),
                            result.getString("source_domain"),
                            accountId.equals(result.getObject("owner_account_id", UUID.class)),
                            result.getObject("attached_at", OffsetDateTime.class).toInstant(), attributes);
                }, caseId);
        var view = new UserCaseView(
                (UUID) row.get("id"), accountId,
                UserCaseType.valueOf((String) row.get("case_type")),
                UserCaseStatus.valueOf((String) row.get("status")),
                ((Number) row.get("case_version")).longValue(),
                evidence.stream().map(OperatorCaseState.Evidence::evidenceId).toList(),
                instant(row.get("updated_at")));
        Object deadlineValue = row.get("response_deadline_at");
        Instant deadline = deadlineValue == null ? null : instant(deadlineValue);
        return Optional.of(new OperatorCaseState(
                view, (UUID) row.get("assignee_operator_id"), evidence,
                instant(row.get("database_now")), deadline,
                (String) row.get("deadline_policy_version")));
    }

    private UUID applyMutation(
            OperatorCaseCommand command,
            OperatorCaseState state,
            OperatorCaseDecisionResult result,
            Instant evaluatedAt) {
        var mutation = result.mutation();
        UUID eventId = UUID.randomUUID();
        UUID previousEvent = jdbc.queryForObject(
                "select last_case_event_id from operations.cases where id = ?", UUID.class, command.caseId());
        String eventType = databaseEventType(mutation.eventType());
        String visibility = switch (eventType) {
            case "INFORMATION_REQUESTED", "RESOLVED", "REJECTED", "SANCTION_APPLIED", "SANCTION_RELEASED"
                    -> "USER_VISIBLE";
            default -> "OPERATOR_ONLY";
        };
        jdbc.update("""
                insert into operations.case_events
                    (id, case_id, account_id, event_sequence, previous_event_id, actor_type,
                     actor_id, event_type, resulting_status, visibility, reason_code,
                     correlation_id, payload_document, created_at)
                values (?, ?, ?, ?, ?, 'OPERATOR', ?, cast(? as operations.case_event_type),
                        cast(? as operations.case_status), cast(? as operations.case_event_visibility),
                        ?, ?, cast(? as jsonb), ?)
                """, eventId, command.caseId(), state.caseView().accountId(), mutation.nextVersion(),
                previousEvent, command.requestContext().operatorId(), eventType, mutation.status().name(),
                visibility, command.reasonCode(), command.correlationId(), write(result.auditEvidence()),
                Timestamp.from(evaluatedAt));
        boolean terminal = mutation.status().terminal();
        int changed = jdbc.update("""
                update operations.cases
                set status = cast(? as operations.case_status), assignee_operator_id = ?,
                    case_version = ?, current_event_sequence = ?, last_case_event_id = ?, updated_at = ?,
                    response_deadline_at = ?, deadline_policy_version = ?,
                    closed_at = case when ? then cast(? as timestamptz) else null end,
                    resolution_code = case when ? then cast(? as text) else null end
                where id = ? and case_version = ?
                """, mutation.status().name(), mutation.assigneeOperatorId(), mutation.nextVersion(),
                Math.toIntExact(mutation.nextVersion()), eventId, Timestamp.from(evaluatedAt),
                mutation.responseDeadlineAt() == null ? null : Timestamp.from(mutation.responseDeadlineAt()),
                mutation.deadlinePolicyVersion(), terminal,
                Timestamp.from(evaluatedAt), terminal, mutation.eventType(), command.caseId(), command.expectedVersion());
        if (changed != 1) {
            throw new IllegalStateException("CASE_CONCURRENT_MUTATION");
        }
        return eventId;
    }

    private void insertReceipt(
            OperatorCaseCommand command,
            OperatorCaseDecisionResult result,
            UUID eventId,
            Instant evaluatedAt) {
        jdbc.update("""
                insert into operations.operator_case_command_receipts
                    (operator_id, command_type, idempotency_key, request_hash, case_id,
                     case_event_id, decision_status, response_code, response_document,
                     audit_document, completed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), ?)
                """, command.requestContext().operatorId(), command.action().name(),
                command.idempotencyKey(), command.requestHash(), command.caseId(), eventId,
                result.status().name(), result.code(), write(result), write(result.auditEvidence()),
                Timestamp.from(evaluatedAt));
    }

    private OperatorCaseDecisionResult replay(OperatorCaseCommand command) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select request_hash, response_document::text as response_document
                from operations.operator_case_command_receipts
                where operator_id = ? and command_type = ? and idempotency_key = ?
                """, command.requestContext().operatorId(), command.action().name(), command.idempotencyKey());
        if (rows.isEmpty()) {
            return null;
        }
        if (!command.requestHash().equals(rows.getFirst().get("request_hash"))) {
            throw new OperatorCaseIdempotencyConflictException();
        }
        try {
            return json.readValue((String) rows.getFirst().get("response_document"),
                    OperatorCaseDecisionResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored operator case receipt is invalid", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Operator case JSON could not be serialized", exception);
        }
    }

    private static String databaseEventType(String eventType) {
        return switch (eventType) {
            case "CASE_ASSIGNED" -> "ASSIGNED";
            case "CASE_REASSIGNED" -> "REASSIGNED";
            case "CASE_UNASSIGNED" -> "UNASSIGNED";
            case "CASE_REVIEW_STARTED" -> "REVIEW_STARTED";
            case "CASE_INFORMATION_REQUESTED" -> "INFORMATION_REQUESTED";
            case "CASE_RESOLVED" -> "RESOLVED";
            case "CASE_REJECTED" -> "REJECTED";
            case "CASE_SANCTION_APPLIED" -> "SANCTION_APPLIED";
            case "CASE_SANCTION_RELEASED" -> "SANCTION_RELEASED";
            default -> throw new IllegalStateException("Unsupported operator case event: " + eventType);
        };
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalStateException("CASE_TIMESTAMP_INVALID");
    }

    private static void appendIn(StringBuilder sql, List<Object> arguments, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) sql.append(",");
            sql.append("?");
            arguments.add(values.get(index));
        }
    }
}
