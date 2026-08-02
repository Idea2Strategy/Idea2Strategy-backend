package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class PostEvaluationStopTransitionBatchRunner {
    private static final Logger log = LoggerFactory.getLogger(PostEvaluationStopTransitionBatchRunner.class);
    private final PostEvaluationStopTransitionService service;
    private final int batchSize;

    PostEvaluationStopTransitionBatchRunner(PostEvaluationStopTransitionService service, int batchSize) {
        this.service = service;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${idea2strategy.batch.post-evaluation-stop-transition.fixed-delay:PT10S}")
    void run() {
        var report = service.run(batchSize);
        log.info(
                "Post-evaluation stop transition batch completed: transitionsApplied={}, observedAt={}",
                report.transitionsApplied(),
                report.observedAt());
    }
}
