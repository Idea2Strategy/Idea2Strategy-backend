package com.idea2strategy.backend.application.common;

import java.util.UUID;

public interface CurrentSessionPrincipal extends CurrentPrincipal {
    UUID sessionId();
}
