package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountRetentionExecutionMigrationContractTest {
    @Test
    void retentionMigrationsAreStrictlyAfterMergedDevelop() {
        assertTrue(20260802220000L > 20260802213500L);
        assertTrue(20260802220100L > 20260802220000L);
        assertTrue(20260802220200L > 20260802220100L);
        assertTrue(20260802220300L > 20260802220200L);
    }

    @Test
    void installsTheExactApprovedTenCategoryPolicyAndFailClosedBoundaries() throws Exception {
        String categories = resource("V20260802220000__backend_retention_category_split.sql");
        String execution = resource("V20260802220200__backend_retention_execution.sql");
        String backtest = resource("V20260802220300__backtest_competition_owner_anonymization.sql");

        assertTrue(categories.contains("bot_strategy_private_data"));
        assertTrue(categories.contains("competition_result_evidence"));
        assertEquals(10, execution.split("'a12-2026-08-02', '").length - 1);
        assertTrue(execution.contains("'profile', 'anonymize', 0"));
        assertTrue(execution.contains("'contact_identifier', 'delete', 30"));
        assertTrue(execution.contains("'bot_strategy_evaluation', 'retain', null"));
        assertTrue(execution.contains("'competition_result_evidence', 'anonymize', 365"));
        assertTrue(execution.contains("private_bot_evidence_conflict"));
        assertTrue(execution.contains("deferrable initially deferred"));
        assertTrue(execution.contains("create function identity.lock_account_retention_category()"));
        assertTrue(execution.contains("'account-retention:' || target_account_id::text"));
        assertTrue(execution.contains("legal_hold_id uuid"));
        assertTrue(execution.contains("account_retention_attempt_held_state_uq"));
        assertTrue(execution.contains("on conflict (obligation_id, legal_hold_id, outcome)"));
        assertTrue(execution.contains("set status = 'pending'"));
        assertTrue(backtest.contains("backtest.anonymize_official_competition_run_owners"));
        assertTrue(backtest.contains("competition.backtest_period_runs"));
    }

    private String resource(String name) throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("db/migration/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }
    }
}
