package com.idea2strategy.backend.batch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionReport;
import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PostEvaluationStopTransitionBatchRunnerTest {
    @Test
    void runsTheConfiguredBatchSize() {
        var service = Mockito.mock(PostEvaluationStopTransitionService.class);
        when(service.run(20)).thenReturn(new PostEvaluationStopTransitionReport(Instant.EPOCH, 0));
        var runner = new PostEvaluationStopTransitionBatchRunner(service, 20);

        runner.run();

        verify(service).run(20);
    }
}
