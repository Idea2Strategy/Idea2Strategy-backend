package com.idea2strategy.backend.messaging.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.marketdata.MarketBar;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

final class MarketBarJsonCodec {
    private final ObjectMapper objectMapper = new ObjectMapper();

    MarketBar decode(String encoded) {
        try {
            JsonNode root = objectMapper.readTree(encoded);
            if (!"BAR_1M".equals(root.path("eventType").asText())) {
                throw new IllegalArgumentException("Redis payload is not a one-minute bar");
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
