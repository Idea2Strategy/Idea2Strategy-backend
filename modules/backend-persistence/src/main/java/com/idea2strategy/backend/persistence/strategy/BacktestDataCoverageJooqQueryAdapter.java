package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage.FeedResolution;
import com.idea2strategy.backend.application.strategy.BacktestDataCoverageQueryPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class BacktestDataCoverageJooqQueryAdapter implements BacktestDataCoverageQueryPort {
    private final DSLContext dsl;

    public BacktestDataCoverageJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * A feed counts as readable only when a published manifest carries content for it. The feed row
     * alone proves the feed is modelled, not that history exists, so the manifest is the authority.
     *
     * <p>This returns the feed's data kind, which is what the element execution contract compares
     * against — and that comparison cannot currently succeed. The contract requires
     * {@code ADJUSTED_BAR@1m}, but {@code ADJUSTED_BAR} is not a data kind anywhere in the
     * publication model: the pipeline writes {@code BARS} for every bar feed and expresses adjustment
     * as {@code dataset_manifests.data_layer = 'ADJUSTED'}, a column this query does not read.
     * Renaming the token would therefore accept raw bars for a feature whose definition says raw bars
     * are not substitutable, so the mismatch is left visible rather than papered over. Adjusted bars
     * are also published only at {@code 30m}. The required-feed expression is under review in
     * {@code proposals/strategy-domain-decisions} §7; until it is settled this reports what the
     * publication model actually contains.
     */
    @Override
    public Set<FeedResolution> findAvailableFeeds(Instant observedAt) {
        OffsetDateTime at = observedAt.atOffset(ZoneOffset.UTC);
        Set<FeedResolution> feeds = new LinkedHashSet<>();
        dsl.fetch("""
                select distinct feed.data_kind as data_kind, manifest.resolution as resolution
                  from market_data.dataset_manifests manifest
                  join market_data.feeds feed on feed.id = manifest.feed_id
                 where manifest.status = 'AVAILABLE'
                   and manifest.available_at is not null
                   and manifest.available_at <= ?::timestamptz
                   and btrim(manifest.dataset_hash) <> ''
                   and (feed.retired_at is null or feed.retired_at > ?::timestamptz)
                 order by data_kind, resolution
                """, at, at)
                .forEach(record -> feeds.add(new FeedResolution(
                        record.get("data_kind", String.class).trim(),
                        record.get("resolution", String.class).trim())));
        return feeds;
    }

    /**
     * A registered feature definition only declares that the calculation exists. It is readable by a
     * backtest once a materialization for it has succeeded and become available, so both are required.
     */
    @Override
    public Set<String> findAvailableFeatures(UUID elementCatalogVersionId, Instant observedAt) {
        OffsetDateTime at = observedAt.atOffset(ZoneOffset.UTC);
        Set<String> features = new LinkedHashSet<>();
        dsl.fetch("""
                select distinct definition.feature_code as feature_code
                  from market_data.feature_definitions definition
                  join market_data.feature_materializations materialization
                    on materialization.feature_definition_id = definition.id
                 where definition.element_catalog_version_id = ?::uuid
                   and materialization.status = 'SUCCEEDED'
                   and materialization.available_at is not null
                   and materialization.available_at <= ?::timestamptz
                 order by feature_code
                """, elementCatalogVersionId, at)
                .forEach(record -> features.add(record.get("feature_code", String.class).trim()));
        return features;
    }
}
