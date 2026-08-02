package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.ConsentDecision;
import com.idea2strategy.backend.domain.identity.PolicyDocumentVersion;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyConsentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T05:00:00Z");
    private static final UUID ACCOUNT_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID CURRENT_DOCUMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID OLD_DOCUMENT_ID = UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Test
    void reportsOnlyTheDecisionForTheExactCurrentPolicyDocument() {
        var repository = new Repository();
        repository.currentPolicies.add(document(CURRENT_DOCUMENT_ID, "2", null));
        repository.latest.put(OLD_DOCUMENT_ID, consent(OLD_DOCUMENT_ID, ConsentDecision.ACCEPTED));

        var policies = service(repository).currentPolicies(ACCOUNT_ID, "en");

        assertThat(policies).singleElement().satisfies(policy -> {
            assertThat(policy.document().id()).isEqualTo(CURRENT_DOCUMENT_ID);
            assertThat(policy.latestDecision()).isEmpty();
        });
    }

    @Test
    void recordsAnAppendOnlyDecisionThroughTheTransactionalCommandPort() {
        var repository = new Repository();
        UUID correlationId = UUID.randomUUID();

        var consent = service(repository).decide(
                ACCOUNT_ID,
                new RecordConsentDecision(CURRENT_DOCUMENT_ID, "WITHDRAWN", correlationId));

        assertThat(repository.recordedAt).isEqualTo(NOW);
        assertThat(repository.correlationId).isEqualTo(correlationId);
        assertThat(consent.policyDocumentId()).isEqualTo(CURRENT_DOCUMENT_ID);
        assertThat(consent.decision()).isEqualTo(ConsentDecision.WITHDRAWN);
    }

    @Test
    void surfacesARejectedDecisionAfterTheCommandPortCanPersistItsAudit() {
        var repository = new Repository();
        repository.outcome = ConsentDecisionOutcome.POLICY_NOT_CURRENT;

        assertThatThrownBy(() -> service(repository).decide(
                        ACCOUNT_ID,
                        new RecordConsentDecision(CURRENT_DOCUMENT_ID, "ACCEPTED", UUID.randomUUID())))
                .isInstanceOf(PolicyDecisionRejectedException.class);
        assertThat(repository.called).isTrue();
    }

    @Test
    void auditsAnInvalidConsentDecisionBeforeReturningAClientError() {
        var repository = new Repository();
        UUID correlationId = UUID.randomUUID();

        assertThatThrownBy(() -> service(repository).decide(
                        ACCOUNT_ID,
                        new RecordConsentDecision(CURRENT_DOCUMENT_ID, null, correlationId)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.rejectionReason).isEqualTo("INVALID_CONSENT_DECISION");
        assertThat(repository.correlationId).isEqualTo(correlationId);
    }

    private static PolicyConsentService service(Repository repository) {
        return new PolicyConsentService(repository, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PolicyDocumentVersion document(UUID id, String version, Instant retiredAt) {
        return new PolicyDocumentVersion(
                id,
                "TERMS",
                version,
                "en",
                "Terms",
                "text/markdown",
                "Terms body",
                "sha256:" + version,
                true,
                NOW.minusSeconds(60),
                retiredAt);
    }

    private static AccountConsent consent(UUID documentId, ConsentDecision decision) {
        return new AccountConsent(UUID.randomUUID(), ACCOUNT_ID, documentId, decision, null, NOW.minusSeconds(1));
    }

    private static final class Repository implements PolicyConsentQueryPort, PolicyConsentCommandPort {
        private final List<PolicyDocumentVersion> currentPolicies = new ArrayList<>();
        private final Map<UUID, AccountConsent> latest = new HashMap<>();
        private ConsentDecisionOutcome outcome = ConsentDecisionOutcome.RECORDED;
        private boolean called;
        private Instant recordedAt;
        private UUID correlationId;
        private String rejectionReason;

        @Override
        public List<PolicyDocumentVersion> findCurrentPolicies(String languageCode, Instant now) {
            return List.copyOf(currentPolicies);
        }

        @Override
        public Optional<AccountConsent> findLatestConsent(UUID accountId, UUID policyDocumentId) {
            return Optional.ofNullable(latest.get(policyDocumentId));
        }

        @Override
        public List<AccountConsent> findConsentHistory(UUID accountId, UUID policyDocumentId) {
            return latest.containsKey(policyDocumentId) ? List.of(latest.get(policyDocumentId)) : List.of();
        }

        @Override
        public ConsentDecisionResult recordDecision(
                UUID accountId,
                UUID policyDocumentId,
                ConsentDecision decision,
                UUID correlationId,
                Instant recordedAt) {
            called = true;
            this.recordedAt = recordedAt;
            this.correlationId = correlationId;
            if (outcome != ConsentDecisionOutcome.RECORDED) {
                return ConsentDecisionResult.rejected(outcome);
            }
            return ConsentDecisionResult.recorded(new AccountConsent(
                    UUID.randomUUID(), accountId, policyDocumentId, decision, null, recordedAt));
        }

        @Override
        public void recordConsentRejection(
                UUID accountId, String reasonCode, UUID correlationId, Instant occurredAt) {
            this.rejectionReason = reasonCode;
            this.correlationId = correlationId;
        }
    }
}
