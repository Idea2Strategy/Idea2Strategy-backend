package com.idea2strategy.backend.persistence.botcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandConflictException;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotStopDispatch;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
public class BotStopCommandJooqAdapter implements BotStopCommandPort {
    private static final String CONTRACT_VERSION = "strategy-bot.v1";
    private static final String MESSAGE_TYPE = "BOT_STOP_COMMAND";
    private static final String BLOCK_REASON = "BOT_STOP_REQUESTED";

    private final DSLContext dsl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BotStopCommandJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public Optional<BotStopDispatch> issueOwned(
            UUID botId, UUID ownerAccountId, String requestedReason, Instant requestedAt) {
        dsl.fetchOne("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", botId);
        var bot = dsl.fetchOne(
                "select b.lifecycle_status::text as lifecycle_status, b.stop_reason_code, s.snapshot_hash "
                        + "from bot.bots b join bot.launch_snapshots s on s.bot_id = b.id "
                        + "where b.id = ? and b.owner_account_id = ? and b.deleted_at is null for update of b",
                botId,
                ownerAccountId);
        if (bot == null) {
            return Optional.empty();
        }

        BotLifecycleStatus lifecycleStatus = BotLifecycleStatus.valueOf(
                bot.get("lifecycle_status", String.class));
        String storedReason = bot.get("stop_reason_code", String.class);
        String reasonCode = storedReason == null ? requestedReason : storedReason;
        String expectedSnapshotHash = "sha256:" + bot.get("snapshot_hash", String.class);
        String idempotencyKey = idempotencyKey(botId, expectedSnapshotHash, "STOP|" + reasonCode);
        UUID messageId = derivedId("message", idempotencyKey);
        UUID correlationId = derivedId("correlation", idempotencyKey);

        if (lifecycleStatus == BotLifecycleStatus.STOPPED) {
            if (!outboxExists(idempotencyKey)) {
                throw new BotStopCommandConflictException("The bot is already permanently stopped");
            }
            return Optional.of(new BotStopDispatch(
                    botId, messageId, idempotencyKey, lifecycleStatus, reasonCode, false));
        }

        if (lifecycleStatus == BotLifecycleStatus.RUNNING) {
            dsl.execute(
                    "update bot.bots set lifecycle_status = 'STOPPING'::bot.lifecycle_status, "
                            + "lifecycle_changed_at = ?::timestamptz, execution_blocked_at = ?::timestamptz, "
                            + "execution_block_reason_code = ?, stop_requested_at = ?::timestamptz, "
                            + "stop_reason_code = ?, updated_at = ?::timestamptz where id = ?",
                    utc(requestedAt),
                    utc(requestedAt),
                    BLOCK_REASON,
                    utc(requestedAt),
                    reasonCode,
                    utc(requestedAt),
                    botId);
            lifecycleStatus = BotLifecycleStatus.STOPPING;
        }

        var sequenceRecord = dsl.fetchOne(
                "select coalesce(max(aggregate_sequence), 0) + 1 from operations.outbox_messages "
                        + "where owner_domain = 'strategy-bot' and aggregate_id = ?",
                botId);
        long aggregateSequence = ((Number) sequenceRecord.get(0)).longValue();
        int inserted = dsl.execute(
                "insert into operations.outbox_messages "
                        + "(id, owner_domain, aggregate_id, aggregate_sequence, event_type, event_schema_version, "
                        + "payload_document, idempotency_key, created_at) "
                        + "values (?, 'strategy-bot', ?, ?, ?, ?, ?::jsonb, ?, ?::timestamptz) "
                        + "on conflict (idempotency_key) do nothing",
                messageId,
                botId,
                aggregateSequence,
                MESSAGE_TYPE,
                CONTRACT_VERSION,
                payload(botId, messageId, correlationId, expectedSnapshotHash, reasonCode, idempotencyKey, requestedAt),
                idempotencyKey,
                utc(requestedAt));
        return Optional.of(new BotStopDispatch(
                botId, messageId, idempotencyKey, lifecycleStatus, reasonCode, inserted == 1));
    }

    private boolean outboxExists(String idempotencyKey) {
        return dsl.fetchExists(dsl.selectOne()
                .from("operations.outbox_messages")
                .where("idempotency_key = ?", idempotencyKey));
    }

    private String payload(
            UUID botId,
            UUID messageId,
            UUID correlationId,
            String expectedSnapshotHash,
            String reasonCode,
            String idempotencyKey,
            Instant occurredAt) {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode metadata = root.putObject("metadata");
        metadata.put("contractVersion", CONTRACT_VERSION);
        metadata.put("messageType", MESSAGE_TYPE);
        metadata.put("messageId", messageId.toString());
        metadata.put("occurredAt", occurredAt.toString());
        metadata.put("correlationId", correlationId.toString());
        metadata.put("idempotencyKey", idempotencyKey);
        root.put("botId", botId.toString());
        root.put("expectedSnapshotHash", expectedSnapshotHash);
        root.put("reasonCode", reasonCode);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Bot stop command could not be serialized", exception);
        }
    }

    private static String idempotencyKey(
            UUID botId, String expectedSnapshotHash, String operationKey) {
        String material = String.join("\n",
                "contractVersion=" + CONTRACT_VERSION,
                "messageType=" + MESSAGE_TYPE,
                "aggregateId=" + botId,
                "snapshotHash=" + expectedSnapshotHash,
                "operationKey=" + operationKey);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private static UUID derivedId(String kind, String idempotencyKey) {
        return UUID.nameUUIDFromBytes((kind + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
