package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import com.idea2strategy.backend.domain.competition.CompetitionType;
import com.idea2strategy.backend.domain.competition.RoomAccessType;
import com.idea2strategy.backend.domain.competition.RoomOrganizerType;
import com.idea2strategy.backend.domain.competition.RoomStatus;
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
@Table(name = "rooms", schema = "competition")
public class CompetitionRoomJpaEntity {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "competition_type", nullable = false, columnDefinition = "competition.competition_type")
    private CompetitionType competitionType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "organizer_type", nullable = false, columnDefinition = "competition.organizer_type")
    private RoomOrganizerType organizerType;

    @Column(name = "creator_account_id")
    private UUID creatorAccountId;

    @Column(name = "created_by_operator_id")
    private UUID createdByOperatorId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "access_type", nullable = false, columnDefinition = "competition.room_access_type")
    private RoomAccessType accessType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "competition.room_status")
    private RoomStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CompetitionRoomJpaEntity() {}

    private CompetitionRoomJpaEntity(CompetitionRoom room) {
        this.id = room.id();
        this.competitionType = room.competitionType();
        this.organizerType = room.organizerType();
        this.creatorAccountId = room.creatorAccountId();
        this.createdByOperatorId = room.createdByOperatorId();
        this.name = room.name();
        this.accessType = room.accessType();
        this.status = room.status();
        this.createdAt = room.createdAt();
    }

    static CompetitionRoomJpaEntity from(CompetitionRoom room) {
        return new CompetitionRoomJpaEntity(room);
    }
}
