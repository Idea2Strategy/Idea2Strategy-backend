package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "room_schedules", schema = "competition")
public class CompetitionRoomScheduleJpaEntity {
    @Id
    @Column(name = "room_id")
    private UUID roomId;

    @Column(name = "recruitment_opens_at", nullable = false)
    private Instant recruitmentOpensAt;

    @Column(name = "participation_opens_at", nullable = false)
    private Instant participationOpensAt;

    @Column(name = "evaluation_starts_at", nullable = false)
    private Instant evaluationStartsAt;

    @Column(name = "participation_closes_at", nullable = false)
    private Instant participationClosesAt;

    @Column(name = "evaluation_ends_at", nullable = false)
    private Instant evaluationEndsAt;

    @Column(name = "finalization_deadline_at", nullable = false)
    private Instant finalizationDeadlineAt;

    @Column(name = "timezone_name", nullable = false, length = 80)
    private String timezoneName;

    protected CompetitionRoomScheduleJpaEntity() {}

    private CompetitionRoomScheduleJpaEntity(CompetitionRoom room) {
        this.roomId = room.id();
        this.recruitmentOpensAt = room.schedule().recruitmentOpensAt();
        this.participationOpensAt = room.schedule().participationOpensAt();
        this.evaluationStartsAt = room.schedule().evaluationStartsAt();
        this.participationClosesAt = room.schedule().participationClosesAt();
        this.evaluationEndsAt = room.schedule().evaluationEndsAt();
        this.finalizationDeadlineAt = room.schedule().finalizationDeadlineAt();
        this.timezoneName = room.schedule().timezoneName();
    }

    static CompetitionRoomScheduleJpaEntity from(CompetitionRoom room) {
        return new CompetitionRoomScheduleJpaEntity(room);
    }
}
