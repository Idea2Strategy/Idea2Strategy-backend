package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TransactionalOutboxMigrationContractTest {
    @Test
    void pinsDurableClaimReplayAttemptAndReceiptSchema() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802220400__backend_transactional_outbox.sql")) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String fragment : new String[] {
                "operations.outbox_delivery_status",
                "outbox_claim_state_consistent",
                "operations.outbox_delivery_attempts",
                "runtime_policy_version",
                "LEASE_EXPIRED",
                "operations.outbox_consumer_receipts",
                "consumer_handler_id",
                "outbox_message_replayed_from_unique",
                "guard_outbox_immutable_envelope_before_update"
        }) {
            assertTrue(sql.contains(fragment), "missing migration contract: " + fragment);
        }
    }
}
