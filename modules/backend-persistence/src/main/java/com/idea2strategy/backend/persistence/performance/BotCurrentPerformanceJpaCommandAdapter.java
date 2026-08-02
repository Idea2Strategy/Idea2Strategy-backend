package com.idea2strategy.backend.persistence.performance;

import com.idea2strategy.backend.application.performance.BotCurrentPerformanceCommandPort;
import com.idea2strategy.backend.application.performance.OfficialLivePerformanceProjection;
import com.idea2strategy.backend.application.performance.ProjectionWriteDecision;
import java.time.ZoneOffset;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BotCurrentPerformanceJpaCommandAdapter implements BotCurrentPerformanceCommandPort {
    private final DSLContext dsl;

    public BotCurrentPerformanceJpaCommandAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public ProjectionWriteDecision save(OfficialLivePerformanceProjection projection) {
        var performance = projection.performance();
        int affected = dsl.execute(
                """
                insert into performance.bot_current_projections
                    (bot_id, equity_amount, total_return_pct, max_drawdown_pct, sharpe_ratio,
                     metrics_document, ledger_state_hash, position_state_hash,
                     calculation_rules_version, last_event_sequence, projection_hash, updated_at)
                values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?, ?, ?, cast(? as timestamptz))
                on conflict (bot_id) do update set
                    equity_amount = excluded.equity_amount,
                    total_return_pct = excluded.total_return_pct,
                    max_drawdown_pct = excluded.max_drawdown_pct,
                    sharpe_ratio = excluded.sharpe_ratio,
                    metrics_document = excluded.metrics_document,
                    ledger_state_hash = excluded.ledger_state_hash,
                    position_state_hash = excluded.position_state_hash,
                    calculation_rules_version = excluded.calculation_rules_version,
                    last_event_sequence = excluded.last_event_sequence,
                    projection_hash = excluded.projection_hash,
                    updated_at = excluded.updated_at
                where performance.bot_current_projections.last_event_sequence < excluded.last_event_sequence
                """,
                performance.botId(),
                performance.equityAmount(),
                performance.totalReturnPct(),
                performance.maxDrawdownPct(),
                performance.sharpeRatio(),
                performance.metricsDocument(),
                performance.ledgerStateHash(),
                performance.positionStateHash(),
                performance.calculationRulesVersion(),
                performance.lastEventSequence(),
                performance.projectionHash(),
                performance.updatedAt().atOffset(ZoneOffset.UTC));
        return affected == 1
                ? ProjectionWriteDecision.APPLIED
                : ProjectionWriteDecision.IGNORED_STALE_OR_DUPLICATE;
    }
}
