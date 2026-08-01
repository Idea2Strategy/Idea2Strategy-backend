package com.idea2strategy.backend.persistence.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotContinuationCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotContinuationConflictException;
import com.idea2strategy.backend.application.botcontrol.BotContinuationFacts;
import com.idea2strategy.backend.application.botcontrol.BotContinuationQueryPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BotContinuationJooqAdapter implements BotContinuationQueryPort, BotContinuationCommandPort {
    private static final Duration RENEWAL_WINDOW = Duration.ofDays(7);
    private static final Duration RENEWAL_PERIOD = Duration.ofDays(30);

    private final DSLContext dsl;

    public BotContinuationJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<BotContinuationFacts> findOwned(UUID botId, UUID ownerAccountId) {
        var record = dsl.fetchOne(
                "select d.due_at, d.last_renewed_at from bot.bots b "
                        + "join bot.continuation_deadlines d on d.bot_id = b.id "
                        + "where b.id = ? and b.owner_account_id = ? and b.deleted_at is null "
                        + "and b.lifecycle_status = 'RUNNING'::bot.lifecycle_status "
                        + "and not exists (select 1 from competition.participations p where p.bot_id = b.id "
                        + "and p.status not in ('WITHDRAWN', 'EXPELLED', 'COMPLETED', 'EVALUATION_FAILED'))",
                botId,
                ownerAccountId);
        return Optional.ofNullable(record).map(value -> facts(botId, value));
    }

    @Override
    @Transactional
    public Optional<BotContinuationFacts> renewOwned(
            UUID botId, UUID ownerAccountId, Instant receivedAt) {
        dsl.fetchOne("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", botId);
        var record = dsl.fetchOne(
                "select b.lifecycle_status::text as lifecycle_status, d.due_at, d.last_renewed_at, "
                        + "d.renewal_sequence, exists (select 1 from competition.participations p "
                        + "where p.bot_id = b.id and p.status not in "
                        + "('WITHDRAWN', 'EXPELLED', 'COMPLETED', 'EVALUATION_FAILED')) as affiliated "
                        + "from bot.bots b left join bot.continuation_deadlines d on d.bot_id = b.id "
                        + "where b.id = ? and b.owner_account_id = ? and b.deleted_at is null for update of b",
                botId,
                ownerAccountId);
        if (record == null) {
            return Optional.empty();
        }
        if (!"RUNNING".equals(record.get("lifecycle_status", String.class))) {
            throw new BotContinuationConflictException("Only a running bot can renew continuation");
        }
        if (Boolean.TRUE.equals(record.get("affiliated", Boolean.class))) {
            throw new BotContinuationConflictException("A room-affiliated bot cannot renew continuation");
        }

        OffsetDateTime storedDueAt = record.get("due_at", OffsetDateTime.class);
        if (storedDueAt == null) {
            throw new BotContinuationConflictException("Continuation deadline is not initialized");
        }
        Instant currentDueAt = storedDueAt.toInstant();
        if (!receivedAt.isBefore(currentDueAt)) {
            throw new BotContinuationConflictException("Continuation deadline has expired");
        }
        if (receivedAt.isBefore(currentDueAt.minus(RENEWAL_WINDOW))) {
            throw new BotContinuationConflictException("Continuation renewal is not available yet");
        }

        long nextSequence = record.get("renewal_sequence", Long.class) + 1;
        Instant newDueAt = receivedAt.plus(RENEWAL_PERIOD);
        dsl.execute(
                "update bot.continuation_deadlines set due_at = ?::timestamptz, "
                        + "last_renewed_at = ?::timestamptz, renewal_sequence = ?, updated_at = ?::timestamptz "
                        + "where bot_id = ?",
                utc(newDueAt),
                utc(receivedAt),
                nextSequence,
                utc(receivedAt),
                botId);
        insertAudit(botId, ownerAccountId, currentDueAt, newDueAt, nextSequence, receivedAt);
        return Optional.of(new BotContinuationFacts(botId, newDueAt, receivedAt));
    }

    private void insertAudit(
            UUID botId,
            UUID ownerAccountId,
            Instant previousDueAt,
            Instant newDueAt,
            long sequence,
            Instant occurredAt) {
        String material = String.join("\n",
                "action=BOT_CONTINUATION_RENEWED",
                "botId=" + botId,
                "sequence=" + sequence,
                "previousDueAt=" + previousDueAt,
                "newDueAt=" + newDueAt);
        String idempotencyKey = "sha256:" + sha256(material);
        dsl.execute(
                "insert into operations.audit_events "
                        + "(id, actor_type, actor_id, action_type, target_domain, target_id, reason_code, "
                        + "correlation_id, idempotency_key, before_hash, after_hash, occurred_at) "
                        + "values (?, 'USER', ?, 'BOT_CONTINUATION_RENEWED', 'bot', ?, "
                        + "'USER_CONFIRMED_CONTINUATION', ?, ?, ?, ?, ?::timestamptz)",
                derivedId("audit", material),
                ownerAccountId,
                botId,
                derivedId("correlation", material),
                idempotencyKey,
                "sha256:" + sha256(previousDueAt.toString()),
                "sha256:" + sha256(newDueAt.toString()),
                utc(occurredAt));
    }

    private static BotContinuationFacts facts(UUID botId, org.jooq.Record record) {
        OffsetDateTime renewedAt = record.get("last_renewed_at", OffsetDateTime.class);
        return new BotContinuationFacts(
                botId,
                record.get("due_at", OffsetDateTime.class).toInstant(),
                renewedAt == null ? null : renewedAt.toInstant());
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static UUID derivedId(String purpose, String material) {
        return UUID.nameUUIDFromBytes((purpose + ":" + material).getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
