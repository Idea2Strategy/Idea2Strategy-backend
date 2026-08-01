package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
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
@Table(name = "strategies", schema = "strategy")
public class StrategyJpaEntity {
    @Id
    private UUID id;

    @Column(name = "owner_account_id", nullable = false)
    private UUID ownerAccountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "mode", nullable = false, columnDefinition = "strategy.strategy_mode")
    private StrategyMode mode;

    @Column(nullable = false, length = 120)
    private String name;

    @Column
    private String description;

    @Column(name = "edit_sequence", nullable = false)
    private long editSequence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StrategyJpaEntity() {}

    private StrategyJpaEntity(Strategy strategy) {
        this.id = strategy.id();
        this.ownerAccountId = strategy.ownerAccountId();
        this.mode = strategy.mode();
        this.name = strategy.name();
        this.description = strategy.description();
        this.editSequence = strategy.editSequence();
        this.createdAt = strategy.createdAt();
        this.updatedAt = strategy.updatedAt();
    }

    static StrategyJpaEntity from(Strategy strategy) {
        return new StrategyJpaEntity(strategy);
    }
}
