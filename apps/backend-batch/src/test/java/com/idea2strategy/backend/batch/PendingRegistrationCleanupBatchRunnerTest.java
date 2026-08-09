package com.idea2strategy.backend.batch;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.identity.PendingRegistrationCleanupService;
import org.junit.jupiter.api.Test;

class PendingRegistrationCleanupBatchRunnerTest {
    @Test
    void delegatesTheConfiguredBatchSize() {
        var service = org.mockito.Mockito.mock(PendingRegistrationCleanupService.class);
        when(service.purgeExpired(250)).thenReturn(4);

        new PendingRegistrationCleanupBatchRunner(service, 250).run();

        verify(service).purgeExpired(250);
    }
}
