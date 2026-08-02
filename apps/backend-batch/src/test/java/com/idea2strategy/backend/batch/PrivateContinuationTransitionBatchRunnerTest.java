package com.idea2strategy.backend.batch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionReport;
import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PrivateContinuationTransitionBatchRunnerTest {
    @Test
    void runsTheConfiguredBatchSize() {
        var service = Mockito.mock(PrivateContinuationTransitionService.class);
        when(service.run(25)).thenReturn(new PrivateContinuationTransitionReport(Instant.EPOCH, 0));
        var runner = new PrivateContinuationTransitionBatchRunner(service, 25);

        runner.run();

        verify(service).run(25);
    }
}
