package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Compares what each runtime role is granted against what the SQL that role executes actually asks for.
 *
 * <p>Root #456 exists because nothing did this. On 2026-08-09 three grant gaps reached the deployed
 * environment — backend #246 (nine competition and bot tables), #249 (the backtest worker's outbox
 * tables), #251 ({@code bot.continuation_deadlines}) — and every one was found by watching a service
 * fail. The privileges are derived from the adapter sources here instead, so the fourth gap fails a
 * test on a pull request rather than a scheduled job in Development.
 *
 * <p>Scope is the two roles whose SQL lives in this repository. {@code backend-batch} runs under BATCH
 * and names its adapters explicitly, so its requirement set is exact. The API and worker applications
 * component-scan the persistence module, so BACKEND is checked against every adapter in it — a superset,
 * and deliberately so: an adapter that exists in the module can be wired into the runtime without any
 * further change, and a role that cannot execute it is a gap waiting for the wiring.
 */
class RoleGrantsCoverExecutedSqlTest {

    private static final String PERSISTENCE = "modules/backend-persistence/src/main/java";
    private static final String BATCH_APPLICATION = "apps/backend-batch/src/main/java";

    @Test
    void batchRoleCoversEverySqlStatementItsSchedulesExecute() {
        Path root = repositoryRoot();
        Path persistence = root.resolve(PERSISTENCE);
        Path batch = root.resolve(BATCH_APPLICATION);
        assumeTrue(Files.isDirectory(persistence) && Files.isDirectory(batch),
                "backend sources are not present in this checkout");

        Set<String> named = ExecutedSqlPrivileges.adaptersReferencedBy(batch);
        List<Path> sources = ExecutedSqlPrivileges.adapterSources(persistence).stream()
                .filter(path -> named.contains(path.getFileName().toString().replace(".java", "")))
                .toList();
        assertTrue(sources.size() >= 7,
                "Expected backend-batch to name at least seven persistence adapters, resolved " + sources.size()
                        + ". If the wiring changed, this derivation has to follow it.");

        assertGranted(DatabaseAccessPolicy.ApplicationRole.BATCH, sources);
    }

    @Test
    void backendRoleCoversEverySqlStatementItsAdaptersExecute() {
        Path root = repositoryRoot();
        Path persistence = root.resolve(PERSISTENCE);
        assumeTrue(Files.isDirectory(persistence), "backend sources are not present in this checkout");

        List<Path> sources = ExecutedSqlPrivileges.adapterSources(persistence);
        assertTrue(sources.size() >= 30,
                "Expected the persistence module to hold at least thirty adapters, found " + sources.size() + ".");

        assertGranted(DatabaseAccessPolicy.ApplicationRole.BACKEND, sources);
    }

    private static void assertGranted(
            DatabaseAccessPolicy.ApplicationRole role, List<Path> sources) {
        // Grouped by requirement so one missing privilege reports every statement that needs it, rather
        // than the first one the walk happened to reach.
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        int checked = 0;
        for (Path source : sources) {
            for (ExecutedSqlPrivileges.Requirement requirement : ExecutedSqlPrivileges.requirements(source)) {
                checked++;
                if (DatabaseAccessPolicy.allows(
                        role, requirement.access(), requirement.schema(), requirement.table())) {
                    continue;
                }
                missing.computeIfAbsent(
                        requirement.access() + " on " + requirement.schema() + "." + requirement.table(),
                        key -> new LinkedHashSet<>())
                        .add(requirement.because());
            }
        }
        assertTrue(checked > 0, "No SQL was derived from " + sources.size() + " adapters; the extraction is broken.");
        if (!missing.isEmpty()) {
            List<String> report = new ArrayList<>();
            missing.forEach((privilege, reasons) -> report.add(privilege + " <- " + String.join("; ", reasons)));
            fail(role + " executes SQL it is not granted:\n  " + String.join("\n  ", report)
                    + "\nAdd the privilege to DatabaseAccessPolicy, or remove the lock or statement that needs it.");
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
                    && Files.isDirectory(candidate.resolve("modules"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to locate the backend repository root from " + Path.of("").toAbsolutePath());
    }
}
