package com.idea2strategy.backend.messaging.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.marketdata.MarketBar;
import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class MarketBarJsonCodec {
    private final ObjectMapper objectMapper = new ObjectMapper();

    MarketBar decode(String encoded, com.idea2strategy.backend.application.marketdata.MarketBarTimeframe timeframe) {
        try {
            JsonNode root = objectMapper.readTree(encoded);
            String expectedType = "BAR_" + timeframe.value().toUpperCase(java.util.Locale.ROOT);
            if (!expectedType.equals(root.path("eventType").asText())) {
                throw new IllegalArgumentException("Redis payload is not a " + timeframe.value() + " bar");
            }
            JsonNode values = root.has("values") ? root.path("values") : root;
            return new MarketBar(
                    requiredText(root, "eventId"),
                    UUID.fromString(requiredText(root, "instrumentId")),
                    requiredText(root, "provider"),
                    requiredText(root, "feed"),
                    Instant.parse(requiredText(root, "occurredAt")),
                    root.path("sequence").longValue(),
                    root.path("revision").intValue(),
                    decimal(values, "open"), decimal(values, "high"),
                    decimal(values, "low"), decimal(values, "close"),
                    decimal(values, "volume"));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid market bar payload", exception);
        }
    }

    List<MarketBar> decodeHistory(
            String encoded,
            UUID instrumentId,
            com.idea2strategy.backend.application.marketdata.MarketBarTimeframe timeframe) {
        try {
            JsonNode root = objectMapper.readTree(encoded);
            if (root.path("schemaVersion").asInt() != 2) {
                throw new IllegalArgumentException("Unsupported history schema version");
            }
            if (!"all".equals(root.path("adjustment").asText())) {
                throw new IllegalArgumentException("History bars must use adjustment=all");
            }
            if (!timeframe.value().equals(root.path("timeframe").asText())) {
                throw new IllegalArgumentException("History timeframe does not match the request");
            }
            if (!instrumentId.toString().equals(root.path("instrumentId").asText())) {
                throw new IllegalArgumentException("History instrument does not match the request");
            }
            validateProjection(root);
            List<MarketBar> bars = new ArrayList<>();
            String provider = requiredText(root, "provider");
            String feed = requiredText(root, "feed");
            for (JsonNode value : root.path("bars")) {
                Instant occurredAt = Instant.parse(requiredText(value, "t"));
                long sequence = Math.floorDiv(
                        occurredAt.getEpochSecond(), timeframe.minutes() * 60L);
                bars.add(new MarketBar(
                        "history:" + instrumentId + ":" + timeframe.value() + ":" + occurredAt,
                        instrumentId,
                        provider,
                        feed,
                        occurredAt,
                        sequence,
                        0,
                        decimal(value, "o"), decimal(value, "h"),
                        decimal(value, "l"), decimal(value, "c"),
                        decimal(value, "v")));
            }
            return List.copyOf(bars);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid historical market bar payload", exception);
        }
    }

    private void validateProjection(JsonNode root) throws Exception {
        JsonNode bars = root.path("bars");
        if (!bars.isArray() || bars.isEmpty()) {
            throw new IllegalArgumentException("History projection contains no bars");
        }
        if (root.path("rowCount").asInt(-1) != bars.size()) {
            throw new IllegalArgumentException("History row count does not match bars");
        }
        Instant actualFrom = Instant.parse(requiredText(root, "actualFrom"));
        Instant actualTo = Instant.parse(requiredText(root, "actualTo"));
        if (!actualFrom.equals(Instant.parse(requiredText(bars.get(0), "t")))
                || !actualTo.equals(Instant.parse(requiredText(bars.get(bars.size() - 1), "t")))
                || actualTo.isBefore(actualFrom)) {
            throw new IllegalArgumentException("History physical range does not match bars");
        }
        requireNonEmptyArray(root, "manifestIds", false);
        requireNonEmptyArray(root, "datasetHashes", true);
        requireNonEmptyArray(root, "objectHashes", true);
        if (!root.path("revision").canConvertToInt() || root.path("revision").asInt() < 0) {
            throw new IllegalArgumentException("Invalid history revision");
        }
        String expectedHash = requiredText(root, "projectionHash");
        if (!expectedHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid history projection hash");
        }
        ObjectNode unsigned = ((ObjectNode) root).deepCopy();
        unsigned.remove("projectionHash");
        byte[] actualDigest = MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsString(unsigned).getBytes(StandardCharsets.UTF_8));
        byte[] expectedDigest = HexFormat.of().parseHex(expectedHash);
        if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
            throw new IllegalArgumentException("History projection hash mismatch");
        }
    }

    private static void requireNonEmptyArray(JsonNode root, String field, boolean hashes) {
        JsonNode values = root.path(field);
        if (!values.isArray() || values.isEmpty()) {
            throw new IllegalArgumentException("Missing history provenance: " + field);
        }
        for (JsonNode value : values) {
            String text = value.asText();
            if (text.isBlank() || (hashes && !text.matches("[0-9a-f]{64}"))) {
                throw new IllegalArgumentException("Invalid history provenance: " + field);
            }
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        if (!node.hasNonNull(field)) throw new IllegalArgumentException("Missing values." + field);
        return node.path(field).decimalValue();
    }
}
