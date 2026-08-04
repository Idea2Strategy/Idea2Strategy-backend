package com.idea2strategy.backend.application.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.strategy.StrategyDocumentJson;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
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
            String expectedDatasetHash,
            LocalDate periodStart,
            LocalDate periodEnd,
            String expectedSnapshotHash,
            String compiledPlanChecksum,
            String instrumentCatalogVersion,
            BigDecimal initialCashAmount,
            String assumptionsVersion,
            String clientIdempotencyKey,
            Instant occurredAt) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(datasetManifestId, "datasetManifestId");
        requireSha256(expectedDatasetHash, "expectedDatasetHash");
        requirePeriod(periodStart, periodEnd);
        requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
        requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
        requireText(instrumentCatalogVersion, "instrumentCatalogVersion");
        requirePositive(initialCashAmount, "initialCashAmount");
        requireText(assumptionsVersion, "assumptionsVersion");
        requireText(clientIdempotencyKey, "clientIdempotencyKey");
        Objects.requireNonNull(occurredAt, "occurredAt");

        String producerKey = sha256("CUSTOM\n" + accountId + "\n" + clientIdempotencyKey);
        String requestHash = sha256(String.join("\n",
                accountId.toString(), botId.toString(), datasetManifestId.toString(), expectedDatasetHash,
                periodStart.toString(), periodEnd.toString(), expectedSnapshotHash, compiledPlanChecksum,
                instrumentCatalogVersion, initialCashAmount.toPlainString(), assumptionsVersion));
        UUID messageId = derivedId(CUSTOM_EVENT, producerKey);
        ObjectNode root = base(CUSTOM_EVENT, messageId, occurredAt, botId, producerKey);
        root.put("requestReason", "USER_PERIOD");
        root.put("requestHash", requestHash);
        root.put("requestingAccountId", accountId.toString());
        root.put("botId", botId.toString());
        root.put("expectedSnapshotHash", expectedSnapshotHash);
        root.put("compiledPlanChecksum", compiledPlanChecksum);
        root.put("datasetManifestId", datasetManifestId.toString());
        root.put("expectedDatasetHash", expectedDatasetHash);
        root.put("periodStart", periodStart.toString());
        root.put("periodEnd", periodEnd.toString());
        root.put("instrumentCatalogVersion", instrumentCatalogVersion);
        root.put("initialCashAmount", initialCashAmount.toPlainString());
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
            UUID scoringTemplateVersionId,
            String roomRulesHash,
            BigDecimal initialCashAmount,
            String currencyCode,
            List<CompetitionPeriod> periods,
            Instant occurredAt) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(botId, "botId");
        requireText(planVersion, "planVersion");
        requireSha256(planHash, "planHash");
        requireSha256(expectedSnapshotHash, "expectedSnapshotHash");
        requireSha256(compiledPlanChecksum, "compiledPlanChecksum");
        requireText(assumptionsVersion, "assumptionsVersion");
        Objects.requireNonNull(scoringTemplateVersionId, "scoringTemplateVersionId");
        requireSha256(roomRulesHash, "roomRulesHash");
        requirePositive(initialCashAmount, "initialCashAmount");
        requireText(currencyCode, "currencyCode");
        if (!"USD".equals(currencyCode)) {
            throw new IllegalArgumentException("currencyCode must be USD");
        }
        List<CompetitionPeriod> orderedPeriods = orderedPeriods(periods);
        Objects.requireNonNull(occurredAt, "occurredAt");

        String producerKey = sha256(
                "COMPETITION\n" + roomId + "\n" + participationId + "\n" + planHash);
        StringBuilder requestMaterial = new StringBuilder(String.join("\n",
                roomId.toString(), participationId.toString(), botId.toString(), planVersion, planHash,
                expectedSnapshotHash, compiledPlanChecksum, assumptionsVersion, scoringTemplateVersionId.toString(),
                roomRulesHash, initialCashAmount.toPlainString(), currencyCode));
        orderedPeriods.forEach(period -> appendPeriod(requestMaterial, period));
        String requestHash = sha256(requestMaterial.toString());
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
        root.put("scoringTemplateVersionId", scoringTemplateVersionId.toString());
        root.put("roomRulesHash", roomRulesHash);
        root.put("initialCashAmount", initialCashAmount.toPlainString());
        root.put("currencyCode", currencyCode);
        ArrayNode periodNodes = root.putArray("periods");
        orderedPeriods.forEach(period -> writePeriod(periodNodes.addObject(), period));
        return envelope(messageId, participationId, COMPETITION_EVENT, producerKey, requestHash, root);
    }

    private static List<CompetitionPeriod> orderedPeriods(List<CompetitionPeriod> periods) {
        Objects.requireNonNull(periods, "periods");
        if (periods.isEmpty()) {
            throw new IllegalArgumentException("periods must not be empty");
        }
        List<CompetitionPeriod> ordered = periods.stream()
                .sorted(Comparator.comparingInt(CompetitionPeriod::periodSequence))
                .toList();
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).periodSequence() != index + 1) {
                throw new IllegalArgumentException("periodSequence must be unique and contiguous from 1");
            }
        }
        BigDecimal totalWeight = ordered.stream()
                .map(CompetitionPeriod::importanceWeight)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalWeight.compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalArgumentException("competition period importance weights must sum to 1");
        }
        return ordered;
    }

    private static void writePeriod(ObjectNode node, CompetitionPeriod period) {
        node.put("evaluationPeriodId", period.evaluationPeriodId().toString());
        node.put("periodSequence", period.periodSequence());
        node.put("evaluationStart", period.evaluationStart().toString());
        node.put("evaluationEnd", period.evaluationEnd().toString());
        node.put("importanceWeight", period.importanceWeight().toPlainString());
        node.put("inputSetHash", period.inputSetHash());
        ArrayNode datasets = node.putArray("datasets");
        period.datasets().stream()
                .sorted(Comparator.comparing(CompetitionDataset::purposeCode)
                        .thenComparing(dataset -> dataset.datasetManifestId().toString()))
                .forEach(dataset -> {
                    ObjectNode datasetNode = datasets.addObject();
                    datasetNode.put("datasetManifestId", dataset.datasetManifestId().toString());
                    datasetNode.put("purposeCode", dataset.purposeCode());
                    datasetNode.put("expectedDatasetHash", dataset.expectedDatasetHash());
                });
        ArrayNode features = node.putArray("featureMaterializations");
        period.featureMaterializations().stream()
                .sorted(Comparator.comparing(feature -> feature.featureMaterializationId().toString()))
                .forEach(feature -> {
                    ObjectNode featureNode = features.addObject();
                    featureNode.put("featureMaterializationId", feature.featureMaterializationId().toString());
                    featureNode.put("lockedResultHash", feature.lockedResultHash());
                });
    }

    private static void appendPeriod(StringBuilder material, CompetitionPeriod period) {
        material.append('\n').append(period.evaluationPeriodId())
                .append('\n').append(period.periodSequence())
                .append('\n').append(period.evaluationStart())
                .append('\n').append(period.evaluationEnd())
                .append('\n').append(period.importanceWeight().toPlainString())
                .append('\n').append(period.inputSetHash());
        period.datasets().stream()
                .sorted(Comparator.comparing(CompetitionDataset::purposeCode)
                        .thenComparing(dataset -> dataset.datasetManifestId().toString()))
                .forEach(dataset -> material.append('\n').append(dataset.datasetManifestId())
                        .append('\n').append(dataset.purposeCode())
                        .append('\n').append(dataset.expectedDatasetHash()));
        period.featureMaterializations().stream()
                .sorted(Comparator.comparing(feature -> feature.featureMaterializationId().toString()))
                .forEach(feature -> material.append('\n').append(feature.featureMaterializationId())
                        .append('\n').append(feature.lockedResultHash()));
    }

    public record CompetitionPeriod(
            UUID evaluationPeriodId,
            int periodSequence,
            LocalDate evaluationStart,
            LocalDate evaluationEnd,
            BigDecimal importanceWeight,
            String inputSetHash,
            List<CompetitionDataset> datasets,
            List<CompetitionFeatureMaterialization> featureMaterializations) {
        public CompetitionPeriod {
            Objects.requireNonNull(evaluationPeriodId, "evaluationPeriodId");
            if (periodSequence < 1) {
                throw new IllegalArgumentException("periodSequence must be positive");
            }
            requirePeriod(evaluationStart, evaluationEnd);
            requirePositive(importanceWeight, "importanceWeight");
            if (importanceWeight.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("importanceWeight must not exceed 1");
            }
            requireSha256(inputSetHash, "inputSetHash");
            datasets = List.copyOf(Objects.requireNonNull(datasets, "datasets"));
            if (datasets.isEmpty()) {
                throw new IllegalArgumentException("period datasets must not be empty");
            }
            featureMaterializations = List.copyOf(
                    Objects.requireNonNull(featureMaterializations, "featureMaterializations"));
        }
    }

    public record CompetitionDataset(UUID datasetManifestId, String purposeCode, String expectedDatasetHash) {
        public CompetitionDataset {
            Objects.requireNonNull(datasetManifestId, "datasetManifestId");
            requireText(purposeCode, "purposeCode");
            requireSha256(expectedDatasetHash, "expectedDatasetHash");
        }
    }

    public record CompetitionFeatureMaterialization(UUID featureMaterializationId, String lockedResultHash) {
        public CompetitionFeatureMaterialization {
            Objects.requireNonNull(featureMaterializationId, "featureMaterializationId");
            requireSha256(lockedResultHash, "lockedResultHash");
        }
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

    private static void requirePositive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
