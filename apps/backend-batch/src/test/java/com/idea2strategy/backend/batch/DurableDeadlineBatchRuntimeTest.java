package com.idea2strategy.backend.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimPage;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimRequest;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ItemResult;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.WorkItem;
import com.idea2strategy.backend.persistence.batch.DurableBatchStore;
import com.idea2strategy.backend.persistence.batch.DurableBatchStore.ClaimedItem;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DurableDeadlineBatchRuntimeTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:05:17Z");
    private static final UUID RUN = id(1);
    private static final UUID ITEM = id(2);
    private static final UUID CLAIM = id(3);

    @Test
    void initializesOnlyTheExactApprovedRegistryAndPersistsACompletedItem() {
        DurableBatchStore store = mock(DurableBatchStore.class);
        var evidence = new ArrayList<DurableDeadlineBatchRuntime.RunReport>();
        var session = new SingleItemPort(BatchCategory.SESSION, new WorkItem(
                BatchCategory.SESSION,
                id(10) + "|" + id(11) + "|2026-08-03T00:04:00Z",
                Instant.parse("2026-08-03T00:04:00Z"),
                "session-expiry:" + id(11) + ":2026-08-03T00:04:00Z",
                id(12), 1));
        List<BatchCategoryPort> ports = List.of(
                session,
                new EmptyPort(BatchCategory.DELEGATED_TOKEN),
                new EmptyPort(BatchCategory.SANCTION),
                new EmptyPort(BatchCategory.NOTIFICATION),
                new EmptyPort(BatchCategory.CASE_DEADLINE));
        var settings = new DurableDeadlineBatchRuntime.Settings(
                "deadline", "v1", "content-hash-v1", "policy-v1", "worker-a",
                Duration.ofMinutes(2), Duration.ofMinutes(1), Duration.ofMinutes(5),
                Duration.ofMinutes(1), 25, 3);
        var runtime = new DurableDeadlineBatchRuntime(store, ports, evidence::add, settings);

        runtime.initialize();

        verify(store).publishJobVersion(
                "deadline", "v1",
                "[\"CASE_RESPONSE_DEADLINE\",\"DELEGATED_AUTHORIZATION_EXPIRY\","
                        + "\"DELEGATED_CREDENTIAL_EXPIRY\",\"NOTIFICATION_RETRY\","
                        + "\"SANCTION_EXPIRY\",\"SESSION_EXPIRY\"]",
                "content-hash-v1");

        when(store.databaseNow()).thenReturn(NOW, NOW, NOW, NOW);
        when(store.startRun(eq("deadline"), eq("v1"), eq("policy-v1"), anyString(),
                any(), any())).thenReturn(RUN);
        when(store.runStatus(RUN)).thenReturn("RUNNING");
        when(store.discover(eq(RUN), eq("SESSION_EXPIRY"), anyString(),
                eq(session.item.itemId()), eq(session.item.dueAt()), any())).thenReturn(ITEM);
        when(store.claimDue("SESSION_EXPIRY", "worker-a", "policy-v1",
                Duration.ofMinutes(2), 25)).thenReturn(List.of(new ClaimedItem(
                ITEM, RUN, "SESSION_EXPIRY", "safe-source", session.item.itemId(),
                session.item.dueAt(), id(20), CLAIM, 1, NOW, NOW.plusSeconds(120))));
        for (String category : Set.of(
                "DELEGATED_CREDENTIAL_EXPIRY", "DELEGATED_AUTHORIZATION_EXPIRY",
                "SANCTION_EXPIRY", "NOTIFICATION_RETRY", "CASE_RESPONSE_DEADLINE")) {
            when(store.claimDue(category, "worker-a", "policy-v1",
                    Duration.ofMinutes(2), 25)).thenReturn(List.of());
        }

        DurableDeadlineBatchRuntime.RunReport report = runtime.run();

        assertThat(report.duplicateTrigger()).isFalse();
        assertThat(report.discovered()).isEqualTo(1);
        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.categoryFailures()).isZero();
        verify(store).succeed(ITEM, CLAIM, "APPLIED");
        verify(store).completeRun(RUN, "SUCCEEDED");
        assertThat(evidence).containsExactly(report);
    }

    private record EmptyPort(BatchCategory category) implements BatchCategoryPort {
        @Override public ClaimPage claimDue(ClaimRequest request) {
            return new ClaimPage(NOW, List.of(), null);
        }

        @Override public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
            throw new AssertionError("empty port must not execute");
        }
    }

    private static final class SingleItemPort implements BatchCategoryPort {
        private final BatchCategory category;
        private final WorkItem item;

        private SingleItemPort(BatchCategory category, WorkItem item) {
            this.category = category;
            this.item = item;
        }

        @Override public BatchCategory category() { return category; }

        @Override public ClaimPage claimDue(ClaimRequest request) {
            return new ClaimPage(NOW, List.of(item), null);
        }

        @Override public ItemResult execute(WorkItem item, UUID runId, UUID correlationId) {
            return ItemResult.completed();
        }
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2100000-0000-4000-8000-%012d".formatted(suffix));
    }
}
