package com.idea2strategy.backend.persistence.botcontrol;

import com.idea2strategy.backend.domain.botcontrol.Bot;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
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
@Table(name = "bots", schema = "bot")
public class BotJpaEntity {
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

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "lifecycle_status", nullable = false, columnDefinition = "bot.lifecycle_status")
    private BotLifecycleStatus lifecycleStatus;

    @Column(name = "lifecycle_changed_at", nullable = false)
    private Instant lifecycleChangedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "execution_eligible_from", nullable = false)
    private Instant executionEligibleFrom;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "stop_requested_at")
    private Instant stopRequestedAt;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "stop_reason_code", length = 80)
    private String stopReasonCode;

    @Column(name = "edit_sequence", nullable = false)
    private long editSequence;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BotJpaEntity() {}

    private BotJpaEntity(Bot bot) {
        this.id = bot.id();
        this.ownerAccountId = bot.ownerAccountId();
        this.mode = bot.mode();
        this.name = bot.name();
        this.lifecycleStatus = bot.lifecycleStatus();
        this.lifecycleChangedAt = bot.lifecycleChangedAt();
        this.createdAt = bot.createdAt();
        this.executionEligibleFrom = bot.executionEligibleFrom();
        this.startedAt = bot.startedAt();
        this.stopRequestedAt = bot.stopRequestedAt();
        this.stoppedAt = bot.stoppedAt();
        this.stopReasonCode = bot.stopReasonCode();
        this.editSequence = bot.editSequence();
        this.updatedAt = bot.updatedAt();
    }

    static BotJpaEntity from(Bot bot) {
        return new BotJpaEntity(bot);
    }
}
