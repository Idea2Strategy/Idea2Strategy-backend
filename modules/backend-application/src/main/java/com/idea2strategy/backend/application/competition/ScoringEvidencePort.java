package com.idea2strategy.backend.application.competition;

public interface ScoringEvidencePort {
    ScoringEvidenceSource load(ScoringEvidenceRequest request);
}
