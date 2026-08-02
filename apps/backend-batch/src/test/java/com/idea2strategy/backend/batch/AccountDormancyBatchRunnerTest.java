package com.idea2strategy.backend.batch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import java.util.List;
import org.junit.jupiter.api.Test;

class AccountDormancyBatchRunnerTest {
    @Test
    void delegatesTheConfiguredBatchSize() {
        AccountLifecycleService lifecycle = org.mockito.Mockito.mock(AccountLifecycleService.class);
        when(lifecycle.markDormantCandidates(25)).thenReturn(List.of());

        new AccountDormancyBatchRunner(lifecycle, 25).run();

        verify(lifecycle).markDormantCandidates(25);
    }
}
