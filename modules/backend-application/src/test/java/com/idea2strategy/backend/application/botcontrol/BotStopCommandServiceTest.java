package com.idea2strategy.backend.application.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotStopCommandServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");

    @Test
    void issuesAnOwnedPermanentStopCommand() {
        var expected = new BotStopDispatch(
                BOT_ID,
                MESSAGE_ID,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                BotLifecycleStatus.STOPPING,
                "USER_REQUESTED",
                true);
        var principal = new TestPrincipal(OWNER_ID);
        BotStopCommandPort port = (botId, ownerId, reasonCode, requestedAt) -> Optional.of(expected);
        var service = new BotStopCommandService(port, principal, fixedClock());

        BotStopDispatch result = service.issue(BOT_ID, "USER_REQUESTED");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void rejectsBlankReasonBeforePersistence() {
        var principal = new TestPrincipal(OWNER_ID);
        BotStopCommandPort port = (botId, ownerId, reasonCode, requestedAt) -> {
            throw new AssertionError("invalid command must not reach persistence");
        };
        var service = new BotStopCommandService(port, principal, fixedClock());

        assertThatThrownBy(() -> service.issue(BOT_ID, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("reasonCode must not be blank");
    }

    @Test
    void hidesAnUnownedBot() {
        var principal = new TestPrincipal(OWNER_ID);
        BotStopCommandPort port = (botId, ownerId, reasonCode, requestedAt) -> Optional.empty();
        var service = new BotStopCommandService(port, principal, fixedClock());

        assertThatThrownBy(() -> service.issue(BOT_ID, "USER_REQUESTED"))
                .isInstanceOf(BotExecutionPreflightNotFoundException.class)
                .hasMessage("Bot not found");
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
