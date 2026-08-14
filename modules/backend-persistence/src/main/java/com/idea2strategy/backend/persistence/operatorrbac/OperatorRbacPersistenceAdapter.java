package com.idea2strategy.backend.persistence.operatorrbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommand;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandPort;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacDecision;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacIdempotencyConflictException;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacResult;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacState;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OperatorRbacPersistenceAdapter implements OperatorRbacCommandPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OperatorRbacPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional
    public OperatorRbacResult executeAtomically(
            OperatorRbacCommand command, Instant evaluatedAt, OperatorRbacDecision decision) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                command.idempotencyKey());
        List<Map<String, Object>> prior = jdbc.queryForList("""
                select request_hash, response_document::text
                from operations.audit_events where idempotency_key = ? for update
                """, command.idempotencyKey());
        if (!prior.isEmpty()) {
            if (!command.requestHash().equals(prior.getFirst().get("request_hash"))) {
                throw new OperatorRbacIdempotencyConflictException();
            }
            return readResult(prior.getFirst().get("response_document").toString());
        }

        String requestDocument = write(requestDocument(command));
        String canonicalHash = jsonbHash(requestDocument);
        if (!canonicalHash.equals(command.requestHash())) {
            throw new OperatorRbacIdempotencyConflictException();
        }

        OperatorRbacState state = loadState(command);
        String before = write(stateDocument(state, evaluatedAt));
        OperatorRbacResult result = decision.decide(state);
        applyMutation(result);
        OperatorRbacState afterState = result.mutation() == null ? state : loadState(command);
        String after = write(stateDocument(afterState, evaluatedAt));
        String evidence = write(evidenceDocument(command, result));
        String response = write(result);
        String catalog = command.expectedCatalogVersion();
        String resolvedCatalog = state.catalog() != null && catalog.equals(state.catalog().version())
                ? catalog : null;
        boolean succeeded = result.decisionStatus() != OperatorRbacResult.DecisionStatus.REJECTED;
        int responseStatus = succeeded ? 200 : rejectedStatus(result.code());

        jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id,
                     reason_code, correlation_id, idempotency_key, rbac_catalog_version,
                     resolved_rbac_catalog_version, request_hash, decision_status,
                     response_status, response_code, request_document, response_document,
                     before_document, after_document, evidence_document,
                     before_hash, after_hash, evidence_hash, occurred_at)
                values (?, 'OPERATOR', ?, ?, 'OPERATOR_RBAC', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                        cast(? as jsonb),
                        encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex'),
                        encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex'),
                        encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex'), ?)
                """, UUID.randomUUID(), command.requestContext().operatorId(),
                "OPERATOR_ROLE_" + command.type().name(), command.targetOperatorId(),
                command.reasonCode(), command.correlationId(), command.idempotencyKey(), catalog,
                resolvedCatalog, canonicalHash, succeeded ? "SUCCEEDED" : "REJECTED",
                responseStatus, result.code(), requestDocument, response, before, after, evidence,
                before, after, evidence, Timestamp.from(evaluatedAt));
        return result;
    }

    public String canonicalRequestHash(OperatorRbacCommand command) {
        return jsonbHash(write(requestDocument(command)));
    }

    private OperatorRbacState loadState(OperatorRbacCommand command) {
        List<UUID> operatorIds = new ArrayList<>(Set.of(
                command.requestContext().operatorId(), command.targetOperatorId()));
        operatorIds.sort(Comparator.naturalOrder());
        for (UUID id : operatorIds) {
            jdbc.queryForList("select id from operations.operator_accounts where id = ? for update", id);
        }
        List<Map<String, Object>> catalogs = jdbc.queryForList("""
                select catalog_version, status from operations.rbac_catalog_versions
                where status = 'ACTIVE' for update
                """);
        OperatorRbacState.Catalog catalog = catalogs.isEmpty()
                ? null : loadCatalog(catalogs.getFirst().get("catalog_version").toString());
        OperatorRbacState.Operator actor = operator(command.requestContext().operatorId());
        OperatorRbacState.Operator target = operator(command.targetOperatorId());
        List<OperatorRbacState.Assignment> actorAssignments = assignments(command.requestContext().operatorId());
        List<OperatorRbacState.Assignment> targetAssignments = assignments(command.targetOperatorId());
        OperatorRbacState.Assignment selected = command.assignmentId() == null ? null
                : targetAssignments.stream().filter(a -> a.id().equals(command.assignmentId())).findFirst().orElse(null);
        return new OperatorRbacState(catalog, actor, target, actorAssignments, targetAssignments, selected);
    }

    private OperatorRbacState.Catalog loadCatalog(String version) {
        Set<UUID> permissions = new LinkedHashSet<>(jdbc.queryForList("""
                select permission_id from operations.rbac_catalog_permissions
                where catalog_version = ? and permission_status = 'ACTIVE'
                """, UUID.class, version));
        Map<UUID, MutableRole> roles = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select role_id, hierarchy_rank, role_status from operations.rbac_catalog_roles
                where catalog_version = ?
                """, version)) {
            UUID roleId = (UUID) row.get("role_id");
            roles.put(roleId, new MutableRole(roleId,
                    ((Number) row.get("hierarchy_rank")).intValue(),
                    "ACTIVE".equals(row.get("role_status"))));
        }
        for (Map<String, Object> row : jdbc.queryForList("""
                select role_id, permission_id, delegable
                from operations.rbac_catalog_role_permissions
                where catalog_version = ?
                """, version)) {
            MutableRole role = roles.get(row.get("role_id"));
            UUID permission = (UUID) row.get("permission_id");
            if (role != null && permissions.contains(permission)) {
                role.permissions.add(permission);
                if (Boolean.TRUE.equals(row.get("delegable"))) role.delegable.add(permission);
            }
        }
        Map<UUID, OperatorRbacState.Role> snapshots = new LinkedHashMap<>();
        roles.forEach((id, role) -> snapshots.put(id, new OperatorRbacState.Role(
                id, role.active, role.rank, role.permissions, role.delegable)));
        return new OperatorRbacState.Catalog(version, OperatorRbacState.Status.ACTIVE, snapshots, permissions);
    }

    private OperatorRbacState.Operator operator(UUID id) {
        List<OperatorRbacState.Operator> rows = jdbc.query("""
                select a.id, a.status, (c.totp_enrolled_at is not null)
                from operations.operator_accounts a
                left join operations.operator_login_credentials c on c.operator_account_id = a.id
                where a.id = ?
                """, (rs, row) -> new OperatorRbacState.Operator(rs.getObject(1, UUID.class),
                "ACTIVE".equals(rs.getString(2)),
                rs.getTimestamp(3) != null), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<OperatorRbacState.Assignment> assignments(UUID operatorId) {
        return jdbc.query("""
                select id, operator_account_id, role_id, catalog_version,
                       granted_at, expires_at, revoked_at
                from operations.operator_role_assignments
                where operator_account_id = ? and catalog_version is not null
                order by granted_at, id for update
                """, (rs, row) -> new OperatorRbacState.Assignment(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getString(4), rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(),
                rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()), operatorId);
    }

    private void applyMutation(OperatorRbacResult result) {
        OperatorRbacDecision.Mutation mutation = result.mutation();
        if (mutation == null) return;
        if (mutation.kind() == OperatorRbacDecision.Mutation.Kind.GRANT) {
            jdbc.update("""
                    insert into operations.operator_role_assignments
                        (id, operator_account_id, role_id, catalog_version,
                         granted_by_operator_id, granted_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), mutation.targetOperatorId(), mutation.roleId(),
                    mutation.catalogVersion(), mutation.changedByOperatorId(),
                    Timestamp.from(mutation.grantedAt()), mutation.expiresAt() == null
                            ? null : Timestamp.from(mutation.expiresAt()));
        } else {
            int changed = jdbc.update("""
                    update operations.operator_role_assignments
                    set revoked_by_operator_id = ?, revoked_at = ?, revocation_reason_code = ?
                    where id = ? and operator_account_id = ? and revoked_at is null
                    """, mutation.changedByOperatorId(), Timestamp.from(mutation.revokedAt()),
                    mutation.reasonCode(), mutation.assignmentId(), mutation.targetOperatorId());
            if (changed != 1) throw new IllegalStateException("assignment changed during authorization");
        }
    }

    private Map<String, Object> requestDocument(OperatorRbacCommand command) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", command.type().name());
        value.put("actorOperatorId", command.requestContext().operatorId().toString());
        value.put("targetOperatorId", command.targetOperatorId().toString());
        value.put("roleId", command.roleId() == null ? null : command.roleId().toString());
        value.put("assignmentId", command.assignmentId() == null ? null : command.assignmentId().toString());
        value.put("expiresAt", command.expiresAt() == null ? null : command.expiresAt().toString());
        value.put("reasonCode", command.reasonCode());
        value.put("catalogVersion", command.expectedCatalogVersion());
        return value;
    }

    private Map<String, Object> stateDocument(OperatorRbacState state, Instant evaluatedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evaluatedAt", evaluatedAt.toString());
        value.put("catalogVersion", state.catalog() == null ? null : state.catalog().version());
        value.put("actor", state.actor() == null ? null : state.actor().id().toString());
        value.put("target", state.target() == null ? null : state.target().id().toString());
        value.put("targetAssignments", state.targetAssignments().stream().map(a -> Map.of(
                "id", a.id().toString(), "roleId", a.roleId().toString(),
                "catalogVersion", a.catalogVersion(), "grantedAt", a.grantedAt().toString(),
                "revokedAt", a.revokedAt() == null ? "" : a.revokedAt().toString())).toList());
        return value;
    }

    private Map<String, Object> evidenceDocument(OperatorRbacCommand command, OperatorRbacResult result) {
        var evidence = result.evidence();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("requiredPermissionId", command.requiredPermissionId().toString());
        value.put("actorRoleIds", sorted(evidence.actorRoleIds()));
        value.put("actorPermissionIds", sorted(evidence.actorPermissionIds()));
        value.put("actorDelegablePermissionIds", sorted(evidence.actorDelegablePermissionIds()));
        value.put("targetRolePermissionIds", sorted(evidence.targetRolePermissionIds()));
        value.put("sessionAuthenticated", evidence.sessionAuthenticated());
        value.put("mfaSatisfied", evidence.mfaSatisfied());
        value.put("strictHierarchySatisfied", evidence.strictHierarchySatisfied());
        return value;
    }

    private List<String> sorted(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).sorted().toList();
    }

    private String jsonbHash(String document) {
        return jdbc.queryForObject("""
                select encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex')
                """, String.class, document);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("RBAC evidence serialization failed", e); }
    }

    private OperatorRbacResult readResult(String value) {
        try { return json.readValue(value, OperatorRbacResult.class); }
        catch (Exception e) { throw new IllegalStateException("stored RBAC response is invalid", e); }
    }

    private int rejectedStatus(String code) {
        return code.endsWith("NOT_FOUND") ? 404 : 403;
    }

    private static final class MutableRole {
        final UUID id;
        final int rank;
        final boolean active;
        final Set<UUID> permissions = new LinkedHashSet<>();
        final Set<UUID> delegable = new LinkedHashSet<>();
        MutableRole(UUID id, int rank, boolean active) { this.id = id; this.rank = rank; this.active = active; }
    }
}
