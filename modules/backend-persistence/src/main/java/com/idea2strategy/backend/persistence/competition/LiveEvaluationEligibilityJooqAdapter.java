package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.LiveEvaluationEligibility;
import com.idea2strategy.backend.application.competition.LiveEvaluationEligibilityNotFoundException;
import com.idea2strategy.backend.application.competition.LiveEvaluationEligibilityPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LiveEvaluationEligibilityJooqAdapter implements LiveEvaluationEligibilityPort {
    private final DSLContext dsl;

    public LiveEvaluationEligibilityJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(readOnly = true)
    public LiveEvaluationEligibility evaluate(UUID participationId, Instant observedAt) {
        OffsetDateTime observed = observedAt.atOffset(ZoneOffset.UTC);
        var record = dsl.fetchOne(
                "select p.room_id, p.bot_id, lrr.minimum_operation_seconds, lrr.minimum_fill_count, "
                        + "floor(coalesce((select sum(extract(epoch from "
                        + "least(s.ends_at, ?::timestamptz) - s.starts_at)) "
                        + "from competition.live_evaluation_segments s "
                        + "where s.participation_id = p.id and s.segment_type = 'OFFICIAL_EVALUATION' "
                        + "and s.starts_at < ?::timestamptz), 0))::bigint as operation_seconds, "
                        + "(select count(*) from trading.fills f where f.bot_id = p.bot_id and exists ("
                        + "select 1 from competition.live_evaluation_segments s "
                        + "where s.participation_id = p.id and s.segment_type = 'OFFICIAL_EVALUATION' "
                        + "and s.starts_at < ?::timestamptz and f.occurred_at >= s.starts_at "
                        + "and f.occurred_at < least(s.ends_at, ?::timestamptz))) as fill_count "
                        + "from competition.participations p "
                        + "join competition.rooms r on r.id = p.room_id "
                        + "join competition.live_room_rules lrr on lrr.room_id = r.id "
                        + "where p.id = ? and r.competition_type = 'LIVE_PAPER'::competition.competition_type",
                observed, observed, observed, observed, participationId);
        if (record == null) {
            throw new LiveEvaluationEligibilityNotFoundException();
        }
        return LiveEvaluationEligibility.fromEvidence(
                record.get("room_id", UUID.class),
                participationId,
                record.get("bot_id", UUID.class),
                observedAt,
                record.get("operation_seconds", Long.class),
                record.get("minimum_operation_seconds", Long.class),
                record.get("fill_count", Long.class),
                record.get("minimum_fill_count", Integer.class));
    }
}
