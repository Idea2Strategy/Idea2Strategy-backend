package com.idea2strategy.backend.admin;

import com.idea2strategy.backend.application.adminmcp.AdminMcpProviderPort;
import java.util.Objects;

public record AdminMcpProviderBinding(String targetDomain, AdminMcpProviderPort provider) {
    public AdminMcpProviderBinding {
        if (targetDomain == null || targetDomain.isBlank()) {
            throw new IllegalArgumentException("targetDomain is required");
        }
        Objects.requireNonNull(provider, "provider");
    }
}
