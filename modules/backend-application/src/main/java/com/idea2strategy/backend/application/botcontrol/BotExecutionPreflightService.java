package com.idea2strategy.backend.application.botcontrol;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

public final class BotExecutionPreflightService {
    public static final int MAX_CONCURRENT_EXECUTIONS = 10;

    private final BotExecutionPreflightQueryPort queryPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public BotExecutionPreflightService(
            BotExecutionPreflightQueryPort queryPort,
            CurrentPrincipal principal,
            Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BotExecutionPreflightReport validate(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        var facts = queryPort.findOwnedById(botId, principal.accountId(), clock.instant())
                .orElseThrow(BotExecutionPreflightNotFoundException::new);
        var issues = new ArrayList<BotExecutionPreflightIssue>();

        if (facts.initialCashAmount().signum() <= 0) {
            add(issues, "INVALID_INITIAL_CAPITAL", "Initial capital must be positive USD");
        }
        if (facts.projectedConcurrentExecutionCount() > MAX_CONCURRENT_EXECUTIONS) {
            add(issues, "CONCURRENT_EXECUTION_LIMIT_EXCEEDED",
                    "Projected concurrent executions exceed " + MAX_CONCURRENT_EXECUTIONS);
        }
        facts.unsupportedInstrumentIds().stream()
                .sorted()
                .forEach(instrumentId -> add(
                        issues, "UNSUPPORTED_INSTRUMENT", "Instrument is not currently provided: " + instrumentId));
        if (!facts.feePolicyActive()) {
            add(issues, "FEE_POLICY_INACTIVE", "The pinned fee policy is not active");
        }
        if (!facts.buyingPowerBufferPolicyActive()) {
            add(issues, "BUYING_POWER_BUFFER_POLICY_INACTIVE",
                    "The pinned buying-power buffer policy is not active");
        }
        if (!facts.riskPolicyConfigured()) {
            add(issues, "RISK_POLICY_MISSING", "An explicit candidate conflict and risk policy is required");
        }
        facts.unavailableDataRequirements().stream()
                .sorted(Comparator.comparing(BotExecutionPreflightFacts.DataRequirement::instrumentId)
                        .thenComparing(BotExecutionPreflightFacts.DataRequirement::featureDefinitionId))
                .forEach(requirement -> add(
                        issues,
                        "DATA_NOT_READY",
                        "Data is not ready for instrument " + requirement.instrumentId()
                                + " and feature " + requirement.featureDefinitionId()));
        return new BotExecutionPreflightReport(botId, issues);
    }

    private static void add(
            ArrayList<BotExecutionPreflightIssue> issues, String code, String detail) {
        issues.add(new BotExecutionPreflightIssue(code, detail));
    }
}
