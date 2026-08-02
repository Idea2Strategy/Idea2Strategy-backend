package com.idea2strategy.backend.application.adminmcp;

public final class AdminMcpAuthenticationRejectedException extends RuntimeException {
    public AdminMcpAuthenticationRejectedException() {
        super("ADMIN_MCP_AUTHENTICATION_REQUIRED");
    }
}
