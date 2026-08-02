package com.idea2strategy.backend.application.competition;

public final class ScoringEvidenceNotFoundException extends RuntimeException {
    public ScoringEvidenceNotFoundException() {
        super("Finalized scoring evidence was not found for the requested sources");
    }
}
