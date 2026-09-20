package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.FinalLeaderboardEntry;
import com.idea2strategy.backend.application.competition.FinalRoomResult;
import com.idea2strategy.backend.application.competition.FinalRoomResultConflictException;
import com.idea2strategy.backend.application.competition.FinalRoomResultPort;
import com.idea2strategy.backend.application.competition.FinalRoomResultWriteDecision;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class FinalRoomResultJooqAdapter implements FinalRoomResultPort {
    private final DSLContext dsl;

    public FinalRoomResultJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public FinalRoomResultWriteDecision save(FinalRoomResult result) {
        Record room = dsl.fetchOne(
                "select r.status::text as status, r.access_type::text as access_type, "
                        + "r.creator_account_id, rs.evaluation_ends_at, "
                        + "rr.scoring_template_version_id "
                        + "from competition.rooms r "
                        + "join competition.room_schedules rs on rs.room_id = r.id "
                        + "join competition.room_rules rr on rr.room_id = r.id "
                        + "where r.id = ? for update of r",
                result.roomId());
        if (room == null) {
            throw new FinalRoomResultConflictException("room does not exist");
        }
        if (!"ENDED".equals(room.get("status", String.class))) {
            throw new FinalRoomResultConflictException("room must be ended before final result publication");
        }
        OffsetDateTime cutoff = result.cutoffAt().atOffset(ZoneOffset.UTC);
        if (!cutoff.toInstant().equals(room.get("evaluation_ends_at", OffsetDateTime.class).toInstant())
                || !result.scoringTemplateVersionId().equals(
                        room.get("scoring_template_version_id", UUID.class))) {
            throw new FinalRoomResultConflictException("final result does not match the locked room rules");
        }

        Record existing = dsl.fetchOne(
                "select id, result_hash, scoring_template_version_id, cutoff_at, "
                        + "(select count(*) from competition.leaderboard_entries le "
                        + "where le.snapshot_id = ls.id) as entry_count "
                        + "from competition.leaderboard_snapshots ls "
                        + "where ls.room_id = ? and ls.cutoff_at = ?::timestamptz for update",
                result.roomId(), cutoff);
        if (existing != null) {
            boolean identical = result.snapshotId().equals(existing.get("id", UUID.class))
                    && result.resultHash().equals(existing.get("result_hash", String.class))
                    && result.scoringTemplateVersionId().equals(
                            existing.get("scoring_template_version_id", UUID.class))
                    && result.entries().size() == existing.get("entry_count", Integer.class);
            if (identical) {
                return FinalRoomResultWriteDecision.ALREADY_FINALIZED_IDENTICALLY;
            }
            throw new FinalRoomResultConflictException("a different final result already exists for the room");
        }

        for (FinalLeaderboardEntry entry : result.entries()) {
            Record participation = dsl.fetchOne(
                    "select status::text as status from competition.participations "
                            + "where id = ? and room_id = ? for update",
                    entry.participationId(), result.roomId());
            if (participation == null) {
                throw new FinalRoomResultConflictException("final result contains a foreign participation");
            }
            String status = participation.get("status", String.class);
            if (!"COMPLETED".equals(status) && !"EVALUATION_FAILED".equals(status)) {
                throw new FinalRoomResultConflictException("final result requires terminal participations");
            }
        }

        dsl.execute(
                "insert into competition.leaderboard_snapshots "
                        + "(id, room_id, scoring_template_version_id, cutoff_at, status, result_hash, created_at) "
                        + "values (?, ?, ?, ?::timestamptz, 'FINAL'::competition.leaderboard_status, "
                        + "?, ?::timestamptz)",
                result.snapshotId(), result.roomId(), result.scoringTemplateVersionId(), cutoff,
                result.resultHash(), result.createdAt().atOffset(ZoneOffset.UTC));
        for (FinalLeaderboardEntry entry : result.entries()) {
            dsl.execute(
                    "update competition.participations set action_locked_at = "
                            + "coalesce(action_locked_at, ?::timestamptz) where id = ?",
                    cutoff, entry.participationId());
            dsl.execute(
                    "insert into competition.leaderboard_entries "
                            + "(snapshot_id, participation_id, performance_snapshot_id, rank, is_joint_rank, "
                            + "eligibility_status, eligibility_reason_code, score, tie_break_document, "
                            + "calculation_document) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
                    result.snapshotId(), entry.participationId(), entry.performanceSnapshotId(), entry.rank(),
                    entry.jointRank(), entry.eligibilityStatus(),
                    entry.eligibilityReason() == null ? null : entry.eligibilityReason().name(), entry.score(),
                    entry.tieBreakDocument(), entry.calculationDocument());
        }
        if ("SECRET".equals(room.get("access_type", String.class))) {
            freezeSecretAccessGrants(result, room.get("creator_account_id", UUID.class));
        }
        return FinalRoomResultWriteDecision.CREATED;
    }

    private void freezeSecretAccessGrants(FinalRoomResult result, UUID creatorAccountId) {
        Map<UUID, String> grants = new LinkedHashMap<>();
        if (creatorAccountId != null) {
            grants.put(creatorAccountId, "CREATOR");
        }
        dsl.fetch(
                        "select distinct owner_account_id from competition.participations "
                                + "where room_id = ? and status in "
                                + "('COMPLETED'::competition.participation_status, "
                                + "'EVALUATION_FAILED'::competition.participation_status) "
                                + "order by owner_account_id",
                        result.roomId())
                .getValues("owner_account_id", UUID.class)
                .forEach(accountId -> grants.putIfAbsent(accountId, "ACTIVE_PARTICIPANT"));
        for (var grant : grants.entrySet()) {
            dsl.execute(
                    "insert into competition.room_final_access_grants "
                            + "(room_id, account_id, snapshot_id, eligibility_basis, granted_at) "
                            + "values (?, ?, ?, ?, ?::timestamptz)",
                    result.roomId(), grant.getKey(), result.snapshotId(), grant.getValue(),
                    result.createdAt().atOffset(ZoneOffset.UTC));
        }
    }
}
