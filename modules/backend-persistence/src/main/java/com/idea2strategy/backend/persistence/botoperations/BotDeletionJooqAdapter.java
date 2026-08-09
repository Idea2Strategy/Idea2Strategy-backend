package com.idea2strategy.backend.persistence.botoperations;

import com.idea2strategy.backend.application.botoperations.BotDeletionCommandPort;
import com.idea2strategy.backend.application.botoperations.BotDeletionResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BotDeletionJooqAdapter implements BotDeletionCommandPort {
    private final DSLContext dsl;

    public BotDeletionJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public BotDeletionResult deleteOwnedStopped(
            UUID botId, UUID ownerAccountId, Instant deletedAt) {
        dsl.fetchOne("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", botId);
        var bot = dsl.fetchOne(
                "select lifecycle_status::text as lifecycle_status, deleted_at from bot.bots "
                        + "where id = ? and owner_account_id = ? for update",
                botId,
                ownerAccountId);
        if (bot == null) {
            return BotDeletionResult.NOT_FOUND;
        }
        if (bot.get("deleted_at", OffsetDateTime.class) != null) {
            return BotDeletionResult.ALREADY_DELETED;
        }
        if (!"STOPPED".equals(bot.get("lifecycle_status", String.class))) {
            return BotDeletionResult.NOT_STOPPED;
        }

        dsl.execute(
                "update bot.bots set deleted_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
                utc(deletedAt),
                utc(deletedAt),
                botId);
        return BotDeletionResult.DELETED;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
