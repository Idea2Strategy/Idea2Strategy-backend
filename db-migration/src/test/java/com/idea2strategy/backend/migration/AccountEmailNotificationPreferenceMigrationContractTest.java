package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountEmailNotificationPreferenceMigrationContractTest {
    private static final String MIGRATION =
            "db/migration/V20260809120000__backend_account_email_notification_preference.sql";

    @Test
    void storesAnAccountOwnedPreferenceThatDefaultsToDisabled() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(MIGRATION);
        assertNotNull(resource, "account email notification preference migration must be checked in");
        var sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);

        DatabaseAccessPolicy.verifyMigrationOwnership(MigrationOwner.BACKEND, sql);
        assertTrue(sql.contains("operations.account_email_notification_preferences"));
        assertTrue(sql.contains("account_id uuid PRIMARY KEY"));
        assertTrue(sql.contains("enabled boolean NOT NULL DEFAULT false"));
        assertTrue(sql.contains("REFERENCES identity.accounts(id) ON DELETE CASCADE"));
    }
}
