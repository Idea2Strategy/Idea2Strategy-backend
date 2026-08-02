package com.idea2strategy.backend.batch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.accountretention.AccountRetentionCoordinator;
import com.idea2strategy.backend.application.accountretention.RetentionBatchResult;
import org.junit.jupiter.api.Test;

class AccountRetentionBatchRunnerTest {
    @Test
    void delegatesTheConfiguredBatchSize() {
        var coordinator = org.mockito.Mockito.mock(AccountRetentionCoordinator.class);
        when(coordinator.run(25)).thenReturn(new RetentionBatchResult(0, 0, 0, 0));

        new AccountRetentionBatchRunner(coordinator, 25).run();

        verify(coordinator).run(25);
    }
}
