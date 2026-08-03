package com.idea2strategy.backend.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthorizationPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionExpiryPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionResult;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionState;
import com.idea2strategy.backend.application.batch.BatchCategoryPort;
import com.idea2strategy.backend.persistence.notification.NotificationEmailWorker;
import com.idea2strategy.backend.persistence.outbox.TransactionalOutboxStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DeadlineBatchBindingTest {
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");

    @Test
    void sanctionBindingExecutesTheExactDueAggregateVersion() {
        UUID account = id(1);
        UUID sanction = id(2);
        AccountSanctionExpiryPort due = limit -> List.of(
                new AccountSanctionExpiryPort.DueSanction(account, sanction, NOW, 3));
        AccountSanctionCommandPort commands = (command, at, authorization, decision, effects) ->
                new AccountSanctionResult(
                        AccountSanctionResult.Status.NO_OP, "SANCTION_NOT_ACTIVE", null,
                        authorization, null, List.of());
        AccountSanctionAuthorizationPort authorization = (context, permission, at) ->
                new AccountSanctionAuthorizationPort.Decision(
                        false, "UNUSED", null, Set.of(), Set.of(), false, false);
        var service = new AccountSanctionCommandService(
                commands, authorization, effect -> {}, messages -> {}, id(3), id(4),
                Clock.fixed(NOW, ZoneOffset.UTC));
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(java.sql.Timestamp.class)))
                .thenReturn(java.sql.Timestamp.from(NOW));
        var port = new SanctionExpiryBatchCategoryPort(due, service, jdbc);

        var page = port.claimDue(request());
        var result = port.execute(page.items().getFirst(), id(8), id(9));

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.itemId()).contains(account.toString(), sanction.toString(), "|3|");
            assertThat(item.idempotencyKey()).contains(sanction.toString(), NOW.toString());
        });
        assertThat(result.status()).isEqualTo(BatchCategoryPort.ItemStatus.ALREADY_COMPLETED);
    }

    @Test
    void notificationBindingUsesFilteredA17ClaimAndMapsDurableRetryDisposition() {
        TransactionalOutboxStore outbox = mock(TransactionalOutboxStore.class);
        NotificationEmailWorker worker = mock(NotificationEmailWorker.class);
        UUID messageId = id(10);
        UUID token = id(11);
        var message = new TransactionalOutboxStore.ClaimedMessage(
                messageId, "notification", id(12), "NOTIFICATION_EMAIL_DELIVERY", "1",
                "{}", "a".repeat(64), "notification:1", token, 1, NOW, NOW.plusSeconds(30));
        when(outbox.claimDueMatching(anyString(), anyString(), any(), anyInt(),
                anyString(), anyString())).thenReturn(List.of(message));
        when(worker.deliver(any(), anyString(), anyInt(), any()))
                .thenReturn(NotificationEmailWorker.DeliveryDisposition.RETRY);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(java.sql.Timestamp.class)))
                .thenReturn(java.sql.Timestamp.from(NOW));
        var port = new NotificationDeliveryBatchCategoryPort(
                outbox, worker, 5, Duration.ofMinutes(1), jdbc);

        var page = port.claimDue(request());
        var result = port.execute(page.items().getFirst(), id(13), id(14));

        assertThat(result.status()).isEqualTo(BatchCategoryPort.ItemStatus.RETRYABLE_FAILURE);
        assertThat(result.failureCode()).isEqualTo("NOTIFICATION_DELIVERY_RETRY_SCHEDULED");
    }

    private static BatchCategoryPort.ClaimRequest request() {
        return new BatchCategoryPort.ClaimRequest(
                "worker-1", "batch-v1", Duration.ofSeconds(30), null, 20, id(6), id(7));
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a2100000-0000-4000-8000-%012d".formatted(suffix));
    }
}
