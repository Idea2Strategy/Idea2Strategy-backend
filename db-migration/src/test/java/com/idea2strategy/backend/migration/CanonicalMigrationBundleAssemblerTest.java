package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalMigrationBundleAssemblerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void assemblesOnlyOwnedCanonicalContributionsInGlobalVersionOrder() throws Exception {
        var central = copyCentralMigrations(temporaryDirectory.resolve("central"));
        var trading = Files.createDirectories(temporaryDirectory.resolve("trading"));
        var pipeline = Files.createDirectories(temporaryDirectory.resolve("pipeline"));
        Files.writeString(
                trading.resolve("V20260802120000__trading_add_execution_marker.sql"),
                "ALTER TABLE trading.orders ADD COLUMN execution_marker text;\n");
        Files.writeString(
                pipeline.resolve("V20260802110000__pipeline_add_manifest_marker.sql"),
                "ALTER TABLE market_data.dataset_manifests ADD COLUMN pipeline_marker text;\n");

        var result = CanonicalMigrationBundleAssembler.assemble(
                central,
                List.of(
                        contribution(MigrationOwner.TRADING, trading, "trading", "bot"),
                        contribution(MigrationOwner.PIPELINE, pipeline, "market_data")),
                temporaryDirectory.resolve("bundle"));

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
                        "V20260802110000__pipeline_add_manifest_marker.sql",
                        "V20260802120000__trading_add_execution_marker.sql",
                        "V20260802194500__backend_final_leaderboard_unranked_entries.sql",
                        "V20260802213500__backend_room_final_access_grants.sql",
                        "V20260802220000__backend_retention_category_split.sql",
                        "V20260802220100__trading_private_bot_runtime_cleanup.sql",
                        "V20260802220200__backend_retention_execution.sql",
                        "V20260802220300__backtest_competition_owner_anonymization.sql",
                        "V20260802230000__backend_operator_room_permissions.sql",
                        "V20260802231000__backend_leaderboard_result_source_guard.sql",
                        "V20260802231100__backend_transactional_outbox.sql",
                        "V20260802231200__backend_delegated_strategy_scope.sql",
                        "V20260802231300__backend_user_case_contract.sql",
                        "V20260802231400__backend_operator_rbac.sql",
                        "V20260802231600__backend_account_sanction_commands.sql",
                        "V20260802231700__backend_operator_case_workflow.sql"),
                result.orderedFileNames());
        assertTrue(Files.exists(result.directory().resolve(CanonicalMigrationBundle.MANIFEST_FILE)));
        assertTrue(Files.exists(result.directory().resolve(CanonicalMigrationBundle.DIGEST_FILE)));
    }

    @Test
    void createsTheSameManifestAndDigestRegardlessOfContributionOrder() throws Exception {
        var central = copyCentralMigrations(temporaryDirectory.resolve("central"));
        var trading = contribution(
                "trading-a", "V20260802120000__trading_add_execution_marker.sql",
                "ALTER TABLE trading.orders ADD COLUMN execution_marker text;\n");
        var backend = contribution(
                "backend-a", "V20260802130000__backend_add_account_marker.sql",
                "ALTER TABLE identity.accounts ADD COLUMN account_marker text;\n");

        var first = CanonicalMigrationBundleAssembler.assemble(
                central,
                List.of(
                        contribution(MigrationOwner.TRADING, trading, "trading", "bot"),
                        contribution(MigrationOwner.BACKEND, backend, "identity")),
                temporaryDirectory.resolve("bundle-one"));
        var second = CanonicalMigrationBundleAssembler.assemble(
                central,
                List.of(
                        contribution(MigrationOwner.BACKEND, backend, "identity"),
                        contribution(MigrationOwner.TRADING, trading, "trading", "bot")),
                temporaryDirectory.resolve("bundle-two"));

        assertEquals(first.sha256(), second.sha256());
        assertEquals(
                Files.readString(first.directory().resolve(CanonicalMigrationBundle.MANIFEST_FILE)),
                Files.readString(second.directory().resolve(CanonicalMigrationBundle.MANIFEST_FILE)));
    }

    @Test
    void rejectsDuplicateGlobalVersionsAndDeclaredOwnerMismatch() throws Exception {
        var central = copyCentralMigrations(temporaryDirectory.resolve("central"));
        var duplicate = contribution(
                "duplicate", "V20260801153000__trading_duplicate_version.sql",
                "ALTER TABLE trading.orders ADD COLUMN duplicate_marker text;\n");
        var wrongOwner = contribution(
                "wrong-owner", "V20260802140000__backend_wrong_owner.sql",
                "ALTER TABLE identity.accounts ADD COLUMN wrong_owner_marker text;\n");

        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalMigrationBundleAssembler.assemble(
                        central,
                        List.of(contribution(MigrationOwner.TRADING, duplicate, "trading", "bot")),
                        temporaryDirectory.resolve("duplicate-bundle")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalMigrationBundleAssembler.assemble(
                        central,
                        List.of(contribution(MigrationOwner.TRADING, wrongOwner, "trading", "bot")),
                        temporaryDirectory.resolve("owner-bundle")));
    }

    @Test
    void rejectsCrossOwnerMutationAndAContributionBaseline() throws Exception {
        var central = copyCentralMigrations(temporaryDirectory.resolve("central"));
        var crossOwner = contribution(
                "cross-owner", "V20260802150000__pipeline_change_backtest.sql",
                "ALTER TABLE backtest.runs ADD COLUMN unsafe integer;\n");
        var baselineContribution = Files.createDirectories(temporaryDirectory.resolve("baseline-contribution"));
        Files.writeString(baselineContribution.resolve(MigrationPolicy.BASELINE_FILE), "-- another baseline\n");

        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalMigrationBundleAssembler.assemble(
                        central,
                        List.of(contribution(MigrationOwner.PIPELINE, crossOwner, "market_data")),
                        temporaryDirectory.resolve("cross-owner-bundle")));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalMigrationBundleAssembler.assemble(
                        central,
                        List.of(contribution(MigrationOwner.TRADING, baselineContribution, "trading", "bot")),
                        temporaryDirectory.resolve("baseline-bundle")));
    }

    @Test
    void loadsTheVersionedContributionContractAndExcludesFixtures() throws Exception {
        var root = Files.createDirectories(temporaryDirectory.resolve("trading-contract"));
        var migrations = Files.createDirectories(root.resolve("migrations"));
        var fixtures = Files.createDirectories(root.resolve("fixtures"));
        Files.writeString(
                root.resolve(MigrationContribution.CONTRACT_FILE),
                "contract.version=1\n"
                        + "owner=trading\n"
                        + "schemas=bot,trading\n"
                        + "filename.regex=^V[0-9]{14}__trading_[a-z0-9]+(?:_[a-z0-9]+)*[.]sql$\n"
                        + "runtime.flyway.enabled=false\n"
                        + "migrations.directory=migrations\n"
                        + "fixtures.directory=fixtures\n");
        Files.writeString(
                migrations.resolve("V20260802160000__trading_add_execution_marker.sql"),
                "ALTER TABLE trading.orders ADD COLUMN execution_marker text;\n");
        Files.writeString(fixtures.resolve("V20260802170000__trading_shadow_fixture.sql"), "invalid fixture");

        var contribution = MigrationContribution.load(root);
        var bundle = CanonicalMigrationBundleAssembler.assemble(
                copyCentralMigrations(temporaryDirectory.resolve("contract-central")),
                List.of(contribution),
                temporaryDirectory.resolve("contract-bundle"));

        assertEquals(MigrationOwner.TRADING, contribution.owner());
        assertEquals(Set.of("bot", "trading"), contribution.schemas());
        assertTrue(bundle.orderedFileNames().contains("V20260802160000__trading_add_execution_marker.sql"));
        assertTrue(bundle.orderedFileNames().stream().noneMatch(name -> name.contains("shadow_fixture")));
    }

    @Test
    void rejectsMutationOutsideTheSchemasDeclaredByTheContribution() throws Exception {
        var migration = contribution(
                "schema-scope", "V20260802180000__trading_change_bot_event.sql",
                "ALTER TABLE bot.bot_events ADD COLUMN sequence_marker bigint;\n");

        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalMigrationBundleAssembler.assemble(
                        copyCentralMigrations(temporaryDirectory.resolve("scope-central")),
                        List.of(contribution(MigrationOwner.TRADING, migration, "trading")),
                        temporaryDirectory.resolve("scope-bundle")));
    }

    @Test
    void rejectsAContributionThatEnablesRuntimeFlyway() throws Exception {
        var root = Files.createDirectories(temporaryDirectory.resolve("runtime-contract"));
        Files.createDirectories(root.resolve("migrations"));
        Files.createDirectories(root.resolve("fixtures"));
        Files.writeString(
                root.resolve(MigrationContribution.CONTRACT_FILE),
                "contract.version=1\n"
                        + "owner=trading\n"
                        + "schemas=bot,trading\n"
                        + "filename.regex=^V[0-9]{14}__trading_.*[.]sql$\n"
                        + "runtime.flyway.enabled=true\n"
                        + "migrations.directory=migrations\n"
                        + "fixtures.directory=fixtures\n");

        assertThrows(IllegalArgumentException.class, () -> MigrationContribution.load(root));
    }

    private Path copyCentralMigrations(Path destination) throws Exception {
        Files.createDirectories(destination);
        var source = Path.of(getClass().getClassLoader().getResource("db/migration").toURI());
        try (var files = Files.list(source)) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                Files.copy(file, destination.resolve(file.getFileName()));
            }
        }
        return destination;
    }

    private Path contribution(String directory, String fileName, String sql) throws Exception {
        var path = Files.createDirectories(temporaryDirectory.resolve(directory));
        Files.writeString(path.resolve(fileName), sql, StandardCharsets.UTF_8);
        return path;
    }

    private MigrationContribution contribution(MigrationOwner owner, Path directory, String... schemas) {
        return new MigrationContribution(owner, Set.of(schemas), directory);
    }
}
