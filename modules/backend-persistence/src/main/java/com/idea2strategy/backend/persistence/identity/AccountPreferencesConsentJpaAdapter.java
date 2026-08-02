package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.AccountPreferencesCommandPort;
import com.idea2strategy.backend.application.identity.ConsentDecisionOutcome;
import com.idea2strategy.backend.application.identity.ConsentDecisionResult;
import com.idea2strategy.backend.application.identity.PolicyConsentCommandPort;
import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.ConsentDecision;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AccountPreferencesConsentJpaAdapter
        implements AccountPreferencesCommandPort, PolicyConsentCommandPort {
    private final EntityManager entityManager;

    public AccountPreferencesConsentJpaAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public AccountPreferences update(
            UUID accountId, AccountPreferences preferences, UUID correlationId) {
        lockActiveAccount(accountId);
        int updated = entityManager.createNativeQuery("""
                        update identity.account_preferences
                        set language_code = :languageCode,
                            timezone_name = :timezoneName,
                            theme_preference = cast(:themePreference as identity.theme_preference),
                            updated_at = :updatedAt
                        where account_id = :accountId
                        """)
                .setParameter("languageCode", preferences.languageCode())
                .setParameter("timezoneName", preferences.timezoneName())
                .setParameter("themePreference", preferences.themePreference().name())
                .setParameter("updatedAt", utc(preferences.updatedAt()))
                .setParameter("accountId", accountId)
                .executeUpdate();
        if (updated != 1) {
            throw new IllegalStateException("Account preferences are missing");
        }
        insertAuditEvent(
                accountId,
                "ACCOUNT_PREFERENCES_UPDATED",
                null,
                correlationId,
                "preferences:update:" + correlationId,
                preferences.updatedAt());
        return preferences;
    }

    @Override
    @Transactional
    public void recordPreferenceRejection(
            UUID accountId, String reasonCode, UUID correlationId, Instant occurredAt) {
        lockActiveAccount(accountId);
        insertAuditEvent(
                accountId,
                "ACCOUNT_PREFERENCES_UPDATE_REJECTED",
                reasonCode,
                correlationId,
                "preferences:rejected:" + correlationId,
                occurredAt);
    }

    @Override
    @Transactional
    public void recordConsentRejection(
            UUID accountId, String reasonCode, UUID correlationId, Instant occurredAt) {
        lockActiveAccount(accountId);
        insertAuditEvent(
                accountId,
                "POLICY_CONSENT_REJECTED",
                reasonCode,
                correlationId,
                "policy-consent:rejected:" + correlationId,
                occurredAt);
    }

    @Override
    @Transactional
    public ConsentDecisionResult recordDecision(
            UUID accountId,
            UUID policyDocumentId,
            ConsentDecision decision,
            UUID correlationId,
            Instant recordedAt) {
        lockActiveAccount(accountId);
        if (!isCurrentPolicy(policyDocumentId, recordedAt)) {
            insertAuditEvent(
                    accountId,
                    "POLICY_CONSENT_REJECTED",
                    "POLICY_NOT_CURRENT",
                    correlationId,
                    "policy-consent:rejected:" + correlationId,
                    recordedAt);
            return ConsentDecisionResult.rejected(ConsentDecisionOutcome.POLICY_NOT_CURRENT);
        }

        UUID supersededId = findCurrentHead(accountId, policyDocumentId);
        UUID consentId = UUID.randomUUID();
        entityManager.createNativeQuery("""
                        insert into identity.account_consents
                            (id, account_id, policy_document_id, decision,
                             supersedes_consent_id, recorded_at)
                        values (:id, :accountId, :policyDocumentId,
                                cast(:decision as identity.consent_decision),
                                cast(:supersedesConsentId as uuid), :recordedAt)
                        """)
                .setParameter("id", consentId)
                .setParameter("accountId", accountId)
                .setParameter("policyDocumentId", policyDocumentId)
                .setParameter("decision", decision.name())
                .setParameter("supersedesConsentId", supersededId)
                .setParameter("recordedAt", utc(recordedAt))
                .executeUpdate();

        var consent = new AccountConsent(
                consentId, accountId, policyDocumentId, decision, supersededId, recordedAt);
        insertAuditEvent(
                accountId,
                "POLICY_CONSENT_RECORDED",
                decision.name(),
                correlationId,
                "policy-consent:recorded:" + correlationId,
                recordedAt);
        return ConsentDecisionResult.recorded(consent);
    }

    private void lockActiveAccount(UUID accountId) {
        try {
            entityManager.createNativeQuery("""
                            select id
                            from identity.accounts
                            where id = :accountId
                              and lifecycle_status = cast('ACTIVE' as identity.account_lifecycle_status)
                            for update
                            """)
                    .setParameter("accountId", accountId)
                    .getSingleResult();
        } catch (NoResultException exception) {
            throw new IllegalStateException("Active account not found", exception);
        }
    }

    private boolean isCurrentPolicy(UUID policyDocumentId, Instant recordedAt) {
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*)
                        from identity.policy_documents target
                        where target.id = :policyDocumentId
                          and target.published_at <= :recordedAt
                          and target.retired_at is null
                          and not exists (
                              select 1
                              from identity.policy_documents newer
                              where newer.policy_code = target.policy_code
                                and newer.language_code = target.language_code
                                and newer.published_at <= :recordedAt
                                and newer.retired_at is null
                                and (
                                    newer.published_at > target.published_at
                                    or (newer.published_at = target.published_at
                                        and newer.version > target.version)
                                    or (newer.published_at = target.published_at
                                        and newer.version = target.version
                                        and newer.id > target.id)
                                )
                          )
                        """)
                .setParameter("policyDocumentId", policyDocumentId)
                .setParameter("recordedAt", utc(recordedAt))
                .getSingleResult();
        return count.intValue() == 1;
    }

    private UUID findCurrentHead(UUID accountId, UUID policyDocumentId) {
        @SuppressWarnings("unchecked")
        List<UUID> rows = entityManager.createNativeQuery("""
                        select consent.id
                        from identity.account_consents consent
                        where consent.account_id = :accountId
                          and consent.policy_document_id = :policyDocumentId
                          and not exists (
                              select 1
                              from identity.account_consents successor
                              where successor.supersedes_consent_id = consent.id
                          )
                        order by consent.recorded_at desc, consent.id desc
                        limit 1
                        """, UUID.class)
                .setParameter("accountId", accountId)
                .setParameter("policyDocumentId", policyDocumentId)
                .getResultList();
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void insertAuditEvent(
            UUID accountId,
            String eventType,
            String reasonCode,
            UUID correlationId,
            String idempotencyKey,
            Instant occurredAt) {
        entityManager.createNativeQuery("""
                        insert into identity.authentication_events
                            (id, account_id, event_sequence, event_type, actor_type, actor_id,
                             reason_code, correlation_id, idempotency_key, occurred_at)
                        values (gen_random_uuid(), :accountId,
                                (select coalesce(max(event_sequence), 0) + 1
                                 from identity.authentication_events
                                 where account_id = :accountId),
                                :eventType, 'ACCOUNT', :accountId, :reasonCode,
                                :correlationId, :idempotencyKey, :occurredAt)
                        on conflict (account_id, idempotency_key) do nothing
                        """)
                .setParameter("accountId", accountId)
                .setParameter("eventType", eventType)
                .setParameter("reasonCode", reasonCode)
                .setParameter("correlationId", correlationId)
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("occurredAt", utc(occurredAt))
                .executeUpdate();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
