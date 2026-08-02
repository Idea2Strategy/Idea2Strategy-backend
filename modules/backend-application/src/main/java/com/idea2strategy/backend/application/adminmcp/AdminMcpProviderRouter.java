package com.idea2strategy.backend.application.adminmcp;

import java.util.Optional;

public interface AdminMcpProviderRouter {
    Optional<AdminMcpProviderPort> providerFor(String targetDomain);
}
