package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.ConsumedRoomInvitation;
import com.idea2strategy.backend.application.competition.RoomInvitationIssueRequest;
import com.idea2strategy.backend.application.competition.RoomInvitationPort;
import com.idea2strategy.backend.application.competition.RoomInvitationRecord;
import com.idea2strategy.backend.domain.competition.RoomInvitationCredentialType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomInvitationJooqAdapter implements RoomInvitationPort {
    private final DSLContext dsl;

    public RoomInvitationJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public Optional<RoomInvitationRecord> issue(RoomInvitationIssueRequest request) {
        OffsetDateTime issuedAt = utc(request.issuedAt());
        return dsl.resultQuery(
                        """
                        insert into competition.room_invitations
                            (id, room_id, issued_by_account_id, credential_type,
                             credential_digest, issued_at, expires_at)
                        select ?, room.id, ?, cast(? as competition.invitation_credential_type),
                               ?, cast(? as timestamptz),
                               least(cast(? as timestamptz), schedule.participation_closes_at)
                        from competition.rooms room
                        join competition.room_schedules schedule on schedule.room_id = room.id
                        where room.id = ?
                          and room.organizer_type = 'USER'
                          and room.creator_account_id = ?
                          and room.access_type = 'SECRET'
                          and room.status in ('DRAFT', 'RECRUITING')
                          and least(cast(? as timestamptz), schedule.participation_closes_at)
                              > cast(? as timestamptz)
                        returning id, room_id, credential_type::text, expires_at
                        """,
                        request.id(),
                        request.issuerAccountId(),
                        request.credentialType().name(),
                        request.credentialDigest(),
                        issuedAt,
                        utc(request.requestedExpiresAt()),
                        request.roomId(),
                        request.issuerAccountId(),
                        utc(request.requestedExpiresAt()),
                        issuedAt)
                .fetchOptional(record -> new RoomInvitationRecord(
                        record.get("id", UUID.class),
                        record.get("room_id", UUID.class),
                        RoomInvitationCredentialType.valueOf(record.get("credential_type", String.class)),
                        record.get("expires_at", OffsetDateTime.class).toInstant()));
    }

    @Override
    @Transactional
    public boolean revoke(UUID roomId, UUID invitationId, UUID actorAccountId, Instant revokedAt) {
        return dsl.execute(
                        """
                        update competition.room_invitations invitation
                           set revoked_at = cast(? as timestamptz), revocation_reason_code = 'OWNER_REVOKED'
                         where invitation.id = ?
                           and invitation.room_id = ?
                           and invitation.revoked_at is null
                           and exists (
                               select 1
                                 from competition.rooms room
                                where room.id = invitation.room_id
                                  and room.organizer_type = 'USER'
                                  and room.creator_account_id = ?
                                  and room.access_type = 'SECRET'
                           )
                        """,
                        utc(revokedAt),
                        invitationId,
                        roomId,
                        actorAccountId)
                == 1;
    }

    @Override
    @Transactional
    public Optional<ConsumedRoomInvitation> consume(String credentialDigest, Instant consumedAt) {
        OffsetDateTime now = utc(consumedAt);
        return dsl.resultQuery(
                        """
                        update competition.room_invitations invitation
                           set revoked_at = cast(? as timestamptz), revocation_reason_code = 'CONSUMED'
                         where invitation.credential_digest = ?
                           and invitation.revoked_at is null
                           and invitation.expires_at > cast(? as timestamptz)
                           and exists (
                               select 1
                                 from competition.rooms room
                                 join competition.room_schedules schedule on schedule.room_id = room.id
                                where room.id = invitation.room_id
                                  and room.access_type = 'SECRET'
                                  and room.status in ('DRAFT', 'RECRUITING')
                                  and schedule.participation_closes_at > cast(? as timestamptz)
                           )
                        returning id, room_id
                        """,
                        now,
                        credentialDigest,
                        now,
                        now)
                .fetchOptional(record -> new ConsumedRoomInvitation(
                        record.get("id", UUID.class), record.get("room_id", UUID.class)));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
