package com.idea2strategy.backend.application.botoperations;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record BotJudgmentLogEntry(
        UUID eventId, long sequence, String eventType, Instant occurredAt, Map<String, Object> summary) {
    public BotJudgmentLogEntry {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(summary, "summary");
        summary = Collections.unmodifiableMap(new LinkedHashMap<>(summary));
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }
}
