package com.idea2strategy.backend.application.competition;

import java.util.Objects;

public record ScoringEvidenceBundle(
        String provenanceVersion,
        ScoringEvidenceSource source,
        String provenanceHash) {

    public ScoringEvidenceBundle {
        provenanceVersion = requireText(provenanceVersion, "provenanceVersion");
        Objects.requireNonNull(source, "source");
        provenanceHash = requireText(provenanceHash, "provenanceHash");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
