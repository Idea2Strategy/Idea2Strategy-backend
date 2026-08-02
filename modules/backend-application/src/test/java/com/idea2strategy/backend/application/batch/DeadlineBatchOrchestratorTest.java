package com.idea2strategy.backend.application.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimPage;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimRequest;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.Cursor;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ItemResult;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.WorkItem;
import com.idea2strategy.backend.application.batch.BatchFailureHandoffPort.Failure;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator.RunCommand;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeadlineBatchOrchestratorTest {
    private static final Instant DATABASE_NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void isolatesItemAndCategoryFailuresAndRecordsOneBoundedSummary() {
        var failures = new ArrayList<Failure>();
        var evidence = new ArrayList<DeadlineBatchOrchestrator.RunSummary>();
        var sanction = new FakePort(BatchCategory.SANCTION, List.of(
                item(BatchCategory.SANCTION, "completed", "k1"),
                item(BatchCategory.SANCTION, "retry", "k2"),
                item(BatchCategory.SANCTION, "dead", "k3"),
                item(BatchCategory.SANCTION, "duplicate", "k3")));
        sanction.results.put("completed", ItemResult.completed());
        sanction.results.put("retry", ItemResult.retryable("TEMPORARY_PROVIDER_FAILURE"));
        sanction.results.put("dead", ItemResult.permanent("UNSUPPORTED_PAYLOAD"));
        var session = new FakePort(BatchCategory.SESSION, List.of());
        session.claimFailure = new IllegalStateException("SESSION_QUERY_FAILED");
        var delegated = new FakePort(BatchCategory.DELEGATED_TOKEN,
                List.of(item(BatchCategory.DELEGATED_TOKEN, "done-before", "k4")));
        delegated.results.put("done-before", ItemResult.alreadyCompleted());
        var cases = new FakePort(BatchCategory.CASE_DEADLINE,
                List.of(item(BatchCategory.CASE_DEADLINE, "throws", "k5")));
        cases.executeFailure.add("throws");

        var orchestrator = new DeadlineBatchOrchestrator(
                List.of(sanction, session, delegated, cases), failures::add, evidence::add, 10);
        var summary = orchestrator.run(command(Set.of(BatchCategory.values()), 10));

        assertThat(summary.claimed()).isEqualTo(6);
        assertThat(summary.completed()).isEqualTo(1);
        assertThat(summary.alreadyCompleted()).isEqualTo(1);
        assertThat(summary.retryHandovers()).isEqualTo(2);
        assertThat(summary.deadLetters()).isEqualTo(1);
        assertThat(summary.duplicateClaims()).isEqualTo(1);
        assertThat(summary.categoryFailures()).isEqualTo(2);
        assertThat(failures).extracting(Failure::failureCode)
                .containsExactly("TEMPORARY_PROVIDER_FAILURE", "UNSUPPORTED_PAYLOAD",
                        "UNCLASSIFIED_EXECUTION_FAILURE");
        assertThat(evidence).containsExactly(summary);
        assertThat(summary.categories()).filteredOn(s -> s.category() == BatchCategory.SANCTION)
                .singleElement().satisfies(s -> {
                    assertThat(s.databaseNow()).isEqualTo(DATABASE_NOW);
                    assertThat(s.nextCursor()).isEqualTo(new Cursor(DATABASE_NOW, "next-SANCTION"));
                });
        assertThat(summary.categories()).filteredOn(s -> s.category() == BatchCategory.NOTIFICATION)
                .extracting(DeadlineBatchOrchestrator.CategorySummary::categoryFailureCode)
                .containsExactly("CATEGORY_PORT_UNAVAILABLE");
    }

    @Test
    void durableCategoryIdempotencyTurnsReplayIntoAlreadyCompleted() {
        var port = new FakePort(BatchCategory.SESSION,
                List.of(item(BatchCategory.SESSION, "session-1", "same-command")));
        port.durableIdempotency = true;
        var evidence = new ArrayList<DeadlineBatchOrchestrator.RunSummary>();
        var orchestrator = new DeadlineBatchOrchestrator(
                List.of(port), ignored -> {}, evidence::add, 5);

        var first = orchestrator.run(command(Set.of(BatchCategory.SESSION), 1));
        var replay = orchestrator.run(command(Set.of(BatchCategory.SESSION), 1));

        assertThat(first.completed()).isEqualTo(1);
        assertThat(replay.alreadyCompleted()).isEqualTo(1);
        assertThat(port.executions).isEqualTo(2);
        assertThat(evidence).hasSize(2);
    }

    @Test
    void rejectsUnboundedCategoryPageAndRuntimeLimitBeforeExecutingWork() {
        var oversized = new FakePort(BatchCategory.NOTIFICATION, List.of(
                item(BatchCategory.NOTIFICATION, "n1", "n1"),
                item(BatchCategory.NOTIFICATION, "n2", "n2")));
        var orchestrator = new DeadlineBatchOrchestrator(
                List.of(oversized), ignored -> {}, ignored -> {}, 4);

        var summary = orchestrator.run(command(Set.of(BatchCategory.NOTIFICATION), 1));
        assertThat(summary.categoryFailures()).isEqualTo(1);
        assertThat(summary.claimed()).isZero();
        assertThat(oversized.executions).isZero();
        assertThatThrownBy(() -> orchestrator.run(command(Set.of(BatchCategory.NOTIFICATION), 5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("versioned runtime maximum");
    }

    private static RunCommand command(Set<BatchCategory> categories, int limit) {
        return new RunCommand(UUID.randomUUID(), UUID.randomUUID(), "batch-worker-1",
                "batch-policy-v1", Duration.ofMinutes(1), limit, categories);
    }

    private static WorkItem item(BatchCategory category, String id, String key) {
        return new WorkItem(category, id, DATABASE_NOW.minusSeconds(1), key,
                UUID.randomUUID(), 1);
    }

    private static final class FakePort implements BatchCategoryPort {
        private final BatchCategory category;
        private final List<WorkItem> items;
        private final Map<String, ItemResult> results = new java.util.HashMap<>();
        private final Set<String> executeFailure = new HashSet<>();
        private final Set<String> completedKeys = new HashSet<>();
        private RuntimeException claimFailure;
        private boolean durableIdempotency;
        private int executions;

        private FakePort(BatchCategory category, List<WorkItem> items) {
            this.category = category;
            this.items = items;
        }

        @Override
        public BatchCategory category() {
            return category;
        }

        @Override
        public ClaimPage claimDue(ClaimRequest request) {
            if (claimFailure != null) throw claimFailure;
            return new ClaimPage(DATABASE_NOW, items,
                    new Cursor(DATABASE_NOW, "next-" + category));
        }

        @Override
        public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
            executions++;
            if (executeFailure.contains(item.itemId())) throw new IllegalStateException("provider failed");
            if (durableIdempotency && !completedKeys.add(item.idempotencyKey())) {
                return ItemResult.alreadyCompleted();
            }
            return results.getOrDefault(item.itemId(), ItemResult.completed());
        }
    }
}
