package com.idea2strategy.backend.persistence.botcontrol;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.botcontrol.BotQueryPort;
import com.idea2strategy.backend.domain.botcontrol.Bot;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class BotJooqQueryAdapter implements BotQueryPort {
    private final DSLContext dsl;

    public BotJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<Bot> findOwnedById(UUID botId, UUID ownerAccountId) {
        var bots = table(name("bot", "bots"));
        var id = field(name("id"), UUID.class);
        var owner = field(name("owner_account_id"), UUID.class);
        var mode = field(name("mode"), String.class);
        var botName = field(name("name"), String.class);
        var status = field(name("lifecycle_status"), String.class);
        var lifecycleChangedAt = field(name("lifecycle_changed_at"), OffsetDateTime.class);
        var createdAt = field(name("created_at"), OffsetDateTime.class);
        var eligibleFrom = field(name("execution_eligible_from"), OffsetDateTime.class);
        var startedAt = field(name("started_at"), OffsetDateTime.class);
        var stopRequestedAt = field(name("stop_requested_at"), OffsetDateTime.class);
        var stoppedAt = field(name("stopped_at"), OffsetDateTime.class);
        var stopReason = field(name("stop_reason_code"), String.class);
        var editSequence = field(name("edit_sequence"), Long.class);
        var updatedAt = field(name("updated_at"), OffsetDateTime.class);

        return dsl.select(
                        id,
                        owner,
                        mode,
                        botName,
                        status,
                        lifecycleChangedAt,
                        createdAt,
                        eligibleFrom,
                        startedAt,
                        stopRequestedAt,
                        stoppedAt,
                        stopReason,
                        editSequence,
                        updatedAt)
                .from(bots)
                .where(id.eq(botId).and(owner.eq(ownerAccountId)))
                .fetchOptional(record -> new Bot(
                        record.get(id),
                        record.get(owner),
                        StrategyMode.valueOf(record.get(mode)),
                        record.get(botName),
                        BotLifecycleStatus.valueOf(record.get(status)),
                        record.get(lifecycleChangedAt).toInstant(),
                        record.get(createdAt).toInstant(),
                        record.get(eligibleFrom).toInstant(),
                        toInstant(record.get(startedAt)),
                        toInstant(record.get(stopRequestedAt)),
                        toInstant(record.get(stoppedAt)),
                        record.get(stopReason),
                        record.get(editSequence),
                        record.get(updatedAt).toInstant()));
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
