package com.idea2strategy.backend.messaging.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.marketdata.MarketBar;
import java.math.BigDecimal;
import java.time.Instant;
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
            if (root.path("schemaVersion").asInt() != 1) {
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
            List<MarketBar> bars = new ArrayList<>();
            for (JsonNode value : root.path("bars")) {
                Instant occurredAt = Instant.parse(requiredText(value, "t"));
                long sequence = Math.floorDiv(
                        occurredAt.getEpochSecond(), timeframe.minutes() * 60L);
                bars.add(new MarketBar(
                        "history:" + instrumentId + ":" + timeframe.value() + ":" + occurredAt,
                        instrumentId,
                        "ALPACA",
                        "SIP",
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
