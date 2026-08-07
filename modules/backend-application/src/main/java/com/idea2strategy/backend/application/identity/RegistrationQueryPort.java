package com.idea2strategy.backend.application.identity;

import java.util.List;
import java.util.Optional;

@FunctionalInterface
public interface RegistrationQueryPort {
    boolean emailExists(String emailLookupHmac);

    default Optional<ExistingEmailRegistration> findEmailRegistration(
            List<IdentifierFingerprint> comparisonFingerprints) {
        return Optional.empty();
    }
}
