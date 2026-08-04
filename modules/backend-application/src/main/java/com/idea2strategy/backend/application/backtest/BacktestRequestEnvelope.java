package com.idea2strategy.backend.application.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A transport-ready request whose identity is stable before an Outbox row is inserted. */
public record BacktestRequestEnvelope(
        UUID messageId,
        UUID aggregateId,
        String eventType,
        String eventSchemaVersion,
        String producerIdempotencyKey,
        String requestHash,
        String payloadHash,
        String payloadDocument) {
    public static final String CONTRACT_VERSION = "backtest-request.v1";
    public static final String CUSTOM_EVENT = "CUSTOM_BACKTEST_REQUESTED";
    public static final String COMPETITION_EVENT = "COMPETITION_BACKTEST_REQUESTED";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public BacktestRequestEnvelope {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        requireText(eventType, "eventType");
        requireText(eventSchemaVersion, "eventSchemaVersion");
        requireSha256(producerIdempotencyKey, "producerIdempotencyKey");
        requireSha256(requestHash, "requestHash");
        requireSha256(payloadHash, "payloadHash");
        requireText(payloadDocument, "payloadDocument");
    }

    public static BacktestRequestEnvelope custom(
            UUID accountId,
            UUID botId,
            UUID datasetManifestId,
            LocalDate periodStart,
            LocalDate periodEnd,
            String expectedSnapshotHash,
            String compiledPlanChecksum,
            String assumptionsVersion,
            String clientIdempotencyKey,
            Instant occurredAt) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        requirePeriod(periodStart, periodEnd);
        requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
        requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
        requireText(assumptionsVersion, "assumptionsVersion");
        requireText(clientIdempotencyKey, "clientIdempotencyKey");
        Objects.requireNonNull(occurredAt, "occurredAt");

        String producerKey = sha256("CUSTOM\n" + accountId + "\n" + clientIdempotencyKey);
        String requestHash = sha256(String.join("\n",
                botId.toString(), datasetManifestId.toString(), periodStart.toString(), periodEnd.toString(),
                expectedSnapshotHash, compiledPlanChecksum, assumptionsVersion));
        UUID messageId = derivedId(CUSTOM_EVENT, producerKey);
        ObjectNode root = base(CUSTOM_EVENT, messageId, occurredAt, botId, producerKey);
        root.put("requestReason", "USER_PERIOD");
        root.put("requestHash", requestHash);
        root.put("botId", botId.toString());
        root.put("expectedSnapshotHash", expectedSnapshotHash);
        root.put("compiledPlanChecksum", compiledPlanChecksum);
        root.put("datasetManifestId", datasetManifestId.toString());
        root.put("periodStart", periodStart.toString());
        root.put("periodEnd", periodEnd.toString());
        root.put("assumptionsVersion", assumptionsVersion);
        return envelope(messageId, botId, CUSTOM_EVENT, producerKey, requestHash, root);
    }

    public static BacktestRequestEnvelope competition(
            UUID roomId,
            UUID participationId,
            UUID botId,
            String planVersion,
            String planHash,
            String expectedSnapshotHash,
            String compiledPlanChecksum,
            String assumptionsVersion,
            Instant occurredAt) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(botId, "botId");
        requireText(planVersion, "planVersion");
        requireSha256(planHash, "planHash");
        requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
        requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
        requireText(assumptionsVersion, "assumptionsVersion");
        Objects.requireNonNull(occurredAt, "occurredAt");

        String producerKey = sha256(
                "COMPETITION\n" + roomId + "\n" + participationId + "\n" + planHash);
        String requestHash = sha256(String.join("\n",
                roomId.toString(), participationId.toString(), botId.toString(), planVersion, planHash,
                expectedSnapshotHash, compiledPlanChecksum, assumptionsVersion));
        UUID messageId = derivedId(COMPETITION_EVENT, producerKey);
        ObjectNode root = base(COMPETITION_EVENT, messageId, occurredAt, participationId, producerKey);
        root.put("requestReason", "COMPETITION_EVALUATION");
        root.put("requestHash", requestHash);
        root.put("roomId", roomId.toString());
        root.put("participationId", participationId.toString());
        root.put("botId", botId.toString());
        root.put("planVersion", planVersion);
        root.put("planHash", planHash);
        root.put("expectedSnapshotHash", expectedSnapshotHash);
        root.put("compiledPlanChecksum", compiledPlanChecksum);
        root.put("assumptionsVersion", assumptionsVersion);
        return envelope(messageId, participationId, COMPETITION_EVENT, producerKey, requestHash, root);
    }

    private static ObjectNode base(
            String eventType, UUID messageId, Instant occurredAt, UUID correlationId, String producerKey) {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode metadata = root.putObject("metadata");
        metadata.put("contractVersion", CONTRACT_VERSION);
        metadata.put("messageType", eventType);
        metadata.put("messageId", messageId.toString());
        metadata.put("occurredAt", occurredAt.toString());
        metadata.put("correlationId", correlationId.toString());
        metadata.put("idempotencyKey", producerKey);
        return root;
    }

    private static BacktestRequestEnvelope envelope(
            UUID messageId, UUID aggregateId, String eventType, String producerKey,
            String requestHash, ObjectNode root) {
        try {
            String payload = StrategyDocumentJson.canonicalize(JSON.writeValueAsString(root));
            return new BacktestRequestEnvelope(
                    messageId, aggregateId, eventType, CONTRACT_VERSION, producerKey,
                    requestHash, sha256(payload), payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Backtest request could not be serialized", exception);
        }
    }

    private static UUID derivedId(String eventType, String producerKey) {
        return UUID.nameUUIDFromBytes((eventType + ":" + producerKey).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        return "sha256:" + StrategyDocumentJson.sha256(value);
    }

    private static void requirePeriod(LocalDate start, LocalDate end) {
        Objects.requireNonNull(start, "periodStart");
        Objects.requireNonNull(end, "periodEnd");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("periodEnd must not precede periodStart");
        }
    }

    private static void requireSha256(String value, String field) {
        requireText(value, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must use sha256:<64 lowercase hex>");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
