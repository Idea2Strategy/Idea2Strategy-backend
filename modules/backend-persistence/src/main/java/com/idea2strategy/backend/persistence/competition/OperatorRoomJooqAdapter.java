package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.OperatorRoomAuthorizationPort;
import com.idea2strategy.backend.application.competition.OperatorRoomQueryPort;
import com.idea2strategy.backend.application.competition.OperatorRoomView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OperatorRoomJooqAdapter implements OperatorRoomAuthorizationPort, OperatorRoomQueryPort {
    private final DSLContext dsl;

    public OperatorRoomJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean authorize(
            UUID operatorId,
            String permissionCode,
            String actionType,
            UUID roomId,
            Instant occurredAt) {
        OffsetDateTime at = occurredAt.atOffset(ZoneOffset.UTC);
        boolean allowed = Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from operations.operator_accounts oa "
                        + "join operations.operator_role_assignments ora on ora.operator_account_id = oa.id "
                        + "join operations.roles r on r.id = ora.role_id "
                        + "join operations.role_permissions rp on rp.role_id = r.id "
                        + "join operations.permissions p on p.id = rp.permission_id "
                        + "where oa.id = ? and oa.status = 'ACTIVE' and r.status = 'ACTIVE' "
                        + "and ora.granted_at <= ?::timestamptz and ora.revoked_at is null "
                        + "and (ora.expires_at is null or ora.expires_at > ?::timestamptz) and p.code = ?)",
                operatorId, at, at, permissionCode));
        UUID auditId = UUID.randomUUID();
        dsl.execute(
                "insert into operations.audit_events "
                        + "(id, actor_type, actor_id, action_type, target_domain, target_id, reason_code, "
                        + "correlation_id, idempotency_key, occurred_at) "
                        + "values (?, 'OPERATOR', ?, ?, 'competition_room', ?, ?, ?, ?, ?::timestamptz)",
                auditId,
                operatorId,
                actionType,
                roomId,
                allowed ? "PERMISSION_GRANTED" : "PERMISSION_DENIED",
                UUID.randomUUID(),
                "operator-room-authorization:" + auditId,
                at);
        return allowed;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OperatorRoomView> findOfficialRoom(UUID roomId) {
        Record room = dsl.fetchOne(
                "select r.id, r.name, r.competition_type::text as competition_type, "
                        + "r.access_type::text as access_type, r.status::text as status, r.created_at, "
                        + "r.ended_at, r.invalidated_at, r.invalidation_reason_code, "
                        + "rs.evaluation_starts_at, rs.evaluation_ends_at, "
                        + "rr.scoring_template_version_id, rr.rules_hash "
                        + "from competition.rooms r "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "where r.id = ? and r.organizer_type = 'PLATFORM'::competition.organizer_type",
                roomId);
        if (room == null) {
            return Optional.empty();
        }
        var roomEvents = dsl.fetch(
                "select event_sequence, event_type, resulting_status::text as resulting_status, "
                        + "reason_code, occurred_at from competition.room_events "
                        + "where room_id = ? order by event_sequence",
                roomId).map(record -> new OperatorRoomView.RoomEvent(
                        record.get("event_sequence", Integer.class),
                        record.get("event_type", String.class),
                        record.get("resulting_status", String.class),
                        record.get("reason_code", String.class),
                        instant(record, "occurred_at")));
        var participationEvents = dsl.fetch(
                "select p.anonymous_alias, pe.event_sequence, pe.event_type, pe.reason_code, pe.occurred_at "
                        + "from competition.participation_events pe "
                        + "join competition.participations p on p.id = pe.participation_id "
                        + "where p.room_id = ? order by pe.occurred_at, p.anonymous_alias, pe.event_sequence",
                roomId).map(record -> new OperatorRoomView.ParticipationEvent(
                        record.get("anonymous_alias", String.class),
                        record.get("event_sequence", Long.class),
                        record.get("event_type", String.class),
                        record.get("reason_code", String.class),
                        instant(record, "occurred_at")));
        return Optional.of(new OperatorRoomView(
                new OperatorRoomView.RoomSummary(
                        room.get("id", UUID.class),
                        room.get("name", String.class),
                        room.get("competition_type", String.class),
                        room.get("access_type", String.class),
                        room.get("status", String.class),
                        instant(room, "created_at"),
                        instant(room, "evaluation_starts_at"),
                        instant(room, "evaluation_ends_at"),
                        instant(room, "ended_at"),
                        instant(room, "invalidated_at"),
                        room.get("invalidation_reason_code", String.class),
                        room.get("scoring_template_version_id", UUID.class),
                        room.get("rules_hash", String.class)),
                roomEvents,
                participationEvents,
                finalResult(roomId)));
    }

    private OperatorRoomView.FinalResult finalResult(UUID roomId) {
        Record snapshot = dsl.fetchOne(
                "select id, status::text as status, cutoff_at, result_hash, scoring_template_version_id "
                        + "from competition.leaderboard_snapshots where room_id = ? "
                        + "and status = 'FINAL'::competition.leaderboard_status "
                        + "order by cutoff_at desc, created_at desc, id desc limit 1",
                roomId);
        if (snapshot == null) {
            return null;
        }
        UUID snapshotId = snapshot.get("id", UUID.class);
        var entries = dsl.fetch(
                "select p.anonymous_alias, le.rank, le.is_joint_rank, le.eligibility_status, le.score, "
                        + "le.calculation_document ->> 'provenanceHash' as provenance_hash "
                        + "from competition.leaderboard_entries le "
                        + "join competition.participations p on p.id = le.participation_id "
                        + "where le.snapshot_id = ? order by le.rank nulls last, p.anonymous_alias",
                snapshotId).map(record -> new OperatorRoomView.FinalEntry(
                        record.get("anonymous_alias", String.class),
                        record.get("rank", Integer.class),
                        record.get("is_joint_rank", Boolean.class),
                        record.get("eligibility_status", String.class),
                        record.get("score", BigDecimal.class),
                        record.get("provenance_hash", String.class)));
        return new OperatorRoomView.FinalResult(
                snapshotId,
                snapshot.get("status", String.class),
                instant(snapshot, "cutoff_at"),
                snapshot.get("result_hash", String.class),
                snapshot.get("scoring_template_version_id", UUID.class),
                entries);
    }

    private static Instant instant(Record record, String field) {
        OffsetDateTime value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
