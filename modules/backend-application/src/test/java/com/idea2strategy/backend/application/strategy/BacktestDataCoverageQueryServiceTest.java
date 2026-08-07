package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage.FeedResolution;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BacktestDataCoverageQueryServiceTest {
    private static final UUID CATALOG_ID = UUID.fromString("0f2a0000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

    @Test
    void reportsTheObservedFeedsAndFeaturesAgainstTheCatalogsOwnDataRequirementVersion() {
        var port = new RecordingPort(
                Set.of(new FeedResolution("ADJUSTED_BAR", "1m")),
                Set.of("RSI_14"));
        var service = new BacktestDataCoverageQueryService(port, Clock.fixed(NOW, ZoneOffset.UTC));

        var coverage = service.coverageFor(catalog("alpaca-sip/v1"));

        assertThat(coverage.dataRequirementVersion()).isEqualTo("alpaca-sip/v1");
        assertThat(coverage.feeds()).containsExactly(new FeedResolution("ADJUSTED_BAR", "1m"));
        assertThat(coverage.features()).containsExactly("RSI_14");
        assertThat(port.feedsObservedAt).isEqualTo(NOW);
        assertThat(port.featuresObservedAt).isEqualTo(NOW);
        assertThat(port.catalogId).isEqualTo(CATALOG_ID);
    }

    @Test
    void reportsEmptyCoverageWhenNothingIsPublishedYetWithoutFabricatingTheVersion() {
        var port = new RecordingPort(Set.of(), Set.of());
        var service = new BacktestDataCoverageQueryService(port, Clock.fixed(NOW, ZoneOffset.UTC));

        var coverage = service.coverageFor(catalog("alpaca-sip/v1"));

        assertThat(coverage.feeds()).isEmpty();
        assertThat(coverage.features()).isEmpty();
        assertThat(coverage.dataRequirementVersion()).isEqualTo("alpaca-sip/v1");
    }

    private static BasicStrategyCatalog catalog(String dataRequirementVersion) {
        return new BasicStrategyCatalog(
                new ElementCatalogVersion(
                        CATALOG_ID,
                        "basic/v1",
                        "basic-semantic/v1",
                        "basic-elements:2026-08-07",
                        dataRequirementVersion,
                        "sha256:" + "0".repeat(64),
                        NOW.minusSeconds(60),
                        null),
                List.of(),
                List.of(),
                List.of());
    }

    private static final class RecordingPort implements BacktestDataCoverageQueryPort {
        private final Set<FeedResolution> feeds;
        private final Set<String> features;
        private Instant feedsObservedAt;
        private Instant featuresObservedAt;
        private UUID catalogId;

        private RecordingPort(Set<FeedResolution> feeds, Set<String> features) {
            this.feeds = feeds;
            this.features = features;
        }

        @Override
        public Set<FeedResolution> findAvailableFeeds(Instant observedAt) {
            feedsObservedAt = observedAt;
            return feeds;
        }

        @Override
        public Set<String> findAvailableFeatures(UUID elementCatalogVersionId, Instant observedAt) {
            catalogId = elementCatalogVersionId;
            featuresObservedAt = observedAt;
            return features;
        }
    }
}
