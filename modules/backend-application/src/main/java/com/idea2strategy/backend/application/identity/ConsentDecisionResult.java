package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountConsent;
import java.util.Objects;
import java.util.Optional;

public record ConsentDecisionResult(
        ConsentDecisionOutcome outcome,
        Optional<AccountConsent> consent) {
    public ConsentDecisionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(consent, "consent");
        if ((outcome == ConsentDecisionOutcome.RECORDED) != consent.isPresent()) {
            throw new IllegalArgumentException("Only a recorded decision can contain consent evidence");
        }
    }

    public static ConsentDecisionResult recorded(AccountConsent consent) {
        return new ConsentDecisionResult(ConsentDecisionOutcome.RECORDED, Optional.of(consent));
    }

    public static ConsentDecisionResult rejected(ConsentDecisionOutcome outcome) {
        if (outcome == ConsentDecisionOutcome.RECORDED) {
            throw new IllegalArgumentException("A recorded outcome is not a rejection");
        }
        return new ConsentDecisionResult(outcome, Optional.empty());
    }
}
