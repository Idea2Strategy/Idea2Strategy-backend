package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.application.common.CurrentSessionPrincipal;
import java.util.UUID;

public record AuthenticatedSession(UUID accountId, UUID sessionId) implements CurrentSessionPrincipal {}
