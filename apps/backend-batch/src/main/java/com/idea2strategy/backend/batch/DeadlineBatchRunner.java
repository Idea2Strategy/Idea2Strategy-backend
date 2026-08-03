package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.batch.BatchCategory;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator;
import com.idea2strategy.backend.application.batch.DeadlineBatchOrchestrator.RunCommand;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class DeadlineBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(DeadlineBatchRunner.class);

    private final DeadlineBatchOrchestrator orchestrator;
    private final String workerId;
    private final String runtimePolicyVersion;
    private final Duration leaseDuration;
    private final int batchSize;
    private final Set<BatchCategory> categories;

    DeadlineBatchRunner(
            DeadlineBatchOrchestrator orchestrator,
            String workerId,
            String runtimePolicyVersion,
            Duration leaseDuration,
            int batchSize,
            Set<BatchCategory> categories) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        this.workerId = requireText(workerId, "workerId");
        this.runtimePolicyVersion = requireText(runtimePolicyVersion, "runtimePolicyVersion");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
        this.categories = Set.copyOf(Objects.requireNonNull(categories, "categories"));
        if (this.categories.isEmpty()) throw new IllegalArgumentException("categories must not be empty");
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.deadline.fixed-delay:PT1M}")
    void run() {
        UUID runId = UUID.randomUUID();
        var summary = orchestrator.run(new RunCommand(
                runId, UUID.randomUUID(), workerId, runtimePolicyVersion,
                leaseDuration, batchSize, categories));
        log.info("Deadline batch completed: runId={}, claimed={}, completed={}, alreadyCompleted={}, failures={}",
                runId, summary.claimed(), summary.completed(), summary.alreadyCompleted(),
                summary.categoryFailures() + summary.retryHandovers() + summary.deadLetters());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
