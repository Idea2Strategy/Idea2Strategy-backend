package com.idea2strategy.backend.application.competition;

import java.util.NoSuchElementException;
import java.util.UUID;

public final class ScoringTemplateNotFoundException extends NoSuchElementException {
    public ScoringTemplateNotFoundException(UUID id) {
        super("Selectable scoring template was not found: " + id);
    }
}
