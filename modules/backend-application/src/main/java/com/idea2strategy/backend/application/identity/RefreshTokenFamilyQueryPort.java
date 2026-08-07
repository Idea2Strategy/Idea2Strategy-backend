package com.idea2strategy.backend.application.identity;

import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenFamilyQueryPort {
    Optional<StoredRefreshTokenFamily> findByTokenDigest(String tokenDigest);

    Optional<StoredRefreshTokenFamily> findById(UUID familyId);
}
