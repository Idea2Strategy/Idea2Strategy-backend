package com.idea2strategy.backend.application.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BacktestRequestEnvelopeTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID BOT = id(2);
    private static final UUID DATASET = id(3);
    private static final UUID ROOM = id(4);
    private static final UUID PARTICIPATION = id(5);
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String SNAPSHOT = "sha256:" + "1".repeat(64);
    private static final String PLAN = "sha256:" + "2".repeat(64);

    @Test
    void customProducerKeyIsStableForTheClientKeyButPayloadDetectsReuse() {
        var first = BacktestRequestEnvelope.custom(
                ACCOUNT, BOT, DATASET, LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"),
                SNAPSHOT, PLAN, "accounting-v1", "request-42", NOW.plusSeconds(30));
        var duplicate = BacktestRequestEnvelope.custom(
                ACCOUNT, BOT, DATASET, LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"),
                SNAPSHOT, PLAN, "accounting-v1", "request-42", NOW);
        var conflict = BacktestRequestEnvelope.custom(
                ACCOUNT, BOT, DATASET, LocalDate.parse("2023-01-01"), LocalDate.parse("2023-12-31"),
                SNAPSHOT, PLAN, "accounting-v1", "request-42", NOW);

        assertThat(first.eventType()).isEqualTo("CUSTOM_BACKTEST_REQUESTED");
        assertThat(first.producerIdempotencyKey()).isEqualTo(duplicate.producerIdempotencyKey());
        assertThat(first.messageId()).isEqualTo(duplicate.messageId());
        assertThat(first.requestHash()).isEqualTo(duplicate.requestHash());
        assertThat(conflict.producerIdempotencyKey()).isEqualTo(first.producerIdempotencyKey());
        assertThat(conflict.requestHash()).isNotEqualTo(first.requestHash());
        assertThat(first.payloadDocument()).contains(
                "\"requestReason\":\"USER_PERIOD\"",
                "\"periodStart\":\"2024-01-01\"",
                "\"periodEnd\":\"2024-12-31\"");
    }

    @Test
    void competitionProducerIdentityPinsParticipationAndPlan() {
        var request = BacktestRequestEnvelope.competition(
                ROOM, PARTICIPATION, BOT, "competition-plan.v1", "sha256:" + "3".repeat(64),
                SNAPSHOT, PLAN, "accounting-v1", NOW);

        assertThat(request.eventType()).isEqualTo("COMPETITION_BACKTEST_REQUESTED");
        assertThat(request.aggregateId()).isEqualTo(PARTICIPATION);
        assertThat(request.producerIdempotencyKey()).matches("sha256:[0-9a-f]{64}");
        assertThat(request.payloadDocument()).contains(
                "\"requestReason\":\"COMPETITION_EVALUATION\"",
                "\"roomId\":\"" + ROOM + "\"",
                "\"participationId\":\"" + PARTICIPATION + "\"");
    }

    private static UUID id(int suffix) {
        return UUID.fromString("97000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
