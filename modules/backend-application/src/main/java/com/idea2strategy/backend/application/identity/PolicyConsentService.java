package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.ConsentDecision;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class PolicyConsentService {
    private final PolicyConsentQueryPort queries;
    private final PolicyConsentCommandPort commands;
    private final Clock clock;

    public PolicyConsentService(
            PolicyConsentQueryPort queries,
            PolicyConsentCommandPort commands,
            Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<CurrentPolicyDecision> currentPolicies(UUID accountId, String languageCode) {
        Objects.requireNonNull(accountId, "accountId");
        AccountPreferences.requireSupportedLanguage(languageCode);
        return queries.findCurrentPolicies(languageCode, clock.instant()).stream()
                .map(document -> new CurrentPolicyDecision(
                        document,
                        queries.findLatestConsent(accountId, document.id())))
                .toList();
    }

    public List<AccountConsent> history(UUID accountId, UUID policyDocumentId) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(policyDocumentId, "policyDocumentId");
        return List.copyOf(queries.findConsentHistory(accountId, policyDocumentId));
    }

    public AccountConsent decide(UUID accountId, RecordConsentDecision command) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(command, "command");
        var now = clock.instant();
        ConsentDecision decision;
        try {
            decision = ConsentDecision.valueOf(Objects.requireNonNull(command.decision(), "decision"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            commands.recordConsentRejection(
                    accountId,
                    "INVALID_CONSENT_DECISION",
                    command.correlationId(),
                    now);
            throw new IllegalArgumentException("Unsupported consent decision", exception);
        }
        var result = commands.recordDecision(
                accountId,
                command.policyDocumentId(),
                decision,
                command.correlationId(),
                now);
        if (result.outcome() != ConsentDecisionOutcome.RECORDED) {
            throw new PolicyDecisionRejectedException(result.outcome());
        }
        return result.consent().orElseThrow(() -> new IllegalStateException("Recorded consent evidence is missing"));
    }
}
