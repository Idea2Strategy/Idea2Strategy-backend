package com.idea2strategy.backend.persistence.operatorrbac;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadModels;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacReadPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Fresh PostgreSQL projection for the operator permission read boundary. */
@Component
public class OperatorRbacReadPersistenceAdapter implements OperatorRbacReadPort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OperatorRbacReadPersistenceAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public OperatorRbacReadModels.ActorState loadActorState(UUID actorId, Instant evaluatedAt) {
        List<Map<String, Object>> operators = jdbc.queryForList("""
                select status
                from operations.operator_accounts where id = ?
                """, actorId);
        String catalogVersion = activeCatalogVersion();
        boolean active = operators.size() == 1 && "ACTIVE".equals(operators.getFirst().get("status"));
        Instant lastMfa = null;
        if (!active || catalogVersion == null) {
            var self = new OperatorRbacReadModels.SelfView(
                    actorId, catalogVersion, false, null, lastMfa,
                    List.of(), List.of(), List.of());
            return new OperatorRbacReadModels.ActorState(active, catalogVersion, Set.of(), self);
        }

        Timestamp at = Timestamp.from(evaluatedAt);
        List<OperatorRbacReadModels.AssignmentView> assignments = jdbc.query("""
                select a.id, a.operator_account_id, a.role_id, r.code, a.catalog_version,
                       a.granted_at, a.expires_at, a.revoked_at, a.revocation_reason_code
                from operations.operator_role_assignments a
                join operations.rbac_catalog_roles cr
                  on cr.catalog_version = a.catalog_version and cr.role_id = a.role_id
                 and cr.role_status = 'ACTIVE'
                join operations.roles r on r.id = a.role_id
                where a.operator_account_id = ? and a.catalog_version = ?
                  and a.granted_at <= ? and a.revoked_at is null
                  and (a.expires_at is null or a.expires_at > ?)
                order by a.granted_at, a.id
                """, (rs, row) -> assignment(rs, catalogVersion, evaluatedAt),
                actorId, catalogVersion, at, at);

        Map<UUID, OperatorRbacReadModels.RoleView> roles = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select distinct r.id, r.code, cr.hierarchy_rank
                from operations.operator_role_assignments a
                join operations.rbac_catalog_roles cr
                  on cr.catalog_version = a.catalog_version and cr.role_id = a.role_id
                 and cr.role_status = 'ACTIVE'
                join operations.roles r on r.id = a.role_id
                where a.operator_account_id = ? and a.catalog_version = ?
                  and a.granted_at <= ? and a.revoked_at is null
                  and (a.expires_at is null or a.expires_at > ?)
                order by r.code, r.id
                """, actorId, catalogVersion, at, at)) {
            UUID id = (UUID) row.get("id");
            roles.put(id, new OperatorRbacReadModels.RoleView(
                    id, row.get("code").toString(), ((Number) row.get("hierarchy_rank")).intValue()));
        }

        List<OperatorRbacReadModels.PermissionView> permissions = jdbc.query("""
                select distinct p.id, p.code
                from operations.operator_role_assignments a
                join operations.rbac_catalog_roles cr
                  on cr.catalog_version = a.catalog_version and cr.role_id = a.role_id
                 and cr.role_status = 'ACTIVE'
                join operations.rbac_catalog_role_permissions rp
                  on rp.catalog_version = a.catalog_version and rp.role_id = a.role_id
                join operations.rbac_catalog_permissions cp
                  on cp.catalog_version = rp.catalog_version and cp.permission_id = rp.permission_id
                 and cp.permission_status = 'ACTIVE'
                join operations.permissions p on p.id = rp.permission_id
                where a.operator_account_id = ? and a.catalog_version = ?
                  and a.granted_at <= ? and a.revoked_at is null
                  and (a.expires_at is null or a.expires_at > ?)
                order by p.code, p.id
                """, (rs, row) -> new OperatorRbacReadModels.PermissionView(
                rs.getObject(1, UUID.class), rs.getString(2)), actorId, catalogVersion, at, at);
        Set<UUID> permissionIds = new LinkedHashSet<>();
        permissions.forEach(permission -> permissionIds.add(permission.id()));
        var self = new OperatorRbacReadModels.SelfView(actorId, catalogVersion, false, null, lastMfa,
                new ArrayList<>(roles.values()), permissions, assignments);
        return new OperatorRbacReadModels.ActorState(true, catalogVersion, permissionIds, self);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<OperatorRbacReadModels.CatalogView> loadCatalog(
            String catalogVersion, Instant evaluatedAt) {
        if (!isActiveCatalog(catalogVersion)) return Optional.empty();
        List<OperatorRbacReadModels.RoleView> roles = jdbc.query("""
                select r.id, r.code, cr.hierarchy_rank
                from operations.rbac_catalog_roles cr
                join operations.roles r on r.id = cr.role_id
                where cr.catalog_version = ? and cr.role_status = 'ACTIVE'
                order by r.code, r.id
                """, (rs, row) -> new OperatorRbacReadModels.RoleView(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3)), catalogVersion);
        List<OperatorRbacReadModels.PermissionView> permissions = jdbc.query("""
                select p.id, p.code
                from operations.rbac_catalog_permissions cp
                join operations.permissions p on p.id = cp.permission_id
                where cp.catalog_version = ? and cp.permission_status = 'ACTIVE'
                order by p.code, p.id
                """, (rs, row) -> new OperatorRbacReadModels.PermissionView(
                rs.getObject(1, UUID.class), rs.getString(2)), catalogVersion);
        List<OperatorRbacReadModels.RolePermissionView> mappings = jdbc.query("""
                select rp.role_id, rp.permission_id, rp.delegable
                from operations.rbac_catalog_role_permissions rp
                join operations.rbac_catalog_roles cr
                  on cr.catalog_version = rp.catalog_version and cr.role_id = rp.role_id
                 and cr.role_status = 'ACTIVE'
                join operations.rbac_catalog_permissions cp
                  on cp.catalog_version = rp.catalog_version and cp.permission_id = rp.permission_id
                 and cp.permission_status = 'ACTIVE'
                where rp.catalog_version = ?
                order by rp.role_id, rp.permission_id
                """, (rs, row) -> new OperatorRbacReadModels.RolePermissionView(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBoolean(3)),
                catalogVersion);
        return Optional.of(new OperatorRbacReadModels.CatalogView(
                catalogVersion, roles, permissions, mappings));
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<OperatorRbacReadModels.AssignmentsView> loadAssignments(
            UUID targetOperatorId, String catalogVersion, Instant evaluatedAt) {
        Integer exists = jdbc.queryForObject(
                "select count(*) from operations.operator_accounts where id = ?", Integer.class,
                targetOperatorId);
        if (exists == null || exists != 1) return Optional.empty();
        List<OperatorRbacReadModels.AssignmentView> assignments = jdbc.query("""
                select a.id, a.operator_account_id, a.role_id, r.code, a.catalog_version,
                       a.granted_at, a.expires_at, a.revoked_at, a.revocation_reason_code
                from operations.operator_role_assignments a
                join operations.roles r on r.id = a.role_id
                where a.operator_account_id = ?
                order by a.granted_at, a.id
                """, (rs, row) -> assignment(rs, catalogVersion, evaluatedAt), targetOperatorId);
        return Optional.of(new OperatorRbacReadModels.AssignmentsView(targetOperatorId, assignments));
    }

    @Override
    @Transactional
    public void recordDecision(OperatorRbacReadModels.AuditDecision decision) {
        String request = write(Map.of(
                "kind", decision.kind().name(),
                "targetOperatorId", decision.targetOperatorId().toString()));
        String response = write(Map.of("code", decision.responseCode()));
        String state = write(Map.of("readOnly", true));
        Map<String, Object> evidenceValue = new LinkedHashMap<>();
        evidenceValue.put("expectedCatalogVersion", decision.expectedCatalogVersion());
        evidenceValue.put("resolvedCatalogVersion", decision.resolvedCatalogVersion());
        evidenceValue.put("decisionStatus", decision.decisionStatus().name());
        evidenceValue.put("requiredPermissionId", decision.requiredPermissionId() == null
                ? null : decision.requiredPermissionId().toString());
        evidenceValue.put("effectivePermissionIds", decision.effectivePermissionIds().stream()
                .map(UUID::toString).sorted().toList());
        evidenceValue.put("mfaRequired", decision.mfaRequired());
        evidenceValue.put("currentMfa", decision.currentMfa());
        String evidence = write(evidenceValue);
        boolean succeeded = decision.decisionStatus() == OperatorRbacReadModels.DecisionStatus.SUCCEEDED;
        int responseStatus = succeeded ? 200 : rejectedStatus(decision.responseCode());
        String requestedCatalog = decision.expectedCatalogVersion() != null
                ? decision.expectedCatalogVersion()
                : decision.resolvedCatalogVersion() != null
                        ? decision.resolvedCatalogVersion() : "unavailable";
        String resolvedCatalog = succeeded
                || requestedCatalog.equals(decision.resolvedCatalogVersion())
                ? decision.resolvedCatalogVersion() : null;
        String idempotencyKey = "operator-rbac-read:" + UUID.randomUUID();
        jdbc.update("""
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id,
                     reason_code, correlation_id, idempotency_key, rbac_catalog_version,
                     resolved_rbac_catalog_version, request_hash, decision_status,
                     response_status, response_code, request_document, response_document,
                     before_document, after_document, evidence_document,
                     before_hash, after_hash, evidence_hash, occurred_at)
                values (?, 'OPERATOR', ?, ?, 'OPERATOR_RBAC', ?, ?, ?, ?, ?, ?,
                        encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex'), ?, ?, ?,
                        cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                        cast(? as jsonb),
                        encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex'),
                        encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex'),
                        encode(digest(cast(? as jsonb)::text, 'sha256'), 'hex'), ?)
                """, UUID.randomUUID(), decision.actorId(),
                "OPERATOR_RBAC_READ_" + decision.kind().name(), decision.targetOperatorId(),
                decision.responseCode(), decision.correlationId(), idempotencyKey,
                requestedCatalog, resolvedCatalog, request,
                succeeded ? "SUCCEEDED" : "REJECTED", responseStatus, decision.responseCode(),
                request, response, state, state, evidence, state, state, evidence,
                Timestamp.from(decision.evaluatedAt()));
    }

    private String activeCatalogVersion() {
        List<String> values = jdbc.queryForList("""
                select catalog_version from operations.rbac_catalog_versions
                where status = 'ACTIVE'
                """, String.class);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private boolean isActiveCatalog(String catalogVersion) {
        Integer count = jdbc.queryForObject("""
                select count(*) from operations.rbac_catalog_versions
                where catalog_version = ? and status = 'ACTIVE'
                """, Integer.class, catalogVersion);
        return count != null && count == 1;
    }

    private static OperatorRbacReadModels.AssignmentView assignment(
            ResultSet rs, String activeCatalogVersion, Instant evaluatedAt) throws SQLException {
        UUID id = rs.getObject(1, UUID.class);
        UUID operator = rs.getObject(2, UUID.class);
        UUID role = rs.getObject(3, UUID.class);
        String roleCode = rs.getString(4);
        String catalog = rs.getString(5);
        Instant granted = rs.getTimestamp(6).toInstant();
        Instant expires = rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant();
        Instant revoked = rs.getTimestamp(8) == null ? null : rs.getTimestamp(8).toInstant();
        OperatorRbacReadModels.AssignmentStatus status;
        if (revoked != null) status = OperatorRbacReadModels.AssignmentStatus.REVOKED;
        else if (catalog == null) status = OperatorRbacReadModels.AssignmentStatus.UNMIGRATED;
        else if (granted.isAfter(evaluatedAt)) status = OperatorRbacReadModels.AssignmentStatus.FUTURE;
        else if (expires != null && !expires.isAfter(evaluatedAt)) {
            status = OperatorRbacReadModels.AssignmentStatus.EXPIRED;
        } else if (!catalog.equals(activeCatalogVersion)) {
            status = OperatorRbacReadModels.AssignmentStatus.STALE_CATALOG;
        } else status = OperatorRbacReadModels.AssignmentStatus.ACTIVE;
        return new OperatorRbacReadModels.AssignmentView(id, operator, role, roleCode, catalog,
                granted, expires, revoked, rs.getString(9), status);
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        return null;
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) {
            throw new IllegalStateException("operator RBAC read audit serialization failed", exception);
        }
    }

    private static int rejectedStatus(String code) {
        if (code.endsWith("NOT_FOUND")) return 404;
        if (code.contains("CATALOG_VERSION")) return 409;
        return 403;
    }
}
