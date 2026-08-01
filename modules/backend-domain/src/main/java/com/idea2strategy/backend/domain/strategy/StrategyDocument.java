package com.idea2strategy.backend.domain.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record StrategyDocument(
        UUID strategyId,
        String semanticDocument,
        String presentationDocument,
        String semanticSchemaVersion,
        String presentationSchemaVersion,
        String semanticHash,
        String presentationHash,
        long editSequence,
        Instant createdAt,
        Instant updatedAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public StrategyDocument {
        Objects.requireNonNull(strategyId, "strategyId");
        requireDocument(semanticDocument, "semanticDocument");
        requireDocument(presentationDocument, "presentationDocument");
        requireVersion(semanticSchemaVersion, "semanticSchemaVersion");
        requireVersion(presentationSchemaVersion, "presentationSchemaVersion");
        requireHash(semanticHash, "semanticHash");
        requireHash(presentationHash, "presentationHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (editSequence < 0) {
            throw new IllegalArgumentException("editSequence must not be negative");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    public static StrategyDocument create(
            UUID strategyId,
            String semanticDocument,
            String presentationDocument,
            String semanticSchemaVersion,
            String presentationSchemaVersion,
            String semanticHash,
            String presentationHash,
            Instant now) {
        return new StrategyDocument(
                strategyId,
                semanticDocument,
                presentationDocument,
                semanticSchemaVersion,
                presentationSchemaVersion,
                semanticHash,
                presentationHash,
                0,
                now,
                now);
    }

    public StrategyDocument replace(
            String nextSemanticDocument,
            String nextPresentationDocument,
            String nextSemanticSchemaVersion,
            String nextPresentationSchemaVersion,
            String nextSemanticHash,
            String nextPresentationHash,
            Instant now) {
        return new StrategyDocument(
                strategyId,
                nextSemanticDocument,
                nextPresentationDocument,
                nextSemanticSchemaVersion,
                nextPresentationSchemaVersion,
                nextSemanticHash,
                nextPresentationHash,
                Math.addExact(editSequence, 1),
                createdAt,
                now);
    }

    private static void requireDocument(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireVersion(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 40) {
            throw new IllegalArgumentException(name + " must contain 1..40 characters");
        }
    }

    private static void requireHash(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest");
        }
    }
}
