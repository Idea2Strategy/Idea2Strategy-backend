package com.idea2strategy.backend.persistence.botoperations;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.idea2strategy.backend.application.botoperations.BotJudgmentLogEntry;
import com.idea2strategy.backend.application.botoperations.BotJudgmentLogSlice;
import com.idea2strategy.backend.application.botoperations.BotOperationsProjection;
import com.idea2strategy.backend.application.botoperations.BotOperationsQueryPort;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class BotOperationsJooqQueryAdapter implements BotOperationsQueryPort {
    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public BotOperationsJooqQueryAdapter(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<BotOperationsProjection> findOwnedBots(UUID ownerAccountId) {
        var bots = table(name("bot", "bots")).as("b");
        var events = table(name("bot", "bot_events")).as("e");
        var id = field(name("b", "id"), UUID.class);
        var owner = field(name("b", "owner_account_id"), UUID.class);
        var botName = field(name("b", "name"), String.class);
        var status = field(name("b", "lifecycle_status"), String.class);
        var lifecycleChangedAt = field(name("b", "lifecycle_changed_at"), OffsetDateTime.class);
        var eligibleFrom = field(name("b", "execution_eligible_from"), OffsetDateTime.class);
        var blockedAt = field(name("b", "execution_blocked_at"), OffsetDateTime.class);
        var blockReason = field(name("b", "execution_block_reason_code"), String.class);
        var archivedAt = field(name("b", "archived_at"), OffsetDateTime.class);
        var deletedAt = field(name("b", "deleted_at"), OffsetDateTime.class);
        var eventBotId = field(name("e", "bot_id"), UUID.class);
        var eventSequence = field(name("e", "event_sequence"), Long.class);

        return dsl.select(
                        id,
                        botName,
                        status,
                        lifecycleChangedAt,
                        eligibleFrom,
                        blockedAt,
                        blockReason,
                        coalesce(max(eventSequence), 0L).as("last_event_sequence"))
                .from(bots)
                .leftJoin(events)
                .on(eventBotId.eq(id))
                .where(owner.eq(ownerAccountId).and(archivedAt.isNull()).and(deletedAt.isNull()))
                .groupBy(id, botName, status, lifecycleChangedAt, eligibleFrom, blockedAt, blockReason)
                .orderBy(field(name("b", "created_at")).desc(), id.asc())
                .fetch(record -> new BotOperationsProjection(
                        record.get(id),
                        record.get(botName),
                        BotLifecycleStatus.valueOf(record.get(status)),
                        record.get(lifecycleChangedAt).toInstant(),
                        record.get(eligibleFrom).toInstant(),
                        toInstant(record.get(blockedAt)),
                        record.get(blockReason),
                        record.get("last_event_sequence", Long.class)));
    }

    @Override
    public Optional<BotJudgmentLogSlice> findOwnedJudgments(
            UUID botId, UUID ownerAccountId, long afterSequence, int limit) {
        var bots = table(name("bot", "bots")).as("b");
        var botIdField = field(name("b", "id"), UUID.class);
        var owner = field(name("b", "owner_account_id"), UUID.class);
        var archivedAt = field(name("b", "archived_at"), OffsetDateTime.class);
        var deletedAt = field(name("b", "deleted_at"), OffsetDateTime.class);
        boolean owned = dsl.fetchExists(
                dsl.selectOne()
                        .from(bots)
                        .where(botIdField
                                .eq(botId)
                                .and(owner.eq(ownerAccountId))
                                .and(archivedAt.isNull())
                                .and(deletedAt.isNull())));
        if (!owned) {
            return Optional.empty();
        }

        var events = table(name("bot", "bot_events")).as("e");
        var eventId = field(name("e", "id"), UUID.class);
        var eventBotId = field(name("e", "bot_id"), UUID.class);
        var sequence = field(name("e", "event_sequence"), Long.class);
        var eventType = field(name("e", "event_type"), String.class);
        var occurredAt = field(name("e", "occurred_at"), OffsetDateTime.class);
        var summary = field(name("e", "summary_document"), JSONB.class);

        List<BotJudgmentLogEntry> fetched = dsl.select(eventId, sequence, eventType, occurredAt, summary)
                .from(events)
                .where(eventBotId.eq(botId).and(sequence.gt(afterSequence)))
                .orderBy(sequence.asc())
                .limit(limit + 1)
                .fetch(record -> new BotJudgmentLogEntry(
                        record.get(eventId),
                        record.get(sequence),
                        record.get(eventType),
                        record.get(occurredAt).toInstant(),
                        readJson(record.get(summary))));
        boolean hasMore = fetched.size() > limit;
        List<BotJudgmentLogEntry> page = hasMore ? fetched.subList(0, limit) : fetched;
        return Optional.of(new BotJudgmentLogSlice(page, hasMore));
    }

    private Map<String, Object> readJson(JSONB value) {
        try {
            return objectMapper.readValue(value.data(), new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Persisted bot event summary is not valid JSON", exception);
        }
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
