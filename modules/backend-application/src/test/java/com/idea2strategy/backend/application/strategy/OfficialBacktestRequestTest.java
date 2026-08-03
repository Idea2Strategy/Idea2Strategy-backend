package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialBacktestRequestTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";

    @Test
    void derivesTheSameVersionedContractForEveryRetryOfOneRelease() {
        var release = release();
        UUID datasetId = UUID.fromString("70000000-0000-4000-8000-000000000001");

        var first = OfficialBacktestRequest.forRelease(release, HASH_B, datasetId);
        var retry = OfficialBacktestRequest.forRelease(release, HASH_B, datasetId);

        assertThat(retry).isEqualTo(first);
        assertThat(first.metadata().contractVersion()).isEqualTo("strategy-bot.v1");
        assertThat(first.metadata().messageType()).isEqualTo("OFFICIAL_BACKTEST_REQUESTED");
        assertThat(first.metadata().idempotencyKey()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.expectedSnapshotHash()).isEqualTo("sha256:" + HASH_C);
        assertThat(first.compiledPlanChecksum()).isEqualTo("sha256:" + HASH_B);
        assertThat(first.requestReason()).isEqualTo("STRATEGY_RELEASE");
    }

    private static ImmutableStrategyRelease release() {
        UUID botId = UUID.fromString("10000000-0000-4000-8000-000000000001");
        UUID ownerId = UUID.fromString("20000000-0000-4000-8000-000000000001");
        UUID feeId = UUID.fromString("30000000-0000-4000-8000-000000000001");
        UUID bufferId = UUID.fromString("40000000-0000-4000-8000-000000000001");
        UUID instrumentId = UUID.fromString("50000000-0000-4000-8000-000000000001");
        var configuration = new ImmutableStrategyRelease.LaunchConfiguration(
                new BigDecimal("100000.00"), "broker/v1", "accounting/v1", "precision/v1",
                feeId, bufferId, "{}", HASH_A);
        var flow = new ImmutableStrategyRelease.Flow(
                UUID.randomUUID(), "flow", UUID.randomUUID(), UUID.randomUUID(), "{}", "{}",
                HASH_A, HASH_B, HASH_A, List.of(instrumentId), List.of(), 0);
        var partition = new ImmutableStrategyRelease.Partition(
                UUID.randomUUID(), "partition", null, 10_000, HASH_A, List.of(flow));
        return new ImmutableStrategyRelease(
                botId, ownerId, "Bot", null, "{}", "{}", HASH_A, HASH_B, HASH_C,
                configuration, partition, contractPlan(), Instant.parse("2026-08-01T09:00:00Z"));
    }

    private static ImmutableStrategyRelease.ContractPlan contractPlan() {
        return new ImmutableStrategyRelease.ContractPlan(
                "strategy-bot.v1", "basic-compiled-plan.v1", "sha256:" + "c".repeat(64),
                "{\"contractVersion\":\"strategy-bot.v1\"}");
    }
}
