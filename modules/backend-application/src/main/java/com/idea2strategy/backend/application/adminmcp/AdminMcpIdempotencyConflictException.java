package com.idea2strategy.backend.application.adminmcp;

public final class AdminMcpIdempotencyConflictException extends RuntimeException {
    public AdminMcpIdempotencyConflictException() {
        super("ADMIN_MCP_IDEMPOTENCY_CONFLICT");
    }
}
