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

    @Test
    void selectsTheBestCoherentDatasetSetThatCoversTheRequestedPeriod() {
        UUID long30m = UUID.fromString("30000000-0000-4000-8000-000000000001");
        UUID long1h = UUID.fromString("30000000-0000-4000-8000-000000000002");
        UUID short30m = UUID.fromString("30000000-0000-4000-8000-000000000003");
        UUID short1h = UUID.fromString("30000000-0000-4000-8000-000000000004");
        var catalog = new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", NOW.minusSeconds(60))),
                List.of(
                        dataset(long30m, "ADJUSTED", "30m", "2024-01-01", "2024-01-31", NOW.minusSeconds(120)),
                        dataset(long1h, "ADJUSTED", "1h", "2024-01-01", "2024-01-31", NOW.minusSeconds(120)),
                        dataset(short30m, "ADJUSTED", "30m", "2024-01-15", "2024-02-01", NOW.minusSeconds(30)),
                        dataset(short1h, "ADJUSTED", "1h", "2024-01-15", "2024-02-01", NOW.minusSeconds(30))),
                NOW);

        var selected = OfficialBacktestInputSelector.select(
                plan("30m", "PT1H"), LocalDate.parse("2024-01-05"), LocalDate.parse("2024-01-25"), catalog);

        assertThat(selected.datasets()).extracting(Dataset::id).containsExactly(long30m, long1h);
    }

    @Test
    void prefersTheNewestCoherentRevisionEvenWhenItsPeriodEndDiffers() {
        UUID old30m = UUID.fromString("40000000-0000-4000-8000-000000000001");
        UUID old1h = UUID.fromString("40000000-0000-4000-8000-000000000002");
        UUID revised30m = UUID.fromString("40000000-0000-4000-8000-000000000003");
        UUID revised1h = UUID.fromString("40000000-0000-4000-8000-000000000004");
        var catalog = new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", NOW.minusSeconds(60))),
                List.of(
                        dataset(old30m, "ADJUSTED", "30m", 1,
                                "2024-01-01", "2024-02-01", NOW.minusSeconds(30)),
                        dataset(old1h, "ADJUSTED", "1h", 1,
                                "2024-01-01", "2024-02-01", NOW.minusSeconds(30)),
                        dataset(revised30m, "ADJUSTED", "30m", 2,
                                "2024-01-01", "2024-01-31", NOW.minusSeconds(120)),
                        dataset(revised1h, "ADJUSTED", "1h", 2,
                                "2024-01-01", "2024-01-31", NOW.minusSeconds(120))),
                NOW);

        var selected = OfficialBacktestInputSelector.select(
                plan("30m", "PT1H"), LocalDate.parse("2024-01-05"), LocalDate.parse("2024-01-25"), catalog);

        assertThat(selected.datasets()).extracting(Dataset::id).containsExactly(revised30m, revised1h);
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
        return dataset(id, layer, resolution, "2024-01-01", "2024-02-01", availableAt);
    }

    private static Dataset dataset(
            UUID id, String layer, String resolution, String periodStart, String periodEnd, Instant availableAt) {
        return dataset(id, layer, resolution, 1, periodStart, periodEnd, availableAt);
    }

    private static Dataset dataset(
            UUID id, String layer, String resolution, int revisionNumber,
            String periodStart, String periodEnd, Instant availableAt) {
        return new Dataset(
                id,
                "ALPACA_SIP_ALL_" + resolution.toUpperCase(),
                layer,
                resolution,
                revisionNumber,
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
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
