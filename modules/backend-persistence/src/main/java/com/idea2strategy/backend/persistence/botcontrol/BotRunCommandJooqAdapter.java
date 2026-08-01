package com.idea2strategy.backend.persistence.botcontrol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandConflictException;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatch;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatchMode;
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
public class BotRunCommandJooqAdapter implements BotRunCommandPort {
    private static final String CONTRACT_VERSION = "strategy-bot.v1";
    private static final String MESSAGE_TYPE = "BOT_RUN_COMMAND";

    private final DSLContext dsl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BotRunCommandJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    @Transactional
    public Optional<BotRunDispatch> issueOwned(
            UUID botId, UUID ownerAccountId, Instant requestedAt) {
        dsl.fetchOne("select pg_advisory_xact_lock(hashtextextended(?::text, 0))", ownerAccountId);
        var bot = dsl.fetchOne(
                "select b.lifecycle_status::text as lifecycle_status, b.execution_eligible_from, "
                        + "s.snapshot_hash, p.status::text as participation_status, "
                        + "schedule.evaluation_starts_at "
                        + "from bot.bots b "
                        + "join bot.launch_snapshots s on s.bot_id = b.id "
                        + "left join competition.participations p on p.bot_id = b.id "
                        + "left join competition.room_schedules schedule on schedule.room_id = p.room_id "
                        + "where b.id = ? and b.owner_account_id = ? and b.deleted_at is null for update of b",
                botId, ownerAccountId);
        if (bot == null) {
            return Optional.empty();
        }
        String lifecycleStatus = bot.get("lifecycle_status", String.class);
        if ("STOPPING".equals(lifecycleStatus) || "STOPPED".equals(lifecycleStatus)) {
            throw new BotRunCommandConflictException("A stopping or stopped bot cannot run again");
        }

        String participationStatus = bot.get("participation_status", String.class);
        if (isTerminalParticipation(participationStatus)) {
            throw new BotRunCommandConflictException("An inactive room participation cannot run");
        }
        OffsetDateTime currentEligibility = bot.get("execution_eligible_from", OffsetDateTime.class);
        OffsetDateTime roomEligibility = bot.get("evaluation_starts_at", OffsetDateTime.class);
        Instant executionEligibleFrom = participationStatus == null
                ? currentEligibility.toInstant()
                : roomEligibility.toInstant();
        BotRunDispatchMode mode = executionEligibleFrom.isAfter(requestedAt)
                ? BotRunDispatchMode.WAITING
                : BotRunDispatchMode.IMMEDIATE;

        if (participationStatus == null) {
            dsl.execute(
                    "insert into bot.continuation_deadlines "
                            + "(bot_id, due_at, renewal_sequence, created_at, updated_at) "
                            + "values (?, ?::timestamptz, 0, ?::timestamptz, ?::timestamptz) "
                            + "on conflict (bot_id) do nothing",
                    botId,
                    requestedAt.plus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC),
                    requestedAt.atOffset(ZoneOffset.UTC),
                    requestedAt.atOffset(ZoneOffset.UTC));
        }

        if (!currentEligibility.toInstant().equals(executionEligibleFrom)) {
            dsl.execute(
                    "update bot.bots set execution_eligible_from = ?::timestamptz, updated_at = ?::timestamptz "
                            + "where id = ?",
                    executionEligibleFrom.atOffset(ZoneOffset.UTC), requestedAt.atOffset(ZoneOffset.UTC), botId);
        }

        String expectedSnapshotHash = "sha256:" + bot.get("snapshot_hash", String.class);
        String operationKey = "RUN|" + executionEligibleFrom;
        String idempotencyKey = idempotencyKey(botId, expectedSnapshotHash, operationKey);
        UUID messageId = derivedId("message", idempotencyKey);
        UUID correlationId = derivedId("correlation", idempotencyKey);
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
                payload(botId, messageId, correlationId, expectedSnapshotHash, executionEligibleFrom,
                        idempotencyKey, requestedAt),
                idempotencyKey,
                requestedAt.atOffset(ZoneOffset.UTC));
        return Optional.of(new BotRunDispatch(
                botId, messageId, idempotencyKey, executionEligibleFrom, mode, inserted == 1));
    }

    private static boolean isTerminalParticipation(String status) {
        return "WITHDRAWN".equals(status)
                || "EXPELLED".equals(status)
                || "COMPLETED".equals(status)
                || "EVALUATION_FAILED".equals(status);
    }

    private String payload(
            UUID botId,
            UUID messageId,
            UUID correlationId,
            String expectedSnapshotHash,
            Instant executionEligibleFrom,
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
        root.put("executionEligibleFrom", executionEligibleFrom.toString());
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Bot run command could not be serialized", exception);
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
}
