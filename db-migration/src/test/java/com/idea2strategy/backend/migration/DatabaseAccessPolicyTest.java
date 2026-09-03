package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
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
        assertEquals(MigrationOwner.PIPELINE, ownership.ownerOf("storage", "objects"));
        assertTrue(ownership.tables().size() > 100);
    }

    @Test
    void generatesNoLoginRuntimeRolesAndExactTablePrivilegesFromThePolicy() throws Exception {
        String baseline;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V1__initial_schema.sql")) {
            baseline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        var sql = DatabaseAccessPolicy.runtimeGrantSql(List.of(baseline));

        assertTrue(sql.contains("CREATE ROLE idea2strategy_backend NOLOGIN"));
        assertTrue(sql.contains("ALTER ROLE idea2strategy_pipeline NOLOGIN NOCREATEDB NOCREATEROLE NOINHERIT"));
        assertFalse(sql.contains("ALTER ROLE idea2strategy_pipeline NOLOGIN NOSUPERUSER"));
        assertTrue(sql.contains("rolsuper OR rolreplication OR rolbypassrls"));
        assertTrue(sql.contains("application group role idea2strategy_pipeline has forbidden privileged attributes"));
        assertTrue(sql.contains("application group role idea2strategy_pipeline must not own database objects"));
        assertTrue(sql.contains("GRANT SELECT, INSERT, UPDATE ON TABLE \"market_data\".\"dataset_manifests\" TO idea2strategy_pipeline"));
        assertTrue(sql.contains("GRANT SELECT, INSERT ON TABLE \"storage\".\"objects\" TO idea2strategy_pipeline"));
        assertTrue(sql.contains("GRANT SELECT ON TABLE \"operations\".\"operator_accounts\" TO idea2strategy_pipeline"));
        assertTrue(sql.contains("GRANT SELECT ON TABLE \"operations\".\"audit_events\" TO idea2strategy_pipeline"));
        assertFalse(sql.contains("GRANT SELECT, INSERT ON TABLE \"operations\".\"audit_events\" TO idea2strategy_pipeline"));
        assertFalse(sql.contains("GRANT DELETE ON TABLE \"market_data\".\"dataset_manifests\" TO idea2strategy_pipeline"));
        assertFalse(sql.contains("GRANT INSERT ON TABLE \"backtest\".\"runs\" TO idea2strategy_pipeline"));
        assertFalse(sql.contains("GRANT CREATE ON SCHEMA"));
    }

    @Test
    void keepsOneOwnershipEntryWhenAnUpgradeConditionallyRedeclaresABaselineTable() {
        var ownership = DatabaseAccessPolicy.ownershipManifest(List.of(
                "CREATE TABLE market_data.corporate_actions (id uuid PRIMARY KEY);",
                "DO $migration$ BEGIN CREATE TABLE market_data.corporate_actions (id uuid PRIMARY KEY); END $migration$;"));

        assertEquals(MigrationOwner.PIPELINE, ownership.ownerOf("market_data", "corporate_actions"));
        assertEquals(1, ownership.tables().size());
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
        assertFalse(DatabaseAccessPolicy.allows(
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
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.PIPELINE,
                DatabaseAccessPolicy.Access.READ,
                "operations",
                "operator_accounts"));
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.PIPELINE,
                DatabaseAccessPolicy.Access.READ,
                "operations",
                "audit_events"));
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.PIPELINE,
                DatabaseAccessPolicy.Access.UPDATE,
                "operations",
                "audit_events"));
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

        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKEND,
                DatabaseAccessPolicy.Access.INSERT,
                "backtest",
                "runs"));
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKEND,
                DatabaseAccessPolicy.Access.UPDATE,
                "backtest",
                "runs"));
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKEND,
                DatabaseAccessPolicy.Access.INSERT,
                "backtest",
                "run_attempts"));

        for (var role : DatabaseAccessPolicy.ApplicationRole.values()) {
            assertFalse(DatabaseAccessPolicy.allows(
                    role, DatabaseAccessPolicy.Access.DDL, "market_data", "dataset_manifests"));
        }
    }

    @Test
    void keepsProtectedBacktestAttemptAndStoragePublicationWritesBehindCapabilities()
            throws Exception {
        String baseline;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V1__initial_schema.sql")) {
            baseline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        var sql = DatabaseAccessPolicy.runtimeGrantSql(List.of(baseline));

        for (var access : List.of(DatabaseAccessPolicy.Access.INSERT, DatabaseAccessPolicy.Access.UPDATE)) {
            assertFalse(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            access,
                            "backtest",
                            "run_attempts"),
                    "attempt state must be changed only by a fenced database capability: " + access);
            assertFalse(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            access,
                            "storage",
                            "objects"),
                    "publication state must be changed only by a fenced database capability: " + access);
        }
        assertTrue(sql.contains(
                "GRANT SELECT ON TABLE \"backtest\".\"run_attempts\" TO idea2strategy_backtest;"));
        assertTrue(sql.contains(
                "GRANT SELECT ON TABLE \"storage\".\"objects\" TO idea2strategy_backtest;"));
        assertFalse(sql.contains(
                "GRANT SELECT, INSERT, UPDATE ON TABLE \"backtest\".\"run_attempts\" "
                        + "TO idea2strategy_backtest;"));
        assertFalse(sql.contains(
                "GRANT SELECT, INSERT, UPDATE ON TABLE \"storage\".\"objects\" "
                        + "TO idea2strategy_backtest;"));

        for (var signature : List.of(
                "\"backtest\".\"claim_run_attempt\"(uuid, text, text, bigint)",
                "\"backtest\".\"heartbeat_run_attempt\"(uuid, uuid, bigint)",
                "\"backtest\".\"close_run_attempt\"(uuid, uuid, text, text, text, boolean)",
                "\"backtest\".\"recover_expired_run_attempt\"(uuid, text, text)",
                "\"storage\".\"register_backtest_object\"(jsonb)",
                "\"storage\".\"transition_backtest_object\"(uuid, text, timestamp with time zone)")) {
            assertTrue(
                    sql.contains("GRANT EXECUTE ON FUNCTION " + signature + " TO idea2strategy_backtest;"),
                    "the runtime role needs the narrow capability " + signature);
            assertTrue(
                    sql.contains("REVOKE ALL ON FUNCTION " + signature + " FROM PUBLIC;"),
                    "the narrow capability must not remain executable by PUBLIC: " + signature);
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
    @Test
    void grantsTheBacktestRoleTheOperationsAccessItsRequestIntakeExecutes() {
        // backtest_request_intake claims a transactional receipt before running anything. Without
        // operations access the claim raises InsufficientPrivilege, the exception escapes poll_once,
        // and the SQS message is retried forever instead of dead-lettered — so backtest.runs stays
        // QUEUED with zero attempts and nothing reports why (backend #246).
        //
        // This was unreachable until backend #243: handle() checks the transport envelope before it
        // claims, and every message failed the envelope check first.
        for (var access : List.of(
                DatabaseAccessPolicy.Access.READ,
                DatabaseAccessPolicy.Access.INSERT,
                DatabaseAccessPolicy.Access.UPDATE)) {
            assertTrue(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            access,
                            "operations",
                            "outbox_consumer_receipts"),
                    "the intake selects, inserts and updates its own receipts: " + access);
        }
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.READ,
                "operations",
                "outbox_messages"));

        // The intake never deletes a receipt. The other three roles hold DELETE on this table; the
        // backtest role must not inherit it just because the rule was widened.
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.DELETE,
                "operations",
                "outbox_consumer_receipts"));
        // Reading the outbox is enough; the consumer never writes the producer's rows.
        assertFalse(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.UPDATE,
                "operations",
                "outbox_messages"));
        // Widening operations must not hand over the rest of the schema.
        for (var table : List.of("audit_events", "operator_accounts", "cases")) {
            assertFalse(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            DatabaseAccessPolicy.Access.READ,
                            "operations",
                            table),
                    "the intake has no reason to read operations." + table);
        }
    }

    @Test
    void grantsTheBatchRoleTheWritesItsScheduledJobsPerform() {
        // Derived from the write statements of the six adapters backend-batch imports. Without these
        // the room schedule transition is refused every ten seconds, a room never leaves DRAFT, and
        // the whole competition lane stalls (backend #246).
        //
        // bot.bots and competition.rooms need UPDATE for a second reason: these adapters take
        // `... for update`, and PostgreSQL requires UPDATE on every table a FOR UPDATE names. That is
        // the same trap as #241 with the opposite fix — there the lock was unnecessary and was
        // removed; here the locks are needed, so the privilege must match.
        //
        // bot.continuation_deadlines is the third instance of that trap and the clearest one: no batch
        // adapter updates it, yet PrivateContinuationTransitionJooqAdapter selects it `for update` and
        // BotStopCommandJooqAdapter names it in `for update of b, d`. A privilege table derived from
        // UPDATE statements alone would miss it, so it is derived from the locks as well (#251).
        for (var target : List.of(
                new DatabaseAccessPolicy.QualifiedTable("competition", "rooms"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "participations"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "backtest_period_runs"),
                new DatabaseAccessPolicy.QualifiedTable("bot", "bots"),
                new DatabaseAccessPolicy.QualifiedTable("bot", "continuation_deadlines"))) {
            assertTrue(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BATCH,
                            DatabaseAccessPolicy.Access.UPDATE,
                            target.schema(),
                            target.table()),
                    "batch updates " + target.schema() + "." + target.table());
        }
        for (var target : List.of(
                new DatabaseAccessPolicy.QualifiedTable("competition", "room_events"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "participation_events"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "backtest_period_runs"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "backtest_aggregate_results"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "leaderboard_snapshots"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "leaderboard_entries"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "room_final_access_grants"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "live_evaluation_segments"),
                new DatabaseAccessPolicy.QualifiedTable("bot", "continuation_deadlines"),
                new DatabaseAccessPolicy.QualifiedTable("backtest", "runs"))) {
            assertTrue(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BATCH,
                            DatabaseAccessPolicy.Access.INSERT,
                            target.schema(),
                            target.table()),
                    "batch inserts into " + target.schema() + "." + target.table());
        }

        // No adapter deletes. Keep the widening from turning into blanket write access.
        for (var target : List.of(
                new DatabaseAccessPolicy.QualifiedTable("competition", "rooms"),
                new DatabaseAccessPolicy.QualifiedTable("bot", "bots"),
                new DatabaseAccessPolicy.QualifiedTable("bot", "continuation_deadlines"),
                new DatabaseAccessPolicy.QualifiedTable("backtest", "runs"))) {
            assertFalse(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BATCH,
                            DatabaseAccessPolicy.Access.DELETE,
                            target.schema(),
                            target.table()),
                    "no batch adapter deletes from " + target.schema() + "." + target.table());
        }
        // Tables the batch only reads must stay read-only. identity.accounts left this list on
        // 2026-08-09: the account-closure job the batch application wires locks that row with
        // `select ... for update` while finalizing a CLOSING account, so asserting it read-only was
        // asserting that a job in this repository could not run (#456).
        for (var target : List.of(
                new DatabaseAccessPolicy.QualifiedTable("competition", "scoring_template_versions"),
                new DatabaseAccessPolicy.QualifiedTable("competition", "room_schedules"),
                new DatabaseAccessPolicy.QualifiedTable("identity", "account_preferences"),
                new DatabaseAccessPolicy.QualifiedTable("strategy", "strategies"))) {
            assertFalse(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BATCH,
                            DatabaseAccessPolicy.Access.UPDATE,
                            target.schema(),
                            target.table()),
                    "batch has no write path into " + target.schema() + "." + target.table());
        }
    }
    @Test
    void grantsTheBacktestRoleTheBotReadsItsExecutorPerforms() {
        // The worker resolves the compiled plan and the run owner before executing anything:
        //   SELECT plan_document   FROM bot.launch_contract_plans WHERE plan_checksum = :checksum
        //   SELECT owner_account_id FROM bot.bots                 WHERE id = :bot_id ...
        // Without these the handler dies with permission denied for schema bot, which is what
        // hjcud's controlled INT03 reproduction recorded as
        // failure_code=HANDLER_ERROR:ProgrammingError on run cfca9ae2 (root #447).
        for (var table : List.of("launch_contract_plans", "bots")) {
            assertTrue(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            DatabaseAccessPolicy.Access.READ,
                            "bot",
                            table),
                    "the backtest executor reads bot." + table);
        }
    }

    @Test
    void grantsTheBacktestRoleOnlyCapabilityBasedStorageObjectPublication() throws Exception {
        // The worker needs to read object metadata, but staging and promotion now cross narrow,
        // attempt-fenced SECURITY DEFINER capabilities. Direct table writes could otherwise forge
        // cleanup ownership or publish unverified bytes.
        assertTrue(DatabaseAccessPolicy.allows(
                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                DatabaseAccessPolicy.Access.READ,
                "storage",
                "objects"));
        for (var access : List.of(
                DatabaseAccessPolicy.Access.INSERT,
                DatabaseAccessPolicy.Access.UPDATE,
                DatabaseAccessPolicy.Access.DELETE)) {
            assertFalse(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            access,
                            "storage",
                            "objects"),
                    "storage objects must change only through fenced capabilities: " + access);
        }

        // storage.objects is the only table in the schema today, so the rule being table-scoped rather
        // than schema-scoped is only observable against a name that does not exist yet. Assert it here:
        // the next storage table must be granted deliberately, not inherited from this change.
        for (var table : List.of("buckets", "object_replicas", "retention_holds")) {
            for (var access : DatabaseAccessPolicy.Access.values()) {
                assertFalse(
                        DatabaseAccessPolicy.allows(
                                DatabaseAccessPolicy.ApplicationRole.BACKTEST, access, "storage", table),
                        access + " on storage." + table + " is not part of registering a detail object");
            }
        }

        // The privilege the deployed database is asked for comes from this generated statement, so the
        // statement itself is asserted rather than only the predicate that produces it.
        String baseline;
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/V1__initial_schema.sql")) {
            baseline = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var sql = DatabaseAccessPolicy.runtimeGrantSql(List.of(baseline));

        assertTrue(
                sql.contains("GRANT SELECT ON TABLE \"storage\".\"objects\" "
                        + "TO idea2strategy_backtest;"),
                "the runtime grants retain the metadata read path");
        assertFalse(
                sql.contains("GRANT SELECT, INSERT, UPDATE ON TABLE \"storage\".\"objects\" "
                        + "TO idea2strategy_backtest;"),
                "the worker must not receive direct publication writes");
        assertTrue(
                sql.contains("GRANT EXECUTE ON FUNCTION "
                        + "\"storage\".\"prepare_backtest_object_cleanup\"(jsonb) "
                        + "TO idea2strategy_backtest;"),
                "cleanup must be exposed only as the narrow function capability");
        assertTrue(
                sql.contains("REVOKE ALL ON FUNCTION "
                        + "\"storage\".\"prepare_backtest_object_cleanup\"(jsonb) FROM PUBLIC;"));
        assertTrue(
                sql.contains("GRANT EXECUTE ON FUNCTION "
                        + "\"storage\".\"reissue_backtest_object_cleanup\"(jsonb, text) "
                        + "TO idea2strategy_backtest;"),
                "successor recovery must be exposed only as the narrow reissue capability");
        assertTrue(
                sql.contains("REVOKE ALL ON FUNCTION "
                        + "\"storage\".\"reissue_backtest_object_cleanup\"(jsonb, text) FROM PUBLIC;"));
        assertTrue(
                sql.contains("GRANT EXECUTE ON FUNCTION "
                        + "\"storage\".\"register_backtest_object\"(jsonb) "
                        + "TO idea2strategy_backtest;"),
                "staging must be exposed only as the narrow registration capability");
        assertTrue(
                sql.contains("GRANT EXECUTE ON FUNCTION "
                        + "\"storage\".\"transition_backtest_object\"(uuid, text, timestamp with time zone) "
                        + "TO idea2strategy_backtest;"),
                "verification must be exposed only as the narrow transition capability");
        for (var role : List.of("backend", "batch", "trading", "pipeline")) {
            assertFalse(
                    sql.contains("GRANT EXECUTE ON FUNCTION "
                            + "\"storage\".\"prepare_backtest_object_cleanup\"(jsonb) "
                            + "TO idea2strategy_" + role + ";"),
                    role + " must not receive the backtest cleanup capability");
            assertFalse(
                    sql.contains("GRANT EXECUTE ON FUNCTION "
                            + "\"storage\".\"reissue_backtest_object_cleanup\"(jsonb, text) "
                            + "TO idea2strategy_" + role + ";"),
                    role + " must not receive the backtest reissue capability");
        }
        // Exactly one storage statement for this role: widening a privilege must not widen the surface.
        assertEquals(
                1,
                sql.lines()
                        .filter(line -> line.contains("ON TABLE \"storage\".")
                                && line.endsWith("TO idea2strategy_backtest;"))
                        .count(),
                "the backtest role must hold storage privileges on storage.objects and nothing else");
        // The pipeline writes storage rows on its own terms and is not part of this change.
        assertTrue(sql.contains("GRANT SELECT, INSERT ON TABLE \"storage\".\"objects\" TO idea2strategy_pipeline;"));
    }

    @Test
    void keepsTheBacktestRoleOutOfTheRestOfTheBotSchema() {
        // Widening the whole schema would hand the worker the bot lifecycle and its runtime state.
        // It reads two rows to resolve what to execute and who owns it; nothing else.
        for (var table : List.of("flows", "continuation_deadlines", "bot_events", "launch_snapshots",
                "runtime_state_values", "evaluation_runs")) {
            assertFalse(
                    DatabaseAccessPolicy.allows(
                            DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                            DatabaseAccessPolicy.Access.READ,
                            "bot",
                            table),
                    "the backtest executor has no reason to read bot." + table);
        }
        // Read-only: the worker never writes the bot aggregate.
        for (var access : List.of(
                DatabaseAccessPolicy.Access.INSERT,
                DatabaseAccessPolicy.Access.UPDATE,
                DatabaseAccessPolicy.Access.DELETE)) {
            for (var table : List.of("launch_contract_plans", "bots")) {
                assertFalse(
                        DatabaseAccessPolicy.allows(
                                DatabaseAccessPolicy.ApplicationRole.BACKTEST,
                                access,
                                "bot",
                                table),
                        access + " on bot." + table + " is not part of executing a backtest");
            }
        }
    }
}
