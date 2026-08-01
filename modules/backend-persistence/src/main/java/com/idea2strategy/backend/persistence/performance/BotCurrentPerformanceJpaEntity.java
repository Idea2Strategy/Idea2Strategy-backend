package com.idea2strategy.backend.persistence.performance;

import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bot_current_projections", schema = "performance")
public class BotCurrentPerformanceJpaEntity {
    @Id
    @Column(name = "bot_id")
    private UUID botId;

    @Column(name = "equity_amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal equityAmount;

    @Column(name = "total_return_pct", nullable = false, precision = 18, scale = 8)
    private BigDecimal totalReturnPct;

    @Column(name = "max_drawdown_pct", nullable = false, precision = 18, scale = 8)
    private BigDecimal maxDrawdownPct;

    @Column(name = "sharpe_ratio", precision = 18, scale = 8)
    private BigDecimal sharpeRatio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_document", nullable = false, columnDefinition = "jsonb")
    private String metricsDocument;

    @Column(name = "ledger_state_hash", nullable = false, length = 128)
    private String ledgerStateHash;

    @Column(name = "position_state_hash", nullable = false, length = 128)
    private String positionStateHash;

    @Column(name = "calculation_rules_version", nullable = false, length = 80)
    private String calculationRulesVersion;

    @Column(name = "last_event_sequence", nullable = false)
    private long lastEventSequence;

    @Column(name = "projection_hash", nullable = false, length = 128)
    private String projectionHash;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BotCurrentPerformanceJpaEntity() {}

    private BotCurrentPerformanceJpaEntity(BotCurrentPerformance performance) {
        this.botId = performance.botId();
        this.equityAmount = performance.equityAmount();
        this.totalReturnPct = performance.totalReturnPct();
        this.maxDrawdownPct = performance.maxDrawdownPct();
        this.sharpeRatio = performance.sharpeRatio();
        this.metricsDocument = performance.metricsDocument();
        this.ledgerStateHash = performance.ledgerStateHash();
        this.positionStateHash = performance.positionStateHash();
        this.calculationRulesVersion = performance.calculationRulesVersion();
        this.lastEventSequence = performance.lastEventSequence();
        this.projectionHash = performance.projectionHash();
        this.updatedAt = performance.updatedAt();
    }

    static BotCurrentPerformanceJpaEntity from(BotCurrentPerformance performance) {
        return new BotCurrentPerformanceJpaEntity(performance);
    }
}
