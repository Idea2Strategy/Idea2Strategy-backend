package com.idea2strategy.backend.batch;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class DeadlineBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(DeadlineBatchRunner.class);

    private final DurableDeadlineBatchRuntime runtime;

    DeadlineBatchRunner(DurableDeadlineBatchRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.deadline.fixed-delay:PT1M}")
    void run() {
        var report = runtime.run();
        log.info("Durable deadline batch completed: runId={}, duplicateTrigger={}, discovered={}, "
                        + "claimed={}, succeeded={}, alreadyApplied={}, retries={}, quarantined={}, "
                        + "categoryFailures={}, status={}",
                report.runId(), report.duplicateTrigger(), report.discovered(), report.claimed(),
                report.succeeded(), report.alreadyApplied(), report.retries(), report.quarantined(),
                report.categoryFailures(), report.terminalStatus());
    }
}
