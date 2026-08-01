package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightFacts;
import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightQueryPort;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatch;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatchMode;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotStopDispatch;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandPort;
import com.idea2strategy.backend.application.strategy.OfficialBacktestRequest;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class FakeTradingBotAdapter
        implements ImmutableStrategyReleaseCommandPort,
                BotExecutionPreflightQueryPort,
                BotRunCommandPort,
                BotStopCommandPort {
    private static final String RUN_KEY =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String STOP_KEY =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private ImmutableStrategyRelease release;
    private OfficialBacktestRequest backtestRequest;
    private BotLifecycleStatus lifecycleStatus;
    private int runCommandCount;
    private int stopCommandCount;

    @Override
    public ImmutableStrategyRelease saveOnce(
            ImmutableStrategyRelease candidate,
            OfficialBacktestRequest candidateBacktestRequest,
            UUID validationRunId,
            long validatedEditSequence,
            String validatedSemanticHash) {
        if (release == null) {
            release = Objects.requireNonNull(candidate, "candidate");
            backtestRequest = Objects.requireNonNull(candidateBacktestRequest, "candidateBacktestRequest");
        }
        return release;
    }

    @Override
    public Optional<BotExecutionPreflightFacts> findOwnedById(
            UUID botId, UUID ownerAccountId, Instant evaluatedAt) {
        if (!isOwned(botId, ownerAccountId) || lifecycleStatus != null) {
            return Optional.empty();
        }
        return Optional.of(new BotExecutionPreflightFacts(
                botId,
                release.launchConfiguration().initialCashAmount(),
                1,
                List.of(),
                true,
                true,
                true,
                List.of()));
    }

    @Override
    public Optional<BotRunDispatch> issueOwned(
            UUID botId, UUID ownerAccountId, Instant requestedAt) {
        if (!isOwned(botId, ownerAccountId) || lifecycleStatus != null) {
            return Optional.empty();
        }
        lifecycleStatus = BotLifecycleStatus.RUNNING;
        runCommandCount++;
        return Optional.of(new BotRunDispatch(
                botId,
                UUID.nameUUIDFromBytes(("run:" + botId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                RUN_KEY,
                requestedAt,
                BotRunDispatchMode.IMMEDIATE,
                true));
    }

    @Override
    public Optional<BotStopDispatch> issueOwned(
            UUID botId, UUID ownerAccountId, String reasonCode, Instant requestedAt) {
        if (!isOwned(botId, ownerAccountId) || lifecycleStatus != BotLifecycleStatus.RUNNING) {
            return Optional.empty();
        }
        lifecycleStatus = BotLifecycleStatus.STOPPING;
        stopCommandCount++;
        return Optional.of(new BotStopDispatch(
                botId,
                UUID.nameUUIDFromBytes(("stop:" + botId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                STOP_KEY,
                lifecycleStatus,
                reasonCode,
                true));
    }

    public OfficialBacktestRequest backtestRequest() {
        return Objects.requireNonNull(backtestRequest, "backtestRequest");
    }

    public int runCommandCount() {
        return runCommandCount;
    }

    public int stopCommandCount() {
        return stopCommandCount;
    }

    private boolean isOwned(UUID botId, UUID ownerAccountId) {
        return release != null
                && release.botId().equals(botId)
                && release.ownerAccountId().equals(ownerAccountId);
    }
}
