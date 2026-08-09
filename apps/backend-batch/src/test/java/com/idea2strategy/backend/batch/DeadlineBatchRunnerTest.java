package com.idea2strategy.backend.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimRequest;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class DeadlineBatchRunnerTest {
    @Test
    void productionSchedulerIsFailClosedUntilDurableClaimLeaseIsApproved() {
        ConditionalOnProperty gate = DeadlineBatchConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(gate.name()).containsExactly("idea2strategy.batch.deadline.enabled");
        assertThat(gate.havingValue()).isEqualTo("true");
        assertThat(gate.matchIfMissing()).isFalse();
    }

    @Test
    void forwardsVersionedRuntimeLeaseAndBoundedSizeToEveryCategory() {
        var requests = new ArrayList<ClaimRequest>();
        List<BatchCategoryPort> ports = java.util.Arrays.stream(BatchCategory.values())
                .map(category -> new EmptyPort(category, requests))
                .map(BatchCategoryPort.class::cast)
                .toList();
        var orchestrator = new DeadlineBatchOrchestrator(ports, ignored -> {}, ignored -> {}, 25);
        var runner = new DeadlineBatchRunner(
                orchestrator, "batch-worker-a", "deadline-policy-v3", Duration.ofSeconds(90), 25, 3,
                java.util.Set.of(BatchCategory.values()));

        runner.run();

        assertThat(requests).hasSize(BatchCategory.values().length).allSatisfy(request -> {
            assertThat(request.workerId()).isEqualTo("batch-worker-a");
            assertThat(request.runtimePolicyVersion()).isEqualTo("deadline-policy-v3");
            assertThat(request.leaseDuration()).isEqualTo(Duration.ofSeconds(90));
            assertThat(request.limit()).isEqualTo(25);
        });
        assertThat(requests).extracting(ClaimRequest::runId).doesNotContainNull().containsOnly(requests.getFirst().runId());
    }

    @Test
    void requestsOnlyRegisteredCategoriesSoOptionalNotificationIsNotAFalseFailure() {
        var requests = new ArrayList<ClaimRequest>();
        var session = new EmptyPort(BatchCategory.REFRESH_TOKEN_FAMILY, requests);
        var summaries = new ArrayList<DeadlineBatchOrchestrator.RunSummary>();
        var orchestrator = new DeadlineBatchOrchestrator(
                List.of(session), ignored -> {}, summaries::add, 10);
        var runner = new DeadlineBatchRunner(
                orchestrator, "worker", "policy-v1", Duration.ofMinutes(1), 10, 3,
                java.util.Set.of(BatchCategory.REFRESH_TOKEN_FAMILY));

        runner.run();

        assertThat(requests).hasSize(1);
        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.categoryFailures()).isZero();
            assertThat(summary.categories()).extracting(DeadlineBatchOrchestrator.CategorySummary::category)
                    .containsExactly(BatchCategory.REFRESH_TOKEN_FAMILY);
        });
    }

    private record EmptyPort(BatchCategory category, List<ClaimRequest> requests)
            implements BatchCategoryPort {
        @Override
        public ClaimPage claimDue(ClaimRequest request) {
            requests.add(request);
            return new ClaimPage(Instant.parse("2026-08-03T00:00:00Z"), List.of(), null);
        }

        @Override
        public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
            throw new AssertionError("empty category must not execute");
        }
    }
}
