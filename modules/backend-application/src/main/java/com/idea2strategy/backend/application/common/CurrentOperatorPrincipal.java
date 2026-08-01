package com.idea2strategy.backend.application.common;

import java.util.Optional;
import java.util.UUID;

public interface CurrentOperatorPrincipal {
    Optional<UUID> operatorId();
}
