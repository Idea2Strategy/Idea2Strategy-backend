package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.AnonymousLeaderboardItem;
import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQuery;
import com.idea2strategy.backend.application.competition.InvalidLeaderboardCursorException;
import com.idea2strategy.backend.application.competition.LeaderboardAccessException;
import com.idea2strategy.backend.application.competition.LeaderboardAuthenticationException;
import com.idea2strategy.backend.application.competition.LeaderboardQueryPort;
import com.idea2strategy.backend.application.competition.LeaderboardQueryResult;
import com.idea2strategy.backend.application.competition.LeaderboardQueryRow;
import com.idea2strategy.backend.application.competition.OwnedLeaderboardEvidence;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AnonymousLeaderboardJooqAdapter implements LeaderboardQueryPort {
    private final DSLContext dsl;

    public AnonymousLeaderboardJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(readOnly = true)
    public LeaderboardQueryResult query(AnonymousLeaderboardQuery query) {
        if (query.viewerAccountId() == null || !isActiveAccount(query.viewerAccountId())) {
            throw new LeaderboardAuthenticationException("An active authenticated account is required");
        }
        Record room = dsl.fetchOne(
                "select access_type::text as access_type, status::text as status "
                        + "from competition.rooms where id = ?",
                query.roomId());
        if (room == null) {
            return LeaderboardQueryResult.empty();
        }
        String roomStatus = room.get("status", String.class);
        if ((!"EVALUATING".equals(roomStatus) && !"ENDED".equals(roomStatus))
                || endedForInsufficientParticipation(query.roomId())) {
            return LeaderboardQueryResult.empty();
        }
        if ("SECRET".equals(room.get("access_type", String.class))
                && !isValidParticipant(query.roomId(), query.viewerAccountId())) {
            throw new LeaderboardAccessException("Secret room leaderboard requires an active room participation");
        }

        Record snapshot = query.snapshotId() == null
                ? dsl.fetchOne(
                        "select ls.id, ls.status::text as status, ls.cutoff_at "
                                + "from competition.leaderboard_snapshots ls "
                                + "join competition.rooms r on r.id = ls.room_id "
                                + "where ls.room_id = ? and r.status in "
                                + "('EVALUATING'::competition.room_status, 'ENDED'::competition.room_status) "
                                + "and ls.status in "
                                + "('PUBLISHED'::competition.leaderboard_status, "
                                + "'FINAL'::competition.leaderboard_status) "
                                + "and not exists(select 1 from competition.room_events re "
                                + "where re.room_id = r.id and re.reason_code = 'INSUFFICIENT_PARTICIPATION') "
                                + "order by ls.cutoff_at desc, ls.created_at desc, ls.id desc limit 1",
                        query.roomId())
                : dsl.fetchOne(
                        "select ls.id, ls.status::text as status, ls.cutoff_at "
                                + "from competition.leaderboard_snapshots ls "
                                + "join competition.rooms r on r.id = ls.room_id "
                                + "where ls.id = ? and ls.room_id = ? and r.status in "
                                + "('EVALUATING'::competition.room_status, 'ENDED'::competition.room_status) "
                                + "and ls.status in "
                                + "('PUBLISHED'::competition.leaderboard_status, "
                                + "'FINAL'::competition.leaderboard_status) "
                                + "and not exists(select 1 from competition.room_events re "
                                + "where re.room_id = r.id and re.reason_code = 'INSUFFICIENT_PARTICIPATION')",
                        query.snapshotId(), query.roomId());
        if (snapshot == null) {
            if (query.snapshotId() != null) {
                throw new InvalidLeaderboardCursorException("cursor snapshot is invalid for this room");
            }
            return LeaderboardQueryResult.empty();
        }

        UUID snapshotId = snapshot.get("id", UUID.class);
        UUID afterParticipationId = query.afterAnchor() == null
                ? null
                : resolveAnchorParticipation(snapshotId, query.afterRank(), query.afterAnchor());
        var rows = dsl.fetch(
                "select le.participation_id, le.rank, le.is_joint_rank, p.anonymous_alias, le.score, "
                        + "le.eligibility_status, ps.equity_amount, "
                        + "coalesce(ps.total_return_pct, bar.weighted_return_pct) as total_return_pct, "
                        + "coalesce(ps.max_drawdown_pct, bar.weighted_max_drawdown_pct) as max_drawdown_pct, "
                        + "coalesce(ps.sharpe_ratio, bar.weighted_sharpe_ratio) as sharpe_ratio, "
                        + "case when p.owner_account_id = ? then true else false end as viewer_owned, "
                        + "case when p.owner_account_id = ? then p.bot_id end as owned_bot_id, "
                        + "case when p.owner_account_id = ? then le.performance_snapshot_id end "
                        + "as owned_performance_snapshot_id, "
                        + "case when p.owner_account_id = ? then le.backtest_aggregate_result_id end "
                        + "as owned_backtest_result_id, "
                        + "case when p.owner_account_id = ? then le.eligibility_reason_code end "
                        + "as owned_eligibility_reason_code "
                        + "from competition.leaderboard_entries le "
                        + "join competition.participations p on p.id = le.participation_id "
                        + "left join performance.bot_snapshots ps on ps.id = le.performance_snapshot_id "
                        + "left join competition.backtest_aggregate_results bar "
                        + "on bar.id = le.backtest_aggregate_result_id "
                        + "where le.snapshot_id = ? and p.status not in "
                        + "('WITHDRAWN'::competition.participation_status, "
                        + "'EXPELLED'::competition.participation_status) and "
                        + "(le.eligibility_status = 'ELIGIBLE' or p.owner_account_id = ?) and "
                        + "(?::int is null or le.rank > ? or (le.rank = ? and le.participation_id > ?)) "
                        + "order by le.rank, le.participation_id limit ?",
                query.viewerAccountId(), query.viewerAccountId(), query.viewerAccountId(),
                query.viewerAccountId(), query.viewerAccountId(), snapshotId,
                query.viewerAccountId(), query.afterRank(), query.afterRank(), query.afterRank(), afterParticipationId,
                query.limit());
        if (!isActiveAccount(query.viewerAccountId())) {
            throw new LeaderboardAuthenticationException("An active authenticated account is required");
        }
        Record currentRoom = dsl.fetchOne(
                "select access_type::text as access_type, status::text as status "
                        + "from competition.rooms where id = ?",
                query.roomId());
        if (currentRoom == null
                || (!"EVALUATING".equals(currentRoom.get("status", String.class))
                        && !"ENDED".equals(currentRoom.get("status", String.class)))
                || endedForInsufficientParticipation(query.roomId())) {
            return LeaderboardQueryResult.empty();
        }
        if ("SECRET".equals(currentRoom.get("access_type", String.class))
                && !isValidParticipant(query.roomId(), query.viewerAccountId())) {
            throw new LeaderboardAccessException("Secret room leaderboard requires an active room participation");
        }
        return new LeaderboardQueryResult(
                snapshotId,
                snapshot.get("status", String.class),
                snapshot.get("cutoff_at", OffsetDateTime.class).toInstant(),
                rows.map(record -> mapRow(snapshotId, record)));
    }

    private boolean isActiveAccount(UUID accountId) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from identity.accounts "
                        + "where id = ? and lifecycle_status = 'ACTIVE'::identity.account_lifecycle_status)",
                accountId));
    }

    private boolean endedForInsufficientParticipation(UUID roomId) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from competition.room_events "
                        + "where room_id = ? and reason_code = 'INSUFFICIENT_PARTICIPATION')",
                roomId));
    }

    private boolean isValidParticipant(UUID roomId, UUID viewerAccountId) {
        if (viewerAccountId == null) {
            return false;
        }
        return Boolean.TRUE.equals(dsl.fetchValue(
                "select exists(select 1 from competition.participations "
                        + "where room_id = ? and owner_account_id = ? and status not in "
                        + "('WITHDRAWN'::competition.participation_status, "
                        + "'EXPELLED'::competition.participation_status))",
                roomId,
                viewerAccountId));
    }

    private UUID resolveAnchorParticipation(UUID snapshotId, int rank, String anchor) {
        var candidates = dsl.fetch(
                "select le.participation_id from competition.leaderboard_entries le "
                        + "where le.snapshot_id = ? and le.rank = ?",
                snapshotId, rank);
        return candidates.stream()
                .map(record -> record.get("participation_id", UUID.class))
                .filter(participationId -> anchor.equals(rowAnchor(snapshotId, rank, participationId)))
                .findFirst()
                .orElseThrow(() -> new InvalidLeaderboardCursorException("cursor anchor is invalid"));
    }

    private LeaderboardQueryRow mapRow(UUID snapshotId, Record record) {
        UUID participationId = record.get("participation_id", UUID.class);
        OwnedLeaderboardEvidence owned = null;
        if (Boolean.TRUE.equals(record.get("viewer_owned", Boolean.class))) {
            owned = new OwnedLeaderboardEvidence(
                    record.get("owned_bot_id", UUID.class),
                    participationId,
                    record.get("owned_performance_snapshot_id", UUID.class),
                    record.get("owned_backtest_result_id", UUID.class),
                    record.get("owned_eligibility_reason_code", String.class));
        }
        return new LeaderboardQueryRow(
                rowAnchor(snapshotId, record.get("rank", Integer.class), participationId),
                new AnonymousLeaderboardItem(
                        record.get("rank", Integer.class),
                        record.get("is_joint_rank", Boolean.class),
                        record.get("anonymous_alias", String.class),
                        record.get("score", BigDecimal.class),
                        record.get("eligibility_status", String.class),
                        record.get("equity_amount", BigDecimal.class),
                        record.get("total_return_pct", BigDecimal.class),
                        record.get("max_drawdown_pct", BigDecimal.class),
                        record.get("sharpe_ratio", BigDecimal.class),
                        owned));
    }

    private static String rowAnchor(UUID snapshotId, int rank, UUID participationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(snapshotId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Integer.toString(rank).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(participationId.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
