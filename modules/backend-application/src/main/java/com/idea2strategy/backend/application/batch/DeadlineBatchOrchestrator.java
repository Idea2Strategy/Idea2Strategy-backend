package com.idea2strategy.backend.application.batch;

import com.idea2strategy.backend.application.batch.BatchCategoryPort.ClaimRequest;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ItemResult;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.ItemStatus;
import com.idea2strategy.backend.application.batch.BatchCategoryPort.WorkItem;
import com.idea2strategy.backend.application.batch.BatchFailureHandoffPort.Failure;
import com.idea2strategy.backend.application.batch.BatchFailureHandoffPort.FailureDisposition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class DeadlineBatchOrchestrator {
    public record RunCommand(
            UUID runId,
            UUID correlationId,
            String workerId,
            String runtimePolicyVersion,
            Duration leaseDuration,
            int perCategoryLimit,
            Set<BatchCategory> categories) {
        public RunCommand {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(correlationId, "correlationId");
            requireText(workerId, "workerId");
            requireText(runtimePolicyVersion, "runtimePolicyVersion");
            Objects.requireNonNull(leaseDuration, "leaseDuration");
            if (leaseDuration.isZero() || leaseDuration.isNegative()) {
                throw new IllegalArgumentException("leaseDuration must be positive");
            }
            if (perCategoryLimit < 1) {
                throw new IllegalArgumentException("perCategoryLimit must be positive");
            }
            categories = Set.copyOf(categories);
            if (categories.isEmpty()) throw new IllegalArgumentException("categories must not be empty");
        }
    }

    public record CategorySummary(
            BatchCategory category,
            Instant databaseNow,
            BatchCategoryPort.Cursor nextCursor,
            int claimed,
            int completed,
            int alreadyCompleted,
            int retryHandovers,
            int deadLetters,
            int duplicateClaims,
            String categoryFailureCode) {}

    public record RunSummary(
            UUID runId,
            UUID correlationId,
            String runtimePolicyVersion,
            List<CategorySummary> categories,
            int claimed,
            int completed,
            int alreadyCompleted,
            int retryHandovers,
            int deadLetters,
            int duplicateClaims,
            int categoryFailures) {
        public RunSummary {
            categories = List.copyOf(categories);
        }
    }

    private final Map<BatchCategory, BatchCategoryPort> ports;
    private final BatchFailureHandoffPort failureHandoff;
    private final BatchRunEvidencePort evidence;
    private final int configuredMaximumBatchSize;

    public DeadlineBatchOrchestrator(
            List<BatchCategoryPort> ports,
            BatchFailureHandoffPort failureHandoff,
            BatchRunEvidencePort evidence,
            int configuredMaximumBatchSize) {
        this.failureHandoff = Objects.requireNonNull(failureHandoff, "failureHandoff");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        if (configuredMaximumBatchSize < 1) {
            throw new IllegalArgumentException("configuredMaximumBatchSize must be positive");
        }
        this.configuredMaximumBatchSize = configuredMaximumBatchSize;
        this.ports = new EnumMap<>(BatchCategory.class);
        for (BatchCategoryPort port : ports) {
            Objects.requireNonNull(port, "port");
            if (this.ports.putIfAbsent(port.category(), port) != null) {
                throw new IllegalArgumentException("duplicate category port: " + port.category());
            }
        }
    }

    public RunSummary run(RunCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.perCategoryLimit() > configuredMaximumBatchSize) {
            throw new IllegalArgumentException("perCategoryLimit exceeds versioned runtime maximum");
        }
        List<CategorySummary> summaries = new ArrayList<>();
        for (BatchCategory category : BatchCategory.values()) {
            if (!command.categories().contains(category)) continue;
            summaries.add(runCategory(command, category));
        }
        RunSummary summary = aggregate(command, summaries);
        evidence.record(summary);
        return summary;
    }

    private CategorySummary runCategory(RunCommand command, BatchCategory category) {
        BatchCategoryPort port = ports.get(category);
        if (port == null) {
            return new CategorySummary(category, null, null, 0, 0, 0, 0, 0, 0,
                    "CATEGORY_PORT_UNAVAILABLE");
        }
        try {
            var page = Objects.requireNonNull(port.claimDue(new ClaimRequest(
                    command.workerId(), command.runtimePolicyVersion(), command.leaseDuration(),
                    null, command.perCategoryLimit(), command.runId(), command.correlationId())),
                    "claim page");
            if (page.items().size() > command.perCategoryLimit()) {
                throw new IllegalStateException("CATEGORY_RETURNED_UNBOUNDED_PAGE");
            }
            int completed = 0;
            int already = 0;
            int retry = 0;
            int dead = 0;
            int duplicates = 0;
            Set<String> seen = new LinkedHashSet<>();
            for (WorkItem item : page.items()) {
                if (item.category() != category) {
                    throw new IllegalStateException("CATEGORY_ITEM_MISMATCH");
                }
                if (!seen.add(item.idempotencyKey())) {
                    duplicates++;
                    continue;
                }
                ItemResult result;
                try {
                    result = Objects.requireNonNull(
                            port.execute(item, command.runId(), command.correlationId()), "item result");
                } catch (RuntimeException failure) {
                    result = ItemResult.retryable("UNCLASSIFIED_EXECUTION_FAILURE");
                }
                if (result.status() == ItemStatus.COMPLETED) completed++;
                else if (result.status() == ItemStatus.ALREADY_COMPLETED) already++;
                else if (result.status() == ItemStatus.RETRYABLE_FAILURE) {
                    retry++;
                    handoff(command, item, result.failureCode(), FailureDisposition.RETRY);
                } else {
                    dead++;
                    handoff(command, item, result.failureCode(), FailureDisposition.DEAD_LETTER);
                }
            }
            return new CategorySummary(category, page.databaseNow(), page.nextCursor(),
                    page.items().size(), completed, already, retry, dead, duplicates, null);
        } catch (RuntimeException categoryFailure) {
            return new CategorySummary(category, null, null, 0, 0, 0, 0, 0, 0,
                    stableCategoryFailure(categoryFailure));
        }
    }

    private void handoff(
            RunCommand command, WorkItem item, String failureCode, FailureDisposition disposition) {
        failureHandoff.handoff(new Failure(
                item.category(), item.itemId(), item.idempotencyKey(), item.attemptNumber(),
                item.claimToken(), failureCode, disposition, command.runtimePolicyVersion(),
                command.runId(), command.correlationId()));
    }

    private static RunSummary aggregate(RunCommand command, List<CategorySummary> summaries) {
        return new RunSummary(
                command.runId(), command.correlationId(), command.runtimePolicyVersion(), summaries,
                summaries.stream().mapToInt(CategorySummary::claimed).sum(),
                summaries.stream().mapToInt(CategorySummary::completed).sum(),
                summaries.stream().mapToInt(CategorySummary::alreadyCompleted).sum(),
                summaries.stream().mapToInt(CategorySummary::retryHandovers).sum(),
                summaries.stream().mapToInt(CategorySummary::deadLetters).sum(),
                summaries.stream().mapToInt(CategorySummary::duplicateClaims).sum(),
                (int) summaries.stream().filter(s -> s.categoryFailureCode() != null).count());
    }

    private static String stableCategoryFailure(RuntimeException failure) {
        String message = failure.getMessage();
        if (message != null && message.matches("[A-Z][A-Z0-9_]{2,79}")) return message;
        return "CATEGORY_EXECUTION_FAILED";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
