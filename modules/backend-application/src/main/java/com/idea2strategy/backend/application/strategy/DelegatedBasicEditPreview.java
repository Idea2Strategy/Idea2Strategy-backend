package com.idea2strategy.backend.application.strategy;

import java.util.List;

public record DelegatedBasicEditPreview(
        long expectedEditSequence,
        String beforeHash,
        String previewHash,
        String proposedSemanticDocument,
        List<String> changes,
        BasicBlockAssemblyValidationResult validation,
        BasicNaturalLanguageReview naturalLanguageReview) {
    public DelegatedBasicEditPreview {
        changes = List.copyOf(changes);
    }

    public boolean valid() {
        return validation.valid();
    }
}
