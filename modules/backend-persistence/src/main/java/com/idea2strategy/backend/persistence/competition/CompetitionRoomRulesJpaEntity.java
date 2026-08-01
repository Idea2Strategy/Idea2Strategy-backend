package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
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
@Table(name = "room_rules", schema = "competition")
public class CompetitionRoomRulesJpaEntity {
    @Id
    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "scoring_template_version_id", nullable = false)
    private UUID scoringTemplateVersionId;

    @Column(name = "initial_cash_amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal initialCashAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency_code", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currencyCode;

    @Column(name = "bot_participation_limit", nullable = false)
    private int botParticipationLimit;

    @Column(name = "per_account_bot_limit", nullable = false)
    private int perAccountBotLimit;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eligibility_document", nullable = false, columnDefinition = "jsonb")
    private String eligibilityDocument;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "market_scope_document", nullable = false, columnDefinition = "jsonb")
    private String marketScopeDocument;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scoring_parameters", nullable = false, columnDefinition = "jsonb")
    private String scoringParameters;

    @Column(name = "fee_policy_id", nullable = false)
    private UUID feePolicyId;

    @Column(name = "slippage_rate_bps", nullable = false)
    private int slippageRateBps;

    @Column(name = "buying_power_buffer_policy_id", nullable = false)
    private UUID buyingPowerBufferPolicyId;

    @Column(name = "precision_rules_version", nullable = false, length = 80)
    private String precisionRulesVersion;

    @Column(name = "rules_hash", nullable = false, length = 128)
    private String rulesHash;

    @Column(name = "locked_at", nullable = false)
    private Instant lockedAt;

    protected CompetitionRoomRulesJpaEntity() {}

    private CompetitionRoomRulesJpaEntity(CompetitionRoom room) {
        this.roomId = room.id();
        this.scoringTemplateVersionId = room.scoringTemplateVersionId();
        this.initialCashAmount = room.initialCashAmount();
        this.currencyCode = room.currencyCode();
        this.botParticipationLimit = room.botParticipationLimit();
        this.perAccountBotLimit = room.perAccountBotLimit();
        this.eligibilityDocument = room.eligibilityDocument();
        this.marketScopeDocument = room.marketScopeDocument();
        this.scoringParameters = room.scoringParameters();
        this.feePolicyId = room.feePolicyId();
        this.slippageRateBps = room.slippageRateBps();
        this.buyingPowerBufferPolicyId = room.buyingPowerBufferPolicyId();
        this.precisionRulesVersion = room.precisionRulesVersion();
        this.rulesHash = room.rulesHash();
        this.lockedAt = room.lockedAt();
    }

    static CompetitionRoomRulesJpaEntity from(CompetitionRoom room) {
        return new CompetitionRoomRulesJpaEntity(room);
    }
}
