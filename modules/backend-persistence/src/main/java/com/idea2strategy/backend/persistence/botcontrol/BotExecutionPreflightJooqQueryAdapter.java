package com.idea2strategy.backend.persistence.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightFacts;
import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightQueryPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class BotExecutionPreflightJooqQueryAdapter implements BotExecutionPreflightQueryPort {
    private final DSLContext dsl;

    public BotExecutionPreflightJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<BotExecutionPreflightFacts> findOwnedById(
            UUID botId, UUID ownerAccountId, Instant evaluatedAt) {
        var at = evaluatedAt.atOffset(ZoneOffset.UTC);
        var bot = dsl.fetchOne(
                "select c.initial_cash_amount, "
                        + "((select count(*) from bot.bots active "
                        + "where active.owner_account_id = b.owner_account_id "
                        + "and active.lifecycle_status = 'RUNNING' and active.deleted_at is null) "
                        + "+ case when b.lifecycle_status = 'RUNNING' then 0 else 1 end)::int "
                        + "as projected_count, "
                        + "exists(select 1 from trading.fee_policy_versions f where f.id = c.fee_policy_id "
                        + "and f.effective_from <= ?::timestamptz "
                        + "and (f.effective_to is null or f.effective_to > ?::timestamptz)) as fee_active, "
                        + "exists(select 1 from trading.buying_power_buffer_policy_versions p "
                        + "where p.id = c.buying_power_buffer_policy_id "
                        + "and p.effective_from <= ?::timestamptz "
                        + "and (p.effective_to is null or p.effective_to > ?::timestamptz)) as buffer_active, "
                        + "(jsonb_typeof(c.candidate_conflict_policy) = 'object' "
                        + "and c.candidate_conflict_policy <> '{}'::jsonb) as risk_configured "
                        + "from bot.bots b join bot.launch_configurations c on c.bot_id = b.id "
                        + "where b.id = ? and b.owner_account_id = ? and b.deleted_at is null",
                at, at, at, at, botId, ownerAccountId);
        if (bot == null) {
            return Optional.empty();
        }

        List<UUID> unsupportedInstruments = dsl.fetch(
                        "select distinct i.id from bot.bot_partitions p "
                                + "join bot.flows f on f.partition_id = p.id "
                                + "join bot.flow_instruments fi on fi.flow_id = f.id "
                                + "join market_data.instruments i on i.id = fi.instrument_id "
                                + "where p.bot_id = ? and ("
                                + "(i.delisted_at is not null and i.delisted_at < (?::timestamptz)::date) or "
                                + "not exists(select 1 from market_data.instrument_symbols s "
                                + "where s.instrument_id = i.id and s.effective_from <= ?::timestamptz "
                                + "and (s.effective_to is null or s.effective_to > ?::timestamptz))) "
                                + "order by i.id",
                        botId, at, at, at)
                .getValues("id", UUID.class);

        List<BotExecutionPreflightFacts.DataRequirement> unavailableData = dsl.fetch(
                        "select distinct r.instrument_id, r.feature_definition_id "
                                + "from bot.bot_partitions p "
                                + "join bot.flows f on f.partition_id = p.id "
                                + "join bot.flow_feature_requirements r on r.flow_id = f.id "
                                + "join market_data.feature_definitions d on d.id = r.feature_definition_id "
                                + "where p.bot_id = ? and not exists ("
                                + "select 1 from market_data.feeds feed "
                                + "join market_data.providers provider on provider.id = feed.provider_id "
                                + "join market_data.stream_watermarks watermark on watermark.feed_id = feed.id "
                                + "where provider.status = 'ACTIVE' and feed.resolution = d.resolution "
                                + "and (feed.retired_at is null or feed.retired_at > ?::timestamptz) "
                                + "and watermark.last_ingested_at <= ?::timestamptz) "
                                + "order by r.instrument_id, r.feature_definition_id",
                        botId, at, at)
                .map(record -> new BotExecutionPreflightFacts.DataRequirement(
                        record.get("instrument_id", UUID.class),
                        record.get("feature_definition_id", UUID.class)));

        return Optional.of(new BotExecutionPreflightFacts(
                botId,
                bot.get("initial_cash_amount", BigDecimal.class),
                bot.get("projected_count", Integer.class),
                unsupportedInstruments,
                Boolean.TRUE.equals(bot.get("fee_active", Boolean.class)),
                Boolean.TRUE.equals(bot.get("buffer_active", Boolean.class)),
                Boolean.TRUE.equals(bot.get("risk_configured", Boolean.class)),
                unavailableData));
    }
}
