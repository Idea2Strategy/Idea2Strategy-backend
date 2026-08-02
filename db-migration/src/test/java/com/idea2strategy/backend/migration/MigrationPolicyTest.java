package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationPolicyTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsTheImmutableBaselineAndOrdersOwnedTimestampMigrations() {
        var plan = MigrationPolicy.verifyNames(List.of(
                "V20260801123000__trading_order_events.sql",
                "V1__initial_schema.sql",
                "V20260801120000__backend_identity_accounts.sql"));

        assertEquals(
                List.of(
                        "V1__initial_schema.sql",
                        "V20260801120000__backend_identity_accounts.sql",
                        "V20260801123000__trading_order_events.sql"),
                plan.orderedFileNames());
    }

    @Test
    void rejectsUnknownOwnerDuplicateTimestampAndLegacyPostBaselineVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationPolicy.verifyNames(List.of(
                        "V1__initial_schema.sql",
                        "V20260801120000__unknown_identity_accounts.sql")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationPolicy.verifyNames(List.of(
                        "V1__initial_schema.sql",
                        "V20260801120000__backend_identity_accounts.sql",
                        "V20260801120000__trading_order_events.sql")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationPolicy.verifyNames(List.of(
                        "V1__initial_schema.sql",
                        "V2__identity_accounts.sql")));
        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationPolicy.verifyNames(List.of(
                        "V1__initial_schema.sql",
                        "V20260230090000__backend_identity_accounts.sql")));
    }

    @Test
    void verifiesTheCheckedInMigrationDirectoryAndBaselineChecksum() throws Exception {
        var resource = getClass().getClassLoader().getResource("db/migration");
        var plan = MigrationPolicy.verifyDirectory(java.nio.file.Path.of(resource.toURI()));

        assertEquals(
                List.of(
                        "V1__initial_schema.sql",
                        "V20260801112341__backend_identity_email_auth.sql",
                        "V20260801153000__backend_bot_continuation_deadlines.sql",
                        "V20260802050054__backend_account_preferences_theme.sql",
                        "V20260802060000__backend_account_lifecycle_dormant_status.sql",
                        "V20260802060100__backend_account_lifecycle_contract.sql",
                        "V20260802060200__backend_account_lifecycle_command_receipts.sql",
                        "V20260802060300__backend_oidc_step_up_nonces.sql",
                        "V20260802060400__backend_account_closure_coordination.sql",
                        "V20260802194500__backend_final_leaderboard_unranked_entries.sql",
                        "V20260802213500__backend_room_final_access_grants.sql",
                        "V20260802220000__backend_retention_category_split.sql",
                        "V20260802220100__trading_private_bot_runtime_cleanup.sql",
                        "V20260802220200__backend_retention_execution.sql",
                        "V20260802220300__backtest_competition_owner_anonymization.sql",
                        "V20260802230000__backend_operator_room_permissions.sql",
                        "V20260802231000__backend_leaderboard_result_source_guard.sql",
                        "V20260802231100__backend_transactional_outbox.sql",
                        "V20260802231400__backend_operator_rbac.sql",
                        "V20260802231500__backend_notification_delivery.sql",
                        "V20260802231600__backend_account_sanction_commands.sql"),
                plan.orderedFileNames());
    }

    @Test
    void rejectsAChangedAppliedBaseline() throws Exception {
        Files.writeString(temporaryDirectory.resolve(MigrationPolicy.BASELINE_FILE), "-- changed baseline");

        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationPolicy.verifyDirectory(temporaryDirectory));
    }

    @Test
    void rejectsAnApplicationDdlGrantFromTheCheckedMigrationSet() throws Exception {
        try (var baseline = getClass()
                .getClassLoader()
                .getResourceAsStream("db/migration/" + MigrationPolicy.BASELINE_FILE)) {
            Files.write(temporaryDirectory.resolve(MigrationPolicy.BASELINE_FILE), baseline.readAllBytes());
        }
        Files.writeString(
                temporaryDirectory.resolve("V20260801130000__shared_unsafe_access.sql"),
                "GRANT CREATE ON SCHEMA market_data TO idea2strategy_pipeline;");

        assertThrows(
                IllegalArgumentException.class,
                () -> MigrationPolicy.verifyDirectory(temporaryDirectory));
    }
}
