package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "live_room_rules", schema = "competition")
public class CompetitionLiveRoomRulesJpaEntity {
    @Id
    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "stopped_bot_slot_policy", nullable = false, length = 30)
    private String stoppedBotSlotPolicy;

    @Column(name = "minimum_operation_seconds", nullable = false)
    private long minimumOperationSeconds;

    @Column(name = "minimum_fill_count", nullable = false)
    private int minimumFillCount;

    protected CompetitionLiveRoomRulesJpaEntity() {}

    private CompetitionLiveRoomRulesJpaEntity(CompetitionRoom room) {
        this.roomId = room.id();
        this.stoppedBotSlotPolicy = room.liveRules().stoppedBotSlotPolicy();
        this.minimumOperationSeconds = room.liveRules().minimumOperationSeconds();
        this.minimumFillCount = room.liveRules().minimumFillCount();
    }

    static CompetitionLiveRoomRulesJpaEntity from(CompetitionRoom room) {
        return new CompetitionLiveRoomRulesJpaEntity(room);
    }
}
