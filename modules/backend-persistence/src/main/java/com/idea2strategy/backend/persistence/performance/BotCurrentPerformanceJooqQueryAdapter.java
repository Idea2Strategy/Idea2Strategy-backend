package com.idea2strategy.backend.persistence.performance;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.performance.BotCurrentPerformanceQueryPort;
import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class BotCurrentPerformanceJooqQueryAdapter implements BotCurrentPerformanceQueryPort {
    private final DSLContext dsl;

    public BotCurrentPerformanceJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<BotCurrentPerformance> findByBotId(UUID botId) {
        var projections = table(name("performance", "bot_current_projections"));
        var id = field(name("bot_id"), UUID.class);
        var equityAmount = field(name("equity_amount"), BigDecimal.class);
        var totalReturnPct = field(name("total_return_pct"), BigDecimal.class);
        var maxDrawdownPct = field(name("max_drawdown_pct"), BigDecimal.class);
        var sharpeRatio = field(name("sharpe_ratio"), BigDecimal.class);
        var metricsDocument = field(name("metrics_document"), JSONB.class);
        var ledgerStateHash = field(name("ledger_state_hash"), String.class);
        var positionStateHash = field(name("position_state_hash"), String.class);
        var calculationRulesVersion = field(name("calculation_rules_version"), String.class);
        var lastEventSequence = field(name("last_event_sequence"), Long.class);
        var projectionHash = field(name("projection_hash"), String.class);
        var updatedAt = field(name("updated_at"), OffsetDateTime.class);

        return dsl.select(
                        id,
                        equityAmount,
                        totalReturnPct,
                        maxDrawdownPct,
                        sharpeRatio,
                        metricsDocument,
                        ledgerStateHash,
                        positionStateHash,
                        calculationRulesVersion,
                        lastEventSequence,
                        projectionHash,
                        updatedAt)
                .from(projections)
                .where(id.eq(botId))
                .fetchOptional(record -> new BotCurrentPerformance(
                        record.get(id),
                        record.get(equityAmount),
                        record.get(totalReturnPct),
                        record.get(maxDrawdownPct),
                        record.get(sharpeRatio),
                        record.get(metricsDocument).data(),
                        record.get(ledgerStateHash),
                        record.get(positionStateHash),
                        record.get(calculationRulesVersion),
                        record.get(lastEventSequence),
                        record.get(projectionHash),
                        record.get(updatedAt).toInstant()));
    }
}
