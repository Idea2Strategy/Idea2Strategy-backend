package com.idea2strategy.backend.application.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.TestPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BotRunCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant ROOM_START = Instant.parse("2026-08-03T13:30:00Z");

    @Test
    void issuesTheOwnedRunCommandAfterPreflightAndPreservesWaitingMode() {
        var expected = new BotRunDispatch(
                BOT_ID,
                MESSAGE_ID,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ROOM_START,
                BotRunDispatchMode.WAITING,
                true);
        var principal = new TestPrincipal(OWNER_ID);
        var preflight = readyPreflight(principal);
        BotRunCommandPort commandPort = (botId, ownerId, requestedAt) -> Optional.of(expected);
        var service = new BotRunCommandService(commandPort, preflight, principal, fixedClock());

        BotRunDispatch result = service.issue(BOT_ID);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void rejectsEveryBlockedPreflightReasonBeforeWritingACommand() {
        var writes = new AtomicInteger();
        var principal = new TestPrincipal(OWNER_ID);
        var facts = new BotExecutionPreflightFacts(
                BOT_ID,
                BigDecimal.ZERO,
                11,
                List.of(),
                true,
                true,
                true,
                List.of());
        var preflight = new BotExecutionPreflightService(
                (botId, ownerId, at) -> Optional.of(facts), principal, fixedClock());
        BotRunCommandPort commandPort = (botId, ownerId, requestedAt) -> {
            writes.incrementAndGet();
            throw new AssertionError("blocked command must not be written");
        };
        var service = new BotRunCommandService(commandPort, preflight, principal, fixedClock());

        assertThatThrownBy(() -> service.issue(BOT_ID))
                .isInstanceOf(BotRunCommandRejectedException.class)
                .satisfies(exception -> assertThat(((BotRunCommandRejectedException) exception).issues())
                        .extracting(BotExecutionPreflightIssue::code)
                        .containsExactly("INVALID_INITIAL_CAPITAL", "CONCURRENT_EXECUTION_LIMIT_EXCEEDED"));
        assertThat(writes).hasValue(0);
    }

    @Test
    void hidesAnUnownedBotEvenAfterAReadyPreflight() {
        var principal = new TestPrincipal(OWNER_ID);
        var preflight = readyPreflight(principal);
        BotRunCommandPort commandPort = (botId, ownerId, requestedAt) -> Optional.empty();
        var service = new BotRunCommandService(commandPort, preflight, principal, fixedClock());

        assertThatThrownBy(() -> service.issue(BOT_ID))
                .isInstanceOf(BotExecutionPreflightNotFoundException.class)
                .hasMessage("Bot not found");
    }

    private static BotExecutionPreflightService readyPreflight(TestPrincipal principal) {
        var facts = new BotExecutionPreflightFacts(
                BOT_ID,
                new BigDecimal("100000.00"),
                1,
                List.of(),
                true,
                true,
                true,
                List.of());
        return new BotExecutionPreflightService(
                (botId, ownerId, at) -> Optional.of(facts), principal, fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
