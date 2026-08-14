package com.idea2strategy.backend.persistence.operatorbootstrap;

import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapManifest;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapPort;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapRejectedException;
import com.idea2strategy.backend.application.operatorbootstrap.OperatorBootstrapResult;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcOperatorBootstrapAdapter implements OperatorBootstrapPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OperatorBootstrapCredential credential;

    public JdbcOperatorBootstrapAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, null);
    }

    public JdbcOperatorBootstrapAdapter(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            OperatorBootstrapCredential credential) {
        this.jdbc = jdbc;
        this.credential = credential;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    @Override
    public OperatorBootstrapResult apply(OperatorBootstrapManifest manifest, String manifestHash) {
        try {
            return transactions.execute(status -> applyInTransaction(manifest, manifestHash));
        } catch (OperatorBootstrapRejectedException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new OperatorBootstrapRejectedException("OPERATOR_BOOTSTRAP_TRANSACTION_FAILED", exception);
        }
    }

    private OperatorBootstrapResult applyInTransaction(OperatorBootstrapManifest manifest, String manifestHash) {
        jdbc.execute("select pg_advisory_xact_lock(hashtextextended('operator-bootstrap', 0))");
        String databaseRole = jdbc.queryForObject("select current_user", String.class);
        if (!manifest.expectedDatabaseRole().equals(databaseRole)) {
            reject("OPERATOR_BOOTSTRAP_DATABASE_ROLE_MISMATCH");
        }

        List<Map<String, Object>> byKey = jdbc.queryForList("""
                select bootstrap_key, manifest_hash, catalog_version, operator_account_id,
                       operator_role_assignment_id, correlation_id, audit_event_id, applied_at
                from operations.operator_bootstrap_receipts where bootstrap_key = ?
                """, manifest.bootstrapKey());
        if (!byKey.isEmpty()) {
            Map<String, Object> receipt = byKey.getFirst();
            if (!manifestHash.equals(receipt.get("manifest_hash"))) {
                reject("OPERATOR_BOOTSTRAP_CONFLICTING_REPLAY");
            }
            return result(true, receipt);
        }
        if (count("select count(*) from operations.operator_bootstrap_receipts where manifest_hash = ?",
                manifestHash) != 0) {
            reject("OPERATOR_BOOTSTRAP_MANIFEST_ALREADY_CONSUMED");
        }
        requireExpectedEmptyState(manifest);
        if (credential == null) reject("OPERATOR_BOOTSTRAP_CREDENTIAL_REQUIRED");
        Instant appliedAt = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();

        jdbc.update("insert into operations.rbac_catalog_versions "
                        + "(catalog_version, content_hash, status) values (?, ?, 'DRAFT')",
                manifest.catalogVersion(), manifest.catalogContentHash());
        for (var role : manifest.roles()) {
            jdbc.update("insert into operations.roles (id, code, hierarchy_rank, status) "
                            + "values (?, ?, ?, 'ACTIVE')",
                    role.id(), role.code(), role.hierarchyRank());
            jdbc.update("insert into operations.rbac_catalog_roles "
                            + "(catalog_version, role_id, hierarchy_rank, role_status) values (?, ?, ?, 'ACTIVE')",
                    manifest.catalogVersion(), role.id(), role.hierarchyRank());
        }
        for (var permission : manifest.permissions()) {
            jdbc.update("insert into operations.permissions (id, code, description, sensitivity) values (?, ?, ?, ?) "
                            + "on conflict (id) do nothing",
                    permission.id(), permission.code(), permission.description(), permission.sensitivity());
            jdbc.update("insert into operations.rbac_catalog_permissions "
                            + "(catalog_version, permission_id, permission_status) values (?, ?, 'ACTIVE')",
                    manifest.catalogVersion(), permission.id());
        }
        for (var mapping : manifest.rolePermissions()) {
            jdbc.update("insert into operations.role_permissions (role_id, permission_id) values (?, ?)",
                    mapping.roleId(), mapping.permissionId());
            jdbc.update("insert into operations.rbac_catalog_role_permissions "
                            + "(catalog_version, role_id, permission_id, delegable) values (?, ?, ?, ?)",
                    manifest.catalogVersion(), mapping.roleId(), mapping.permissionId(), mapping.delegable());
        }
        jdbc.update("update operations.rbac_catalog_versions set status = 'ACTIVE', activated_at = ? "
                        + "where catalog_version = ? and status = 'DRAFT'",
                Timestamp.from(appliedAt), manifest.catalogVersion());
        jdbc.update("insert into operations.operator_accounts (id, status, created_at) values (?, 'ACTIVE', ?)",
                manifest.operatorAccountId(), Timestamp.from(appliedAt));
        jdbc.update("""
                insert into operations.operator_login_credentials
                  (operator_account_id, login_name, password_hash, password_parameters, password_version,
                   credential_version, totp_ciphertext, totp_nonce, totp_key_version, totp_enrolled_at,
                   password_changed_at, created_at, updated_at)
                values (?, ?, ?, cast(? as jsonb), ?, 1, ?, ?, ?, ?, ?, ?, ?)
                """, manifest.operatorAccountId(), manifest.loginName(), credential.passwordHash(),
                credential.passwordParameters(), credential.passwordVersion(), credential.totpCiphertext(),
                credential.totpNonce(), credential.totpKeyVersion(), Timestamp.from(appliedAt),
                Timestamp.from(appliedAt), Timestamp.from(appliedAt), Timestamp.from(appliedAt));
        jdbc.update("insert into operations.operator_role_assignments "
                        + "(id, operator_account_id, role_id, catalog_version, granted_by_operator_id, granted_at) "
                        + "values (?, ?, ?, ?, ?, ?)",
                manifest.operatorRoleAssignmentId(), manifest.operatorAccountId(), manifest.initialRoleId(),
                manifest.catalogVersion(), manifest.operatorAccountId(), Timestamp.from(appliedAt));
        jdbc.update("""
                with documents as (
                    select
                      jsonb_build_object('bootstrapKey', ?::text, 'manifestHash', ?::text,
                                         'catalogVersion', ?::text,
                                         'catalogContentHash', ?::text,
                                         'expectedDatabaseRole', ?::text,
                                         'grantProvenance', ?::text) as request_document,
                      jsonb_build_object('operatorAccountId', ?::text,
                                         'operatorRoleAssignmentId', ?::text,
                                         'catalogVersion', ?::text,
                                         'credentialVersion', 1,
                                         'status', 'ACTIVE') as response_document,
                      jsonb_build_object('databaseRole', current_user,
                                         'deploymentActorId', ?::text,
                                         'grantProvenance', ?::text,
                                         'technicalGrantorOperatorId', ?::text,
                                         'grantMode', 'BOOTSTRAP_DEPLOYMENT') as evidence_document
                )
                insert into operations.audit_events
                    (id, actor_type, actor_id, action_type, target_domain, target_id, reason_code,
                     correlation_id, idempotency_key, before_hash, after_hash, evidence_hash,
                     request_hash, decision_status, response_status, response_code,
                     request_document, response_document, before_document, after_document,
                     evidence_document, occurred_at)
                select ?, 'DEPLOYMENT', ?, 'OPERATOR_BOOTSTRAP', 'OPERATOR_BOOTSTRAP', ?,
                       'BOOTSTRAP_DEPLOYMENT', ?, ?,
                       encode(digest('{}'::jsonb::text, 'sha256'), 'hex'),
                       encode(digest(response_document::text, 'sha256'), 'hex'),
                       encode(digest(evidence_document::text, 'sha256'), 'hex'),
                       encode(digest(request_document::text, 'sha256'), 'hex'),
                       'SUCCEEDED', 200, 'OPERATOR_BOOTSTRAP_APPLIED',
                       request_document, response_document, '{}'::jsonb, response_document,
                       evidence_document, ?
                from documents
                """,
                manifest.bootstrapKey(), manifestHash, manifest.catalogVersion(),
                manifest.catalogContentHash(), manifest.expectedDatabaseRole(), manifest.grantProvenance(),
                manifest.operatorAccountId(), manifest.operatorRoleAssignmentId(), manifest.catalogVersion(),
                manifest.deploymentActorId(), manifest.grantProvenance(), manifest.operatorAccountId(),
                manifest.auditEventId(), manifest.deploymentActorId(), manifest.operatorAccountId(),
                manifest.correlationId(), "operator-bootstrap:" + manifest.bootstrapKey(),
                Timestamp.from(appliedAt));
        jdbc.update("insert into operations.operator_bootstrap_receipts "
                        + "(bootstrap_key, manifest_hash, catalog_version, operator_account_id, "
                        + "operator_role_assignment_id, credential_version, correlation_id, "
                        + "audit_event_id, applied_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                manifest.bootstrapKey(), manifestHash, manifest.catalogVersion(), manifest.operatorAccountId(),
                manifest.operatorRoleAssignmentId(), 1,
                manifest.correlationId(), manifest.auditEventId(), Timestamp.from(appliedAt));
        return new OperatorBootstrapResult(false, manifest.bootstrapKey(), manifestHash, manifest.catalogVersion(),
                manifest.operatorAccountId(), manifest.operatorRoleAssignmentId(), manifest.correlationId(),
                manifest.auditEventId(), appliedAt);
    }

    private void requireExpectedEmptyState(OperatorBootstrapManifest manifest) {
        String[] tables = {
                "operations.rbac_catalog_versions", "operations.roles",
                "operations.role_permissions", "operations.operator_accounts",
                "operations.operator_role_assignments", "operations.operator_bootstrap_receipts"
        };
        for (String table : tables) {
            if (count("select count(*) from " + table) != 0) {
                reject("OPERATOR_BOOTSTRAP_STATE_NOT_EMPTY");
            }
        }
        List<Map<String, Object>> existingPermissions = jdbc.queryForList(
                "select id, code, description, sensitivity from operations.permissions");
        for (Map<String, Object> row : existingPermissions) {
            boolean exact = manifest.permissions().stream().anyMatch(permission ->
                    permission.id().equals(row.get("id")) && permission.code().equals(row.get("code"))
                            && permission.description().equals(row.get("description"))
                            && permission.sensitivity().equals(row.get("sensitivity")));
            if (!exact) reject("OPERATOR_BOOTSTRAP_OUT_OF_MANIFEST");
        }
        for (var permission : manifest.permissions()) {
            boolean conflictingCode = existingPermissions.stream().anyMatch(row ->
                    permission.code().equals(row.get("code")) && !permission.id().equals(row.get("id")));
            if (conflictingCode) reject("OPERATOR_BOOTSTRAP_OUT_OF_MANIFEST");
        }
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0 : value;
    }

    private static OperatorBootstrapResult result(boolean replayed, Map<String, Object> row) {
        return new OperatorBootstrapResult(replayed, row.get("bootstrap_key").toString(),
                row.get("manifest_hash").toString(), row.get("catalog_version").toString(),
                (UUID) row.get("operator_account_id"), (UUID) row.get("operator_role_assignment_id"),
                (UUID) row.get("correlation_id"), (UUID) row.get("audit_event_id"),
                ((Timestamp) row.get("applied_at")).toInstant());
    }

    private static void reject(String code) {
        throw new OperatorBootstrapRejectedException(code);
    }
}
