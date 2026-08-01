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
import org.junit.jupiter.api.Test;

class BotExecutionPreflightServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID FEATURE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    @Test
    void acceptsAnOwnedBotOnlyWhenEveryExecutionBoundaryIsReady() {
        var facts = new BotExecutionPreflightFacts(
                BOT_ID,
                new BigDecimal("100000.00"),
                10,
                List.of(),
                true,
                true,
                true,
                List.of());
        var service = service((botId, ownerId, at) -> Optional.of(facts));

        BotExecutionPreflightReport report = service.validate(BOT_ID);

        assertThat(report.botId()).isEqualTo(BOT_ID);
        assertThat(report.ready()).isTrue();
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void returnsEveryBlockingReasonWithoutSubstitutingDefaults() {
        var missingData = new BotExecutionPreflightFacts.DataRequirement(INSTRUMENT_ID, FEATURE_ID);
        var facts = new BotExecutionPreflightFacts(
                BOT_ID,
                BigDecimal.ZERO,
                11,
                List.of(INSTRUMENT_ID),
                false,
                false,
                false,
                List.of(missingData));
        var service = service((botId, ownerId, at) -> Optional.of(facts));

        BotExecutionPreflightReport report = service.validate(BOT_ID);

        assertThat(report.ready()).isFalse();
        assertThat(report.issues()).extracting(BotExecutionPreflightIssue::code).containsExactly(
                "INVALID_INITIAL_CAPITAL",
                "CONCURRENT_EXECUTION_LIMIT_EXCEEDED",
                "UNSUPPORTED_INSTRUMENT",
                "FEE_POLICY_INACTIVE",
                "BUYING_POWER_BUFFER_POLICY_INACTIVE",
                "RISK_POLICY_MISSING",
                "DATA_NOT_READY");
        assertThat(report.issues().get(2).detail()).contains(INSTRUMENT_ID.toString());
        assertThat(report.issues().get(6).detail()).contains(INSTRUMENT_ID.toString(), FEATURE_ID.toString());
    }

    @Test
    void hidesBotsNotOwnedByTheCurrentPrincipal() {
        var service = service((botId, ownerId, at) -> Optional.empty());

        assertThatThrownBy(() -> service.validate(BOT_ID))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("Bot not found");
    }

    private static BotExecutionPreflightService service(BotExecutionPreflightQueryPort port) {
        return new BotExecutionPreflightService(
                port,
                new TestPrincipal(OWNER_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
