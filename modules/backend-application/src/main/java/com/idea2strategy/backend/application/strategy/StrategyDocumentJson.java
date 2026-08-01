package com.idea2strategy.backend.application.strategy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class StrategyDocumentJson {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private StrategyDocumentJson() {}

    public static String canonicalize(String document) {
        Objects.requireNonNull(document, "document");
        try {
            return OBJECT_MAPPER.writeValueAsString(OBJECT_MAPPER.readValue(document, Object.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Strategy document must be valid JSON", exception);
        }
    }

    public static String sha256(String canonicalDocument) {
        Objects.requireNonNull(canonicalDocument, "canonicalDocument");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalDocument.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
