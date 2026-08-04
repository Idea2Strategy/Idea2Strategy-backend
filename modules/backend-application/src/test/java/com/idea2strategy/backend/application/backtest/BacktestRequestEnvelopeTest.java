package com.idea2strategy.backend.application.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BacktestRequestEnvelopeTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID BOT = id(2);
    private static final UUID DATASET = id(3);
    private static final UUID ROOM = id(4);
    private static final UUID PARTICIPATION = id(5);
    private static final UUID PERIOD_1 = id(6);
    private static final UUID PERIOD_2 = id(7);
    private static final UUID SCORING = id(8);
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final String SNAPSHOT = "sha256:" + "1".repeat(64);
    private static final String PLAN = "sha256:" + "2".repeat(64);

    @Test
    void customProducerKeyIsStableForTheClientKeyButPayloadDetectsReuse() {
        var first = BacktestRequestEnvelope.custom(
                ACCOUNT, BOT, DATASET, "sha256:" + "3".repeat(64),
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"),
                SNAPSHOT, PLAN, "us-supported-universe:2026-08-04", new BigDecimal("100000.00000000"),
                "accounting-v1", "request-42", NOW.plusSeconds(30));
        var duplicate = BacktestRequestEnvelope.custom(
                ACCOUNT, BOT, DATASET, "sha256:" + "3".repeat(64),
                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-12-31"),
                SNAPSHOT, PLAN, "us-supported-universe:2026-08-04", new BigDecimal("100000.00000000"),
                "accounting-v1", "request-42", NOW);
        var conflict = BacktestRequestEnvelope.custom(
                ACCOUNT, BOT, DATASET, "sha256:" + "3".repeat(64),
                LocalDate.parse("2023-01-01"), LocalDate.parse("2023-12-31"),
                SNAPSHOT, PLAN, "us-supported-universe:2026-08-04", new BigDecimal("100000.00000000"),
                "accounting-v1", "request-42", NOW);

        assertThat(first.eventType()).isEqualTo("CUSTOM_BACKTEST_REQUESTED");
        assertThat(first.producerIdempotencyKey()).isEqualTo(duplicate.producerIdempotencyKey());
        assertThat(first.messageId()).isEqualTo(duplicate.messageId());
        assertThat(first.requestHash()).isEqualTo(duplicate.requestHash());
        assertThat(conflict.producerIdempotencyKey()).isEqualTo(first.producerIdempotencyKey());
        assertThat(conflict.requestHash()).isNotEqualTo(first.requestHash());
        assertThat(first.payloadDocument()).contains(
                "\"requestReason\":\"USER_PERIOD\"",
                "\"requestingAccountId\":\"" + ACCOUNT + "\"",
                "\"expectedDatasetHash\":\"sha256:" + "3".repeat(64) + "\"",
                "\"instrumentCatalogVersion\":\"us-supported-universe:2026-08-04\"",
                "\"initialCashAmount\":\"100000.00000000\"",
                "\"periodStart\":\"2024-01-01\"",
                "\"periodEnd\":\"2024-12-31\"");
    }

    @Test
    void competitionProducerIdentityPinsParticipationAndPlan() {
        var request = BacktestRequestEnvelope.competition(
                ROOM, PARTICIPATION, BOT, "competition-plan.v1", "sha256:" + "3".repeat(64),
                SNAPSHOT, PLAN, "accounting-v1", SCORING, "sha256:" + "4".repeat(64),
                new BigDecimal("100000.00000000"), "USD", List.of(
                        period(PERIOD_2, 2, "2025-07-01", "2025-12-31", DATASET, "6"),
                        period(PERIOD_1, 1, "2025-01-01", "2025-06-30", id(9), "5")), NOW);

        assertThat(request.eventType()).isEqualTo("COMPETITION_BACKTEST_REQUESTED");
        assertThat(request.aggregateId()).isEqualTo(PARTICIPATION);
        assertThat(request.producerIdempotencyKey()).matches("sha256:[0-9a-f]{64}");
        assertThat(request.payloadDocument()).contains(
                "\"requestReason\":\"COMPETITION_EVALUATION\"",
                "\"roomId\":\"" + ROOM + "\"",
                "\"participationId\":\"" + PARTICIPATION + "\"",
                "\"scoringTemplateVersionId\":\"" + SCORING + "\"",
                "\"roomRulesHash\":\"sha256:" + "4".repeat(64) + "\"",
                "\"initialCashAmount\":\"100000.00000000\"",
                "\"currencyCode\":\"USD\"",
                "\"periodSequence\":1",
                "\"periodSequence\":2",
                "\"expectedDatasetHash\":\"sha256:" + "5".repeat(64) + "\"");
        assertThat(request.payloadDocument().indexOf("\"periodSequence\":1"))
                .isLessThan(request.payloadDocument().indexOf("\"periodSequence\":2"));
    }

    private static BacktestRequestEnvelope.CompetitionPeriod period(
            UUID periodId, int sequence, String start, String end, UUID datasetId, String hashDigit) {
        return new BacktestRequestEnvelope.CompetitionPeriod(
                periodId, sequence, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal("0.5"),
                "sha256:" + hashDigit.repeat(64),
                List.of(new BacktestRequestEnvelope.CompetitionDataset(
                        datasetId, "MARKET_BARS", "sha256:" + hashDigit.repeat(64))),
                List.of());
    }

    private static UUID id(int suffix) {
        return UUID.fromString("97000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
