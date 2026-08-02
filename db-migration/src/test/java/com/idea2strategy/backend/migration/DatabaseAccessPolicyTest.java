package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DatabaseAccessPolicyTest {

    @Test
    void assignsEveryBaselineTableToOneWriteOwner() throws Exception {
        String baseline;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V1__initial_schema.sql")) {
            baseline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        var ownership = DatabaseAccessPolicy.verifyBaselineOwnership(baseline);

        assertEquals(MigrationOwner.BACKEND, ownership.ownerOf("identity", "accounts"));
        assertEquals(MigrationOwner.BACKEND, ownership.ownerOf("bot", "bots"));
        assertEquals(MigrationOwner.TRADING, ownership.ownerOf("bot", "bot_events"));
        assertEquals(MigrationOwner.TRADING, ownership.ownerOf("trading", "orders"));
        assertEquals(MigrationOwner.BACKTEST, ownership.ownerOf("backtest", "runs"));
        assertEquals(MigrationOwner.PIPELINE, ownership.ownerOf("market_data", "dataset_manifests"));
        assertEquals(MigrationOwner.SHARED, ownership.ownerOf("storage", "objects"));
        assertTrue(ownership.tables().size() > 100);
    }

    @Test
    void limitsPythonRolesToDocumentedReadAndWriteBoundaries() {
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.READ,
                "strategy",
                "strategy_documents"));
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.UPDATE,
                "backtest",
                "runs"));
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.INSERT,
                "storage",
                "objects"));
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.UPDATE,
                "strategy",
                "strategies"));
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.DELETE,
                "backtest",
                "runs"));

        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.PIPELINE,
                DatabaseAccessPolicy.Access.UPDATE,
                "market_data",
                "dataset_manifests"));
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.PIPELINE,
                DatabaseAccessPolicy.Access.READ,
                "storage",
                "objects"));
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.PIPELINE,
                DatabaseAccessPolicy.Access.INSERT,
                "backtest",
                "runs"));
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.PIPELINE,
                DatabaseAccessPolicy.Access.DELETE,
                "market_data",
                "dataset_manifests"));

        for (var role : DatabaseAccessPolicy.ApplicationRole.values()) {
            assertFalse(DatabaseAccessPolicy.allows(
                    role, DatabaseAccessPolicy.Access.DDL, "market_data", "dataset_manifests"));
        }
    }

    @Test
    void rejectsApplicationDdlGrantsInMigrations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseAccessPolicy.verifyNoApplicationDdlGrants(
                        "GRANT CREATE ON SCHEMA market_data TO idea2strategy_pipeline"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseAccessPolicy.verifyNoApplicationDdlGrants(
                        "ALTER SCHEMA backtest OWNER TO idea2strategy_backtest"));
    }

    @Test
    void rejectsMigrationsThatMutateAnotherOwnersTables() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseAccessPolicy.verifyMigrationOwnership(
                        MigrationOwner.PIPELINE,
                        "ALTER TABLE backtest.runs ADD COLUMN unsafe integer"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseAccessPolicy.verifyMigrationOwnership(
                        MigrationOwner.BACKEND,
                        "CREATE INDEX unsafe ON bot.bot_events (bot_id)"));

        DatabaseAccessPolicy.verifyMigrationOwnership(
                MigrationOwner.TRADING,
                "ALTER TABLE bot.bot_events ADD COLUMN broker_sequence bigint");
        DatabaseAccessPolicy.verifyMigrationOwnership(
                MigrationOwner.BACKEND,
                "INSERT INTO identity.auth_providers (id, code) VALUES (1, 'PASSWORD')");
    }

    @Test
    void enforcesOwnershipForTypesAndDestructiveSchemaObjects() {
        DatabaseAccessPolicy.verifyMigrationOwnership(
                MigrationOwner.TRADING,
                "ALTER TYPE trading.reservation_event_type ADD VALUE 'PARTIALLY_CONSUMED'");

        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseAccessPolicy.verifyMigrationOwnership(
                        MigrationOwner.PIPELINE,
                        "ALTER TYPE trading.reservation_event_type ADD VALUE 'UNSAFE'"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseAccessPolicy.verifyMigrationOwnership(
                        MigrationOwner.BACKEND,
                        "DROP TABLE IF EXISTS trading.orders"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DatabaseAccessPolicy.verifyMigrationOwnership(
                        MigrationOwner.TRADING,
                        "DROP VIEW IF EXISTS identity.active_accounts"));
    }
}
