package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "validation_runs", schema = "strategy")
public class StrategyValidationRunJpaEntity {
    @Id
    private UUID id;

    @Column(name = "strategy_id", nullable = false)
    private UUID strategyId;

    @Column(name = "requested_by_account_id", nullable = false)
    private UUID requestedByAccountId;

    @Column(name = "delegated_authorization_id")
    private UUID delegatedAuthorizationId;

    @Column(name = "requested_edit_sequence", nullable = false)
    private long requestedEditSequence;

    @Column(name = "semantic_hash", nullable = false, length = 128)
    private String semanticHash;

    @Column(name = "element_catalog_version_id", nullable = false)
    private UUID elementCatalogVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StrategyValidationStatus status;

    @Column(name = "issue_count", nullable = false)
    private int issueCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result_document", nullable = false, columnDefinition = "jsonb")
    private String resultDocument;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected StrategyValidationRunJpaEntity() {}

    private StrategyValidationRunJpaEntity(StrategyValidationRun run) {
        this.id = run.id();
        this.strategyId = run.strategyId();
        this.requestedByAccountId = run.requestedByAccountId();
        this.delegatedAuthorizationId = run.delegatedAuthorizationId();
        this.requestedEditSequence = run.requestedEditSequence();
        this.semanticHash = run.semanticHash();
        this.elementCatalogVersionId = run.elementCatalogVersionId();
        this.status = run.status();
        this.issueCount = run.issueCount();
        this.resultDocument = StrategyValidationResultJson.write(run.findings());
        this.requestedAt = run.requestedAt();
        this.completedAt = run.completedAt();
    }

    static StrategyValidationRunJpaEntity from(StrategyValidationRun run) {
        return new StrategyValidationRunJpaEntity(run);
    }
}
