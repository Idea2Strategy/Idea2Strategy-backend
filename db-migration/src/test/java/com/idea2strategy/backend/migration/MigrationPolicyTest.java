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
                        "V20260801112341__backend_identity_email_auth.sql"),
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
