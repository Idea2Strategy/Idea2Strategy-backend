package com.idea2strategy.backend.application.strategy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

final class StrategyLibraryCursor {
    private static final String VERSION = "v1";

    private StrategyLibraryCursor() {}

    static String encode(Instant snapshotAt, StrategyLibraryPosition position) {
        String plain = String.join(
                "|",
                VERSION,
                snapshotAt.toString(),
                position.sortTime().toString(),
                position.kind().name(),
                position.id().toString());
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    static Decoded decode(String cursor) {
        try {
            String plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] values = plain.split("\\|", -1);
            if (values.length != 5 || !VERSION.equals(values[0])) {
                throw invalid();
            }
            return new Decoded(
                    Instant.parse(values[1]),
                    new StrategyLibraryPosition(
                            Instant.parse(values[2]),
                            StrategyLibraryItemKind.valueOf(values[3]),
                            UUID.fromString(values[4])));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("cursor is invalid");
    }

    record Decoded(Instant snapshotAt, StrategyLibraryPosition position) {}
}
