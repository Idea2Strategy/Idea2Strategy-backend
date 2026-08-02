package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AccountPreferencesMigrationContractTest {

    private static final String MIGRATION =
            "db/migration/V20260802050054__backend_account_preferences_theme.sql";

    @Test
    void declaresTheApprovedThemeContractAndRepairsMissingPreferences() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(MIGRATION);
        assertNotNull(resource, "A11 account preferences migration must be checked in");
        var sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);

        DatabaseAccessPolicy.verifyMigrationOwnership(MigrationOwner.BACKEND, sql);
        assertTrue(sql.contains("CREATE TYPE identity.theme_preference AS ENUM ('LIGHT', 'DARK', 'SYSTEM')"));
        assertTrue(sql.contains("ADD COLUMN theme_preference identity.theme_preference"));
        assertTrue(sql.contains("SET theme_preference = 'SYSTEM'"));
        assertTrue(sql.contains("ALTER COLUMN theme_preference SET DEFAULT 'SYSTEM'"));
        assertTrue(sql.contains("ALTER COLUMN theme_preference SET NOT NULL"));
        assertTrue(sql.contains("INSERT INTO identity.account_preferences"));
        assertTrue(sql.contains("'ko'"));
        assertTrue(sql.contains("'America/New_York'"));
        assertTrue(sql.contains("WHERE preferences.account_id IS NULL"));
    }
}
