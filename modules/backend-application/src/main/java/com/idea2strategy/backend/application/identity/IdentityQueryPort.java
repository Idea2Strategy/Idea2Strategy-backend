package com.idea2strategy.backend.application.identity;

import java.util.Optional;

public interface IdentityQueryPort {
    Optional<PasswordLoginAccount> findPasswordLoginByEmailLookup(String emailLookup);
}
