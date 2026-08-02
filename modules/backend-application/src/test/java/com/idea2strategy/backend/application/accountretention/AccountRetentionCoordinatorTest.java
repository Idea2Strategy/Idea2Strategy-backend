package com.idea2strategy.backend.application.accountretention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountRetentionCoordinatorTest {
    @Test
    void isolatesAccountFailuresAndContinuesTheBatch() {
        var store = mock(RetentionExecutionStore.class);
        var now = Instant.parse("2026-09-01T00:00:00Z");
        var first = new RetentionCandidate(UUID.randomUUID(), UUID.randomUUID(), "PROFILE");
        var second = new RetentionCandidate(UUID.randomUUID(), UUID.randomUUID(), "AUTH_CREDENTIAL");
        var third = new RetentionCandidate(UUID.randomUUID(), UUID.randomUUID(), "CONTACT_IDENTIFIER");
        when(store.findDueAccounts(3, now)).thenReturn(List.of(
                first.accountId(), second.accountId(), third.accountId()));
        when(store.executeAccount(any(), any(), any())).thenAnswer(invocation -> {
            UUID accountId = invocation.getArgument(0);
            if (accountId.equals(first.accountId())) return List.of(RetentionExecutionResult.COMPLETED);
            if (accountId.equals(second.accountId())) throw new IllegalStateException("database unavailable");
            return List.of(RetentionExecutionResult.HELD);
        });

        var result = new AccountRetentionCoordinator(store, Clock.fixed(now, ZoneOffset.UTC)).run(3);

        assertEquals(new RetentionBatchResult(3, 1, 1, 1), result);
        verify(store).recordAccountFailure(org.mockito.ArgumentMatchers.eq(second.accountId()), any(),
                org.mockito.ArgumentMatchers.eq("ILLEGALSTATEEXCEPTION_ERROR"), org.mockito.ArgumentMatchers.eq(now));
    }
}
