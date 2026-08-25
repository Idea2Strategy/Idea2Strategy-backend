package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.Dataset;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.ExecutionPolicy;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalogQueryPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class StrategyReleaseInputCatalogJooqQueryAdapter implements StrategyReleaseInputCatalogQueryPort {
    private final DSLContext dsl;

    public StrategyReleaseInputCatalogJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public StrategyReleaseInputCatalog findSelectableAt(Instant observedAt) {
        OffsetDateTime at = observedAt.atOffset(ZoneOffset.UTC);
        var policies = dsl.fetch("""
                select p.version,
                       p.policy_document ->> 'marketRulesVersion' as broker_rules_version,
                       p.policy_document ->> 'accountingRulesVersion' as accounting_rules_version,
                       p.policy_document ->> 'precisionRulesVersion' as precision_rules_version,
                       p.policy_document ->> 'periodStart' as period_start,
                       p.policy_document ->> 'periodEnd' as period_end,
                       p.policy_document ->> 'marketDataSchemaVersion' as market_data_schema_version,
                       p.policy_document ->> 'timezone' as timezone,
                       p.locked_at,
                       f.id as fee_policy_id, f.fee_rate_bps,
                       b.id as buffer_policy_id, b.buffer_bps
                  from backtest.execution_policy_versions p
                  join trading.fee_policy_versions f
                    on f.id = (p.policy_document ->> 'feePolicyId')::uuid
                  join trading.buying_power_buffer_policy_versions b
                    on b.id = (p.policy_document ->> 'buyingPowerBufferPolicyId')::uuid
                 where p.locked_at <= ?::timestamptz
                   and (p.retired_at is null or p.retired_at > ?::timestamptz)
                   and f.effective_from <= ?::timestamptz
                   and (f.effective_to is null or f.effective_to > ?::timestamptz)
                   and b.effective_from <= ?::timestamptz
                   and (b.effective_to is null or b.effective_to > ?::timestamptz)
                   and p.policy_document ->> 'marketRulesVersion' is not null
                   and p.policy_document ->> 'accountingRulesVersion' is not null
                   and p.policy_document ->> 'precisionRulesVersion' is not null
                   and p.policy_document ->> 'periodStart' is not null
                   and p.policy_document ->> 'periodEnd' is not null
                   and p.policy_document ->> 'marketDataSchemaVersion' is not null
                   and p.policy_document ->> 'timezone' is not null
                   and exists (
                       select 1
                         from market_data.dataset_manifests candidate
                        where candidate.status = 'AVAILABLE'
                          and candidate.data_layer = 'ADJUSTED'
                          and candidate.available_at is not null
                          and candidate.available_at <= ?::timestamptz
                          and candidate.schema_version = p.policy_document ->> 'marketDataSchemaVersion'
                          and (candidate.period_start at time zone 'UTC')::date >=
                              ((p.policy_document ->> 'periodStart')::timestamptz
                                  at time zone (p.policy_document ->> 'timezone'))::date
                          and (candidate.period_end at time zone 'UTC')::date <=
                              ((p.policy_document ->> 'periodEnd')::timestamptz
                                  at time zone (p.policy_document ->> 'timezone'))::date
                   )
                 order by p.locked_at desc, p.version
                """, at, at, at, at, at, at, at).map(row -> new ExecutionPolicy(
                row.get("version", String.class),
                row.get("broker_rules_version", String.class),
                row.get("accounting_rules_version", String.class),
                row.get("precision_rules_version", String.class),
                row.get("fee_policy_id", UUID.class),
                row.get("fee_rate_bps", Integer.class),
                row.get("buffer_policy_id", UUID.class),
                row.get("buffer_bps", Integer.class),
                localDate(row.get("period_start", String.class), row.get("timezone", String.class)),
                localDate(row.get("period_end", String.class), row.get("timezone", String.class)),
                row.get("market_data_schema_version", String.class),
                row.get("locked_at", OffsetDateTime.class).toInstant()));

        var datasets = dsl.fetch("""
                select d.id, f.code as feed_code, d.data_layer, d.resolution,
                       d.period_start::date as period_start,
                       d.period_end::date as period_end, d.schema_version, d.available_at
                 from market_data.dataset_manifests d
                  join market_data.feeds f on f.id = d.feed_id
                 where d.status = 'AVAILABLE'
                   and d.data_layer = 'ADJUSTED'
                   and d.available_at is not null
                   and d.available_at <= ?::timestamptz
                   and btrim(d.dataset_hash) <> ''
                 order by d.period_end desc, d.period_start, d.id
                """, at).map(row -> new Dataset(
                row.get("id", UUID.class),
                row.get("feed_code", String.class),
                row.get("data_layer", String.class),
                row.get("resolution", String.class),
                row.get("period_start", LocalDate.class),
                row.get("period_end", LocalDate.class),
                row.get("schema_version", String.class),
                row.get("available_at", OffsetDateTime.class).toInstant()));
        return new StrategyReleaseInputCatalog(policies, datasets, observedAt);
    }

    private static LocalDate localDate(String instant, String timezone) {
        return OffsetDateTime.parse(instant).atZoneSameInstant(ZoneId.of(timezone)).toLocalDate();
    }
}
