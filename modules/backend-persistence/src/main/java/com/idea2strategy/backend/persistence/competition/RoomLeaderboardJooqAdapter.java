package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQuery;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQuery;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQueryPort;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQueryResult;
import com.idea2strategy.backend.application.competition.RoomLeaderboardSummary;
import com.idea2strategy.backend.application.competition.ViewerParticipationState;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RoomLeaderboardJooqAdapter implements RoomLeaderboardQueryPort {
    private final DSLContext dsl;
    private final AnonymousLeaderboardJooqAdapter leaderboard;

    public RoomLeaderboardJooqAdapter(DSLContext dsl, AnonymousLeaderboardJooqAdapter leaderboard) {
        this.dsl = dsl;
        this.leaderboard = leaderboard;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RoomLeaderboardQueryResult queryRoomLeaderboard(RoomLeaderboardQuery query) {
        var publicRows = leaderboard.query(new AnonymousLeaderboardQuery(
                query.roomId(), query.viewerAccountId(), query.snapshotId(),
                query.leaderboardAfterRank(), query.leaderboardAfterAnchor(), query.leaderboardLimit()));
        if (publicRows.snapshotId() == null) {
            return RoomLeaderboardQueryResult.empty();
        }
        var ownedRows = leaderboard.queryOwned(new AnonymousLeaderboardQuery(
                query.roomId(), query.viewerAccountId(), publicRows.snapshotId(),
                query.ownedAfterRank(), query.ownedAfterAnchor(), query.ownedLimit()));
        if (!publicRows.snapshotId().equals(ownedRows.snapshotId())) {
            throw new IllegalStateException("Integrated leaderboard queries did not share a snapshot");
        }

        Record room = dsl.fetchOne(
                "select r.id, r.name, r.competition_type::text as competition_type, "
                        + "r.organizer_type::text as organizer_type, r.access_type::text as access_type, "
                        + "r.status::text as status, r.ended_at, rs.evaluation_starts_at, "
                        + "rs.evaluation_ends_at, rr.scoring_template_version_id, rr.rules_hash "
                        + "from competition.rooms r "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "join competition.room_rules rr on rr.room_id = r.id where r.id = ?",
                query.roomId());
        if (room == null) {
            return RoomLeaderboardQueryResult.empty();
        }
        var participations = dsl.fetch(
                "select anonymous_alias, status::text as status, joined_at, evaluation_started_at, "
                        + "evaluation_finished_at from competition.participations "
                        + "where room_id = ? and owner_account_id = ? order by joined_at, id",
                query.roomId(), query.viewerAccountId()).map(record -> new ViewerParticipationState(
                        record.get("anonymous_alias", String.class),
                        record.get("status", String.class),
                        instant(record, "joined_at"),
                        instant(record, "evaluation_started_at"),
                        instant(record, "evaluation_finished_at")));
        return new RoomLeaderboardQueryResult(
                new RoomLeaderboardSummary(
                        room.get("id", UUID.class),
                        room.get("name", String.class),
                        room.get("competition_type", String.class),
                        room.get("organizer_type", String.class),
                        room.get("access_type", String.class),
                        room.get("status", String.class),
                        instant(room, "evaluation_starts_at"),
                        instant(room, "evaluation_ends_at"),
                        instant(room, "ended_at"),
                        room.get("scoring_template_version_id", UUID.class),
                        room.get("rules_hash", String.class)),
                participations,
                publicRows,
                ownedRows);
    }

    private static java.time.Instant instant(Record record, String field) {
        OffsetDateTime value = record.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
