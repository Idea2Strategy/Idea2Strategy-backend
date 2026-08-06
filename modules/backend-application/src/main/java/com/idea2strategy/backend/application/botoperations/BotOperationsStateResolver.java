package com.idea2strategy.backend.application.botoperations;

import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

public final class BotOperationsStateResolver {
    private BotOperationsStateResolver() {}

    public static BotOperationsState resolve(
            BotLifecycleStatus lifecycleStatus,
            Instant executionEligibleFrom,
            Instant executionBlockedAt,
            String executionBlockReasonCode,
            Instant now) {
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        Objects.requireNonNull(executionEligibleFrom, "executionEligibleFrom");
        Objects.requireNonNull(now, "now");
        if (lifecycleStatus == BotLifecycleStatus.STOPPING) {
            return BotOperationsState.STOPPING;
        }
        if (lifecycleStatus == BotLifecycleStatus.STOPPED) {
            return BotOperationsState.STOPPED;
        }
        if (executionBlockedAt != null) {
            return blockedState(executionBlockReasonCode);
        }
        if (executionEligibleFrom.isAfter(now)) {
            return BotOperationsState.WAITING;
        }
        return BotOperationsState.RUNNING;
    }

    private static BotOperationsState blockedState(String reasonCode) {
        String reason = reasonCode == null ? "" : reasonCode.toUpperCase(Locale.ROOT);
        if (reason.contains("SETTLEMENT")) {
            return BotOperationsState.SETTLEMENT_FAILED;
        }
        if (reason.contains("MARKET_DATA")
                || reason.contains("DATA_DEGRADED")
                || reason.contains("DATA_QUALITY")
                || reason.contains("WATERMARK")) {
            return BotOperationsState.DATA_DEGRADED;
        }
        return BotOperationsState.ACTION_REQUIRED;
    }
}
