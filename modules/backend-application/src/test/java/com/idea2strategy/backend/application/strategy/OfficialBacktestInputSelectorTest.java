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
                .hasMessageContaining("1h");
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

    @Test
    void selectsTheMinimumOrderedManifestCoverForEveryRequiredResolution() {
        UUID bars30mFull = UUID.fromString("50000000-0000-4000-8000-000000000001");
        UUID bars30m2024 = UUID.fromString("50000000-0000-4000-8000-000000000002");
        UUID bars30m2025 = UUID.fromString("50000000-0000-4000-8000-000000000003");
        UUID bars1h2024 = UUID.fromString("50000000-0000-4000-8000-000000000004");
        UUID bars1h2025 = UUID.fromString("50000000-0000-4000-8000-000000000005");
        var catalog = new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", "2024-01-01", "2026-01-01", NOW.minusSeconds(60))),
                List.of(
                        dataset(bars30mFull, "ADJUSTED", "30m", "2024-01-01", "2026-01-01", NOW.minusSeconds(10)),
                        dataset(bars30m2024, "ADJUSTED", "30m", "2024-01-01", "2025-01-01", NOW.minusSeconds(20)),
                        dataset(bars30m2025, "ADJUSTED", "30m", "2025-01-01", "2026-01-01", NOW.minusSeconds(20)),
                        dataset(bars1h2024, "ADJUSTED", "1h", "2024-01-01", "2025-01-01", NOW.minusSeconds(20)),
                        dataset(bars1h2025, "ADJUSTED", "1h", "2025-01-01", "2026-01-01", NOW.minusSeconds(20))),
                NOW);

        var selected = OfficialBacktestInputSelector.select(
                plan("30m", "1h"), LocalDate.parse("2024-02-01"), LocalDate.parse("2025-12-01"), catalog);

        assertThat(selected.datasets()).extracting(Dataset::id)
                .containsExactly(bars30mFull, bars1h2024, bars1h2025);
    }

    @Test
    void failsClosedAndNamesTheResolutionWhenSegmentedCoverageHasAGap() {
        UUID first = UUID.fromString("60000000-0000-4000-8000-000000000001");
        UUID second = UUID.fromString("60000000-0000-4000-8000-000000000002");
        var catalog = new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", "2024-01-01", "2026-01-01", NOW.minusSeconds(60))),
                List.of(
                        dataset(BARS_30M, "ADJUSTED", "30m", "2024-01-01", "2026-01-01", NOW.minusSeconds(20)),
                        dataset(first, "ADJUSTED", "1h", "2024-01-01", "2025-01-01", NOW.minusSeconds(20)),
                        dataset(second, "ADJUSTED", "1h", "2025-02-01", "2026-01-01", NOW.minusSeconds(20))),
                NOW);

        assertThatThrownBy(() -> OfficialBacktestInputSelector.select(
                plan("30m", "1h"), LocalDate.parse("2024-02-01"), LocalDate.parse("2025-12-01"), catalog))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("1h")
                .hasMessageContaining("2025-01-01");
    }

    @Test
    void neverUsesAnInstrumentScopedManifestForAnotherInstrument() {
        UUID aapl = UUID.fromString("70000000-0000-4000-8000-000000000001");
        UUID msft = UUID.fromString("70000000-0000-4000-8000-000000000002");
        UUID aaplBars = UUID.fromString("70000000-0000-4000-8000-000000000003");
        UUID msftBars = UUID.fromString("70000000-0000-4000-8000-000000000004");
        var catalog = new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", NOW.minusSeconds(60))),
                List.of(
                        new Dataset(aaplBars, aapl, "AAPL", "ADJUSTED", "30m", 1,
                                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-02-01"),
                                "market-bars/1", NOW.minusSeconds(20)),
                        new Dataset(msftBars, msft, "MSFT", "ADJUSTED", "30m", 2,
                                LocalDate.parse("2024-01-01"), LocalDate.parse("2024-02-01"),
                                "market-bars/1", NOW.minusSeconds(10))),
                NOW);
        String plan = """
                {"executionSnapshot":{"partitions":[{"flows":[{
                  "officialInstrumentIds":["%s"],
                  "steps":[{"arguments":{"resolution":"30m"}}]
                }]}]}}
                """.formatted(aapl);

        var selected = OfficialBacktestInputSelector.select(
                plan, LocalDate.parse("2024-01-05"), LocalDate.parse("2024-01-25"), catalog);

        assertThat(selected.datasets()).extracting(Dataset::id).containsExactly(aaplBars);
    }

    @Test
    void rejectsOverlappingSegmentsThatTheWorkerCannotBind() {
        UUID first = UUID.fromString("71000000-0000-4000-8000-000000000001");
        UUID overlap = UUID.fromString("71000000-0000-4000-8000-000000000002");
        var catalog = new StrategyReleaseInputCatalog(
                List.of(policy("official-v1", "2024-01-01", "2024-04-01", NOW.minusSeconds(60))),
                List.of(
                        dataset(first, "ADJUSTED", "30m", "2024-01-01", "2024-03-01", NOW.minusSeconds(20)),
                        dataset(overlap, "ADJUSTED", "30m", "2024-02-01", "2024-04-01", NOW.minusSeconds(10))),
                NOW);

        assertThatThrownBy(() -> OfficialBacktestInputSelector.select(
                plan("30m", "30m"), LocalDate.parse("2024-01-05"), LocalDate.parse("2024-03-15"), catalog))
                .isInstanceOf(ImmutableStrategyReleaseRejectedException.class)
                .hasMessageContaining("30m");
    }

    private static ExecutionPolicy policy(String version, Instant lockedAt) {
        return policy(version, "2024-01-01", "2024-02-01", lockedAt);
    }

    private static ExecutionPolicy policy(
            String version, String periodStart, String periodEnd, Instant lockedAt) {
        return new ExecutionPolicy(
                version,
                "market:1.0.0",
                "accounting:1.0.0",
                "precision:1.0.0",
                FEE_ID,
                20,
                BUFFER_ID,
                1,
                LocalDate.parse(periodStart),
                LocalDate.parse(periodEnd),
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
