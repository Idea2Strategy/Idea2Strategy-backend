package com.idea2strategy.backend.application.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.TestPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BotContinuationServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-08-10T09:00:00Z");

    @Test
    void showsTheDeadlineAndKeepsRenewalDisabledBeforeTheFinalSevenDays() {
        var principal = new TestPrincipal(OWNER_ID);
        BotContinuationQueryPort query = (botId, ownerId) -> Optional.of(
                new BotContinuationFacts(BOT_ID, DUE_AT, null));
        var service = new BotContinuationService(query, rejectingCommand(), principal, fixedClock());

        BotContinuationView result = service.get(BOT_ID);

        assertThat(result.renewalAvailableFrom()).isEqualTo(Instant.parse("2026-08-03T09:00:00Z"));
        assertThat(result.renewalAllowed()).isFalse();
        assertThat(result.dueAt()).isEqualTo(DUE_AT);
    }

    @Test
    void renewsFromTheServerReceiptTimeWithinTheFinalSevenDays() {
        var expected = new BotContinuationFacts(
                BOT_ID, Instant.parse("2026-09-01T09:00:00Z"), NOW);
        var principal = new TestPrincipal(OWNER_ID);
        var capturedReceiptTime = new AtomicReference<Instant>();
        BotContinuationCommandPort command = (botId, ownerId, receivedAt) -> {
            capturedReceiptTime.set(receivedAt);
            return Optional.of(expected);
        };
        var service = new BotContinuationService(missingQuery(), command, principal, fixedClock());

        BotContinuationView result = service.renew(BOT_ID);

        assertThat(result.dueAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(result.lastRenewedAt()).isEqualTo(NOW);
        assertThat(result.renewalAllowed()).isFalse();
        assertThat(capturedReceiptTime).hasValue(NOW);
    }

    @Test
    void hidesAnUnownedBot() {
        var principal = new TestPrincipal(OWNER_ID);
        var service = new BotContinuationService(
                missingQuery(),
                (botId, ownerId, receivedAt) -> Optional.empty(),
                principal,
                fixedClock());

        assertThatThrownBy(() -> service.renew(BOT_ID))
                .isInstanceOf(BotContinuationNotFoundException.class)
                .hasMessage("Bot continuation deadline not found");
    }

    private static BotContinuationCommandPort rejectingCommand() {
        return (botId, ownerId, receivedAt) -> {
            throw new AssertionError("query must not renew the deadline");
        };
    }

    private static BotContinuationQueryPort missingQuery() {
        return (botId, ownerId) -> Optional.empty();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
