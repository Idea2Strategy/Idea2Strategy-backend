package com.idea2strategy.backend.application.adminmcp;

import java.time.Instant;

public interface AdminMcpExecutionPort {
    AdminMcpExecutionResult executeIdempotently(
            AdminMcpInvocation invocation,
            Instant evaluatedAt,
            Decision decision);

    @FunctionalInterface
    interface Decision {
        AdminMcpExecutionResult decide();
    }
}
