package com.idea2strategy.backend.persistence.botcontrol;

import com.idea2strategy.backend.application.botcontrol.ExpiredBotStopCandidate;
import com.idea2strategy.backend.application.botcontrol.ExpiredBotStopQueryPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ExpiredBotStopJooqQueryAdapter implements ExpiredBotStopQueryPort {
    private final DSLContext dsl;

    public ExpiredBotStopJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExpiredBotStopCandidate> findExpired(Instant expiredAt, int limit) {
        return dsl.fetch(
                        "select b.id, b.owner_account_id, d.due_at "
                                + "from bot.continuation_deadlines d join bot.bots b on b.id = d.bot_id "
                                + "where d.due_at <= ?::timestamptz and b.lifecycle_status = 'RUNNING' "
                                + "and b.deleted_at is null and not exists ("
                                + "select 1 from competition.participations p where p.bot_id = b.id "
                                + "and p.status not in ('WITHDRAWN', 'EXPELLED', 'COMPLETED', 'EVALUATION_FAILED')) "
                                + "order by d.due_at, b.id limit ?",
                        utc(expiredAt),
                        limit)
                .map(record -> new ExpiredBotStopCandidate(
                        record.get("id", UUID.class),
                        record.get("owner_account_id", UUID.class),
                        record.get("due_at", OffsetDateTime.class).toInstant()));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
