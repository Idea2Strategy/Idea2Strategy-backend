package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NotificationDeliveryMigrationContractTest {
    @Test
    void pinsSourceEvidenceSnapshotPreferenceAndOutboxAttemptLinkage() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802231500__backend_notification_delivery.sql")) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String fragment : new String[] {
                "operations.notification_policies", "policy_version", "source_event_id",
                "source_event_hash", "selected_channels", "correlation_id",
                "notification_source_event_unique", "outbox_message_id",
                "notification_delivery_outbox_attempt_unique"
        }) assertTrue(sql.contains(fragment), "missing migration contract: " + fragment);
    }
}
