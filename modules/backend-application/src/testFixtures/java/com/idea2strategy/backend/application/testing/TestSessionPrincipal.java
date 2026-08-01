package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.common.CurrentSessionPrincipal;
import java.util.UUID;

public record TestSessionPrincipal(UUID accountId, UUID sessionId) implements CurrentSessionPrincipal {}
