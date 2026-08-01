package com.idea2strategy.backend.application.botoperations;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class BotOperationsQueryService {
    private static final int MAX_PAGE_SIZE = 100;

    private final BotOperationsQueryPort queryPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public BotOperationsQueryService(
            BotOperationsQueryPort queryPort, CurrentPrincipal principal, Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<BotOperationsView> listOwned() {
        return queryPort.findOwnedBots(principal.accountId()).stream()
                .map(this::toView)
                .toList();
    }

    public BotJudgmentLogPage getJudgments(UUID botId, long afterSequence, int limit) {
        Objects.requireNonNull(botId, "botId");
        if (afterSequence < 0) {
            throw new IllegalArgumentException("afterSequence must not be negative");
        }
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
        }

        BotJudgmentLogSlice slice = queryPort
                .findOwnedJudgments(botId, principal.accountId(), afterSequence, limit)
                .orElseThrow(() -> new BotOperationsNotFoundException(botId));
        long next = slice.entries().isEmpty()
                ? afterSequence
                : slice.entries().get(slice.entries().size() - 1).sequence();
        return new BotJudgmentLogPage(slice.entries(), next, slice.hasMore());
    }

    private BotOperationsView toView(BotOperationsProjection projection) {
        return new BotOperationsView(
                projection.botId(),
                projection.name(),
                stateOf(projection),
                projection.lifecycleChangedAt(),
                projection.executionBlockedAt(),
                projection.executionBlockReasonCode(),
                projection.lastEventSequence());
    }

    private BotOperationsState stateOf(BotOperationsProjection projection) {
        if (projection.lifecycleStatus() == BotLifecycleStatus.STOPPING) {
            return BotOperationsState.STOPPING;
        }
        if (projection.lifecycleStatus() == BotLifecycleStatus.STOPPED) {
            return BotOperationsState.STOPPED;
        }
        if (projection.executionBlockedAt() != null) {
            return blockedState(projection.executionBlockReasonCode());
        }
        if (projection.executionEligibleFrom().isAfter(clock.instant())) {
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
