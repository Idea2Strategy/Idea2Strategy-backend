package com.idea2strategy.backend.application.identity;

import java.util.Optional;
import java.util.UUID;

public interface AccountRecoveryQueryPort {
    Optional<PasswordRecoveryAccount> findPasswordRecoveryByEmailLookup(String emailLookup);

    Optional<PasswordRecoveryAccount> findPasswordRecoveryByAccountId(UUID accountId);
}
