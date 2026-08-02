package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountLifecycleMigrationContractTest {

    private static final String DORMANT_MIGRATION =
            "db/migration/V20260802060000__backend_account_lifecycle_dormant_status.sql";
    private static final String LIFECYCLE_MIGRATION =
            "db/migration/V20260802060100__backend_account_lifecycle_contract.sql";
    private static final String RECEIPT_MIGRATION =
            "db/migration/V20260802060200__backend_account_lifecycle_command_receipts.sql";
    private static final String CLOSURE_MIGRATION =
            "db/migration/V20260802060400__backend_account_closure_coordination.sql";

    @Test
    void addsDormantBeforeAnyColumnOrConstraintCanReferenceIt() throws Exception {
        var sql = migration(DORMANT_MIGRATION);
        DatabaseAccessPolicy.verifyMigrationOwnership(MigrationOwner.BACKEND, sql);
        assertTrue(sql.contains("ALTER TYPE identity.account_lifecycle_status"));
        assertTrue(sql.contains("ADD VALUE IF NOT EXISTS 'DORMANT' BEFORE 'CLOSING'"));
    }

    @Test
    void installsTheCanonicalLifecycleEvidenceAndRetentionContract() throws Exception {
        var sql = migration(LIFECYCLE_MIGRATION);
        DatabaseAccessPolicy.verifyMigrationOwnership(MigrationOwner.BACKEND, sql);

        assertTrue(sql.contains("ADD COLUMN lifecycle_version bigint"));
        assertTrue(sql.contains("ADD COLUMN last_lifecycle_event_id uuid"));
        assertTrue(sql.contains("ADD COLUMN last_successful_auth_at timestamptz"));
        assertTrue(sql.contains("ADD COLUMN cancellation_deadline_at timestamptz"));
        assertTrue(sql.contains("cancellation_deadline_at = withdrawal_requested_at + interval '30 days'"));
        assertTrue(sql.contains("CREATE TABLE identity.account_retention_policy_versions"));
        assertTrue(sql.contains("CREATE TABLE identity.account_retention_policy_rules"));
        assertTrue(sql.contains("CREATE TABLE identity.account_retention_obligations"));
        assertTrue(sql.contains("failure_code = 'RETENTION_POLICY_MISSING'"));
        assertTrue(sql.contains("CREATE TABLE identity.account_legal_holds"));
        assertTrue(sql.contains("CREATE TABLE identity.account_identifier_quarantines"));
        assertTrue(sql.contains("reuse_eligible_at = quarantined_at + interval '30 days'"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX account_identifier_quarantine_active_fingerprint_uq"));
        assertTrue(sql.contains("CREATE CONSTRAINT TRIGGER account_lifecycle_projection_head_guard"));
        assertTrue(sql.contains("CREATE CONSTRAINT TRIGGER account_lifecycle_event_chain_guard"));
        assertTrue(sql.contains("predecessor.event_sequence + 1 <> NEW.event_sequence"));
        assertTrue(sql.contains("predecessor.new_status IS DISTINCT FROM NEW.previous_status"));
        assertTrue(sql.contains("CREATE TRIGGER account_lifecycle_events_append_only"));
        assertTrue(sql.contains("previous_event_account_fk"));
        assertTrue(sql.contains("last_lifecycle_event_account_fk"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX account_lifecycle_event_genesis_uq"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX account_legal_hold_active_uq"));
        assertTrue(sql.contains("CREATE TRIGGER account_lifecycle_genesis"));
        assertTrue(sql.contains("A12 cannot infer closing_previous_status"));
    }

    @Test
    void installsImmutableSameAccountCommandReceipts() throws Exception {
        var sql = migration(RECEIPT_MIGRATION);
        DatabaseAccessPolicy.verifyMigrationOwnership(MigrationOwner.BACKEND, sql);

        assertTrue(sql.contains("CREATE TABLE identity.account_lifecycle_command_receipts"));
        assertTrue(sql.contains("PRIMARY KEY (account_id, command_type, idempotency_key)"));
        assertTrue(sql.contains("FOREIGN KEY (account_id, lifecycle_event_id)"));
        assertTrue(sql.contains("REFERENCES identity.account_lifecycle_events (account_id, id)"));
        assertTrue(sql.contains("response_status BETWEEN 100 AND 599"));
        assertTrue(sql.contains("response_document jsonb NOT NULL"));
        assertTrue(sql.contains("CREATE TRIGGER account_lifecycle_command_receipts_immutable"));
    }

    @Test
    void installsFailClosedClosureCoordinationAndApprovedRetentionDefaults() throws Exception {
        var sql = migration(CLOSURE_MIGRATION);
        DatabaseAccessPolicy.verifyMigrationOwnership(MigrationOwner.BACKEND, sql);

        assertTrue(sql.contains("CREATE TABLE identity.account_closure_runs"));
        assertTrue(sql.contains("CREATE TABLE identity.account_closure_readiness"));
        assertTrue(sql.contains("'BOT', 'TRADING', 'COMPETITION', 'NOTIFICATION', 'INTEGRATION'"));
        assertTrue(sql.contains("'FREEZE_REQUESTED', 'FROZEN', 'SETTLEMENT_REQUIRED', 'SETTLED', 'BLOCKED'"));
        assertTrue(sql.contains("CREATE TABLE operations.account_integrations"));
        assertTrue(sql.contains("ALTER COLUMN email_lookup_hmac DROP NOT NULL"));
        assertTrue(sql.contains("'A12-2026-08-02'"));
        assertTrue(sql.contains("'kcrmin'"));
    }

    private String migration(String path) throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(resource, path + " must be checked in");
        return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
}
