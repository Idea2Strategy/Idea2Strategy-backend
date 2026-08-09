package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.Dataset;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.ExecutionPolicy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OfficialBacktestInputSelectorTest {
    private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");
    private static final UUID FEE_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID BARS_30M_OLD = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID BARS_30M = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID BARS_1H = UUID.fromString("20000000-0000-4000-8000-000000000003");
    private static final UUID RAW_30M = UUID.fromString("20000000-0000-4000-8000-000000000004");

    @Test
    void selectsTheNewestOfficialPolicyAndEveryDatasetResolutionRequiredByThePlan() {
        var selected = OfficialBacktestInputSelector.select(plan("30m", "PT1H"), new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", NOW.minusSeconds(60))),
                List.of(
                        dataset(BARS_30M_OLD, "ADJUSTED", "30m", NOW.minusSeconds(120)),
                        dataset(BARS_30M, "ADJUSTED", "30m", NOW.minusSeconds(30)),
                        dataset(BARS_1H, "ADJUSTED", "1h", NOW.minusSeconds(20)),
                        dataset(RAW_30M, "RAW", "30m", NOW.minusSeconds(10))),
                NOW));

        assertThat(selected.policy().version()).isEqualTo("official-v1");
        assertThat(selected.datasets()).extracting(Dataset::id).containsExactly(BARS_30M, BARS_1H);
    }

    @Test
    void failsClosedWhenOneRequiredResolutionHasNoCoherentOfficialDatasetSet() {
        var catalog = new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", NOW.minusSeconds(60))),
                List.of(dataset(BARS_30M, "ADJUSTED", "30m", NOW.minusSeconds(30))),
                NOW);

        assertThatThrownBy(() -> OfficialBacktestInputSelector.select(plan("30m", "PT1H"), catalog))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("30m, 1h");
    }

    private static ExecutionPolicy policy(String version, Instant lockedAt) {
        return new ExecutionPolicy(
                version,
                "market:1.0.0",
                "accounting:1.0.0",
                "precision:1.0.0",
                FEE_ID,
                20,
                BUFFER_ID,
                1,
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-02-01"),
                "market-bars/1",
                lockedAt);
    }

    private static Dataset dataset(UUID id, String layer, String resolution, Instant availableAt) {
        return new Dataset(
                id,
                "ALPACA_SIP_ALL_" + resolution.toUpperCase(),
                layer,
                resolution,
                LocalDate.parse("2024-01-01"),
                LocalDate.parse("2024-02-01"),
                "market-bars/1",
                availableAt);
    }

    private static String plan(String liveResolution, String featureResolution) {
        return """
                {
                  "requiredFeatures":[{"resolution":"%s"}],
                  "executionSnapshot":{"partitions":[{"flows":[{"steps":[
                    {"arguments":{"resolution":"%s"}}
                  ]}]}]}
                }
                """.formatted(featureResolution, liveResolution);
    }
}
