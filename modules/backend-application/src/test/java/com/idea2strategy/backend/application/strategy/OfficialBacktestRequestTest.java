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
    private static final String PLAN_CHECKSUM = "sha256:" + "d".repeat(64);

    @Test
    void derivesTheSameVersionedContractForEveryRetryOfOneRelease() {
        var release = release();
        UUID datasetId = UUID.fromString("70000000-0000-4000-8000-000000000001");

        var first = OfficialBacktestRequest.forRelease(release, datasetId, "backtest-policy-v1");
        var retry = OfficialBacktestRequest.forRelease(release, datasetId, "backtest-policy-v1");

        assertThat(retry).isEqualTo(first);
        assertThat(first.metadata().contractVersion()).isEqualTo("strategy-bot.v1");
        assertThat(first.metadata().messageType()).isEqualTo("OFFICIAL_BACKTEST_REQUESTED");
        assertThat(first.metadata().idempotencyKey()).matches("sha256:[0-9a-f]{64}");
        assertThat(first.expectedSnapshotHash()).isEqualTo("sha256:" + HASH_C);
        assertThat(first.compiledPlanChecksum()).isEqualTo(PLAN_CHECKSUM);
        assertThat(first.runId()).isEqualTo(retry.runId());
        assertThat(first.executionPolicyVersion()).isEqualTo("backtest-policy-v1");
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
                "strategy-bot.v1", "basic-compiled-plan.v1", PLAN_CHECKSUM,
                "{\"contractVersion\":\"strategy-bot.v1\"}");
    }
    @Test
    void namesTheChecksumOfThePlanTheReleaseActuallyPublished() {
        // The consumer resolves this checksum against bot.launch_contract_plans, and that row is
        // written from release.contractPlan().planChecksum(). Naming any other digest points the
        // worker at a row that was never stored: it fails with
        //   JobNotSatisfiable: compiled plan sha256:... is not resolvable
        // before the simulation starts, which is what INT03 run c0df2755 hit (root #439).
        var release = release();
        var request = OfficialBacktestRequest.forRelease(
                release, UUID.fromString("40000000-0000-4000-8000-000000000001"), "backtest-policy-v1");

        assertThat(request.compiledPlanChecksum())
                .as("the request must name the published contract plan, not a separately compiled digest")
                .isEqualTo(release.contractPlan().planChecksum());
    }
}
