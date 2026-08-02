package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import java.util.Optional;
import java.util.UUID;

public interface AccountPreferencesQueryPort {
    Optional<AccountPreferences> findByAccountId(UUID accountId);
}
