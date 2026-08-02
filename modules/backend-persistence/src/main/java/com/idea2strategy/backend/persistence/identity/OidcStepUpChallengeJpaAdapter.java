package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationMethod;
import com.idea2strategy.backend.application.identity.AccountLifecycleAuthenticationProof;
import com.idea2strategy.backend.application.identity.AccountReactivationEligibility;
import com.idea2strategy.backend.application.identity.AccountReactivationEligibilityPort;
import com.idea2strategy.backend.application.identity.AuthenticationRejectedException;
import com.idea2strategy.backend.application.identity.OidcStepUpChallengePort;
import com.idea2strategy.backend.application.identity.StoredOidcStepUpChallenge;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OidcStepUpChallengeJpaAdapter
        implements OidcStepUpChallengePort, AccountReactivationEligibilityPort {
    private static final long PROVIDER_CHALLENGE_LOCK_NAMESPACE = 130_000_000L;
    private static final long MAX_PENDING_CHALLENGES_PER_PROVIDER = 10_000L;
    private final EntityManager entityManager;

    public OidcStepUpChallengeJpaAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void create(
            UUID challengeId,
            short providerId,
            String nonceDigest,
            short digestKeyVersion,
            Instant requestedAt,
            Instant expiresAt) {
        entityManager.createNativeQuery("select pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", PROVIDER_CHALLENGE_LOCK_NAMESPACE + providerId)
                .getSingleResult();
        entityManager.createNativeQuery("""
                        delete from identity.oidc_step_up_nonces
                        where expires_at <= :requestedAt and consumed_at is null
                        """)
                .setParameter("requestedAt", utc(requestedAt))
                .executeUpdate();
        Number pending = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.oidc_step_up_nonces
                        where provider_id = :providerId and consumed_at is null
                        """)
                .setParameter("providerId", providerId)
                .getSingleResult();
        if (pending.longValue() >= MAX_PENDING_CHALLENGES_PER_PROVIDER) {
            throw new AuthenticationRejectedException("OIDC step-up challenge capacity is unavailable");
        }
        entityManager.createNativeQuery("""
                        insert into identity.oidc_step_up_nonces
                            (id, provider_id, nonce_digest, digest_key_version, requested_at, expires_at)
                        values (:id, :providerId, :nonceDigest, :keyVersion, :requestedAt, :expiresAt)
                        """)
                .setParameter("id", challengeId)
                .setParameter("providerId", providerId)
                .setParameter("nonceDigest", nonceDigest)
                .setParameter("keyVersion", digestKeyVersion)
                .setParameter("requestedAt", utc(requestedAt))
                .setParameter("expiresAt", utc(expiresAt))
                .executeUpdate();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<StoredOidcStepUpChallenge> find(UUID challengeId) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        select nonce.id, nonce.provider_id, provider.code, nonce.nonce_digest,
                               nonce.expires_at, nonce.consumed_at, nonce.consumed_by_account_id
                        from identity.oidc_step_up_nonces nonce
                        join identity.auth_providers provider on provider.id = nonce.provider_id
                        where nonce.id = :id
                        """)
                .setParameter("id", challengeId)
                .getResultList();
        return rows.stream().findFirst().map(row -> new StoredOidcStepUpChallenge(
                (UUID) row[0],
                ((Number) row[1]).shortValue(),
                (String) row[2],
                (String) row[3],
                instant(row[4]),
                instant(row[5]),
                (UUID) row[6]));
    }

    @Override
    @Transactional
    public boolean registerVerificationAttempt(UUID challengeId, Instant attemptedAt) {
        int updated = entityManager.createNativeQuery("""
                        update identity.oidc_step_up_nonces
                        set verification_attempt_count = verification_attempt_count + 1,
                            last_verification_attempt_at = :attemptedAt
                        where id = :challengeId
                          and consumed_at is null
                          and expires_at > :attemptedAt
                          and verification_attempt_count < 5
                        """)
                .setParameter("challengeId", challengeId)
                .setParameter("attemptedAt", utc(attemptedAt))
                .executeUpdate();
        return updated == 1;
    }

    @Override
    public AccountReactivationEligibility evaluateAndConsume(
            UUID accountId,
            AccountLifecycleAuthenticationProof proof,
            Set<UUID> acceptedPolicyDocumentIds,
            UUID correlationId,
            Instant now) {
        String languageCode = accountLanguage(accountId);
        RequiredPolicySet requiredPolicies = requiredPolicyDocumentIds(languageCode, now);
        if (!requiredPolicies.resolvable()
                || !requiredPolicies.documentIds().equals(acceptedPolicyDocumentIds)) {
            return AccountReactivationEligibility.rejected("CURRENT_REQUIRED_CONSENT_MISSING");
        }
        if (hasEffectiveSanction(accountId, now) || hasCompromisedPassword(accountId, proof)) {
            return AccountReactivationEligibility.rejected("ACCOUNT_REACTIVATION_RESTRICTED");
        }
        if (proof.method() == AccountLifecycleAuthenticationMethod.OIDC && !consumeOidcChallenge(accountId, proof, now)) {
            return AccountReactivationEligibility.rejected("OIDC_STEP_UP_REPLAYED_OR_EXPIRED");
        }
        recordAcceptedConsents(accountId, requiredPolicies.documentIds(), now);
        return AccountReactivationEligibility.allowed();
    }

    private String accountLanguage(UUID accountId) {
        List<?> values = entityManager.createNativeQuery("""
                        select language_code from identity.account_preferences where account_id = :accountId
                        """)
                .setParameter("accountId", accountId)
                .getResultList();
        return values.isEmpty() ? null : (String) values.getFirst();
    }

    @SuppressWarnings("unchecked")
    private RequiredPolicySet requiredPolicyDocumentIds(String languageCode, Instant now) {
        List<Object[]> rows = entityManager.createNativeQuery("""
                        with current_documents as (
                            select id, is_required,
                                   policy_code, language_code,
                                   row_number() over (
                                       partition by policy_code, language_code
                                       order by published_at desc, version desc, id desc
                                   ) as current_rank
                            from identity.policy_documents
                            where published_at <= :now
                              and retired_at is null
                        ), required_policy_codes as (
                            select distinct policy_code
                            from current_documents
                            where current_rank = 1 and is_required
                        )
                        select required.policy_code, resolved.id
                        from required_policy_codes required
                        left join lateral (
                                select document.id
                                from current_documents document
                                where document.current_rank = 1
                                  and document.policy_code = required.policy_code
                                  and document.is_required
                                  and document.language_code in (:languageCode, 'ko')
                                order by case when document.language_code = :languageCode then 0 else 1 end,
                                         document.id
                                limit 1
                        ) resolved on true
                        order by required.policy_code
                        """)
                .setParameter("languageCode", languageCode)
                .setParameter("now", utc(now))
                .getResultList();
        if (rows.stream().anyMatch(row -> row[1] == null)) {
            return new RequiredPolicySet(false, Set.of());
        }
        return new RequiredPolicySet(true, rows.stream().map(row -> (UUID) row[1])
                .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    @SuppressWarnings("unchecked")
    private void recordAcceptedConsents(UUID accountId, Set<UUID> policyDocumentIds, Instant now) {
        for (UUID policyDocumentId : policyDocumentIds) {
            List<Object[]> heads = entityManager.createNativeQuery("""
                            select consent.id, cast(consent.decision as text)
                            from identity.account_consents consent
                            where consent.account_id = :accountId
                              and consent.policy_document_id = :policyDocumentId
                              and not exists (
                                  select 1 from identity.account_consents successor
                                  where successor.supersedes_consent_id = consent.id
                              )
                            order by consent.recorded_at desc, consent.id desc
                            limit 1
                            """)
                    .setParameter("accountId", accountId)
                    .setParameter("policyDocumentId", policyDocumentId)
                    .getResultList();
            if (!heads.isEmpty() && "ACCEPTED".equals(heads.getFirst()[1])) {
                continue;
            }
            entityManager.createNativeQuery("""
                            insert into identity.account_consents
                                (id, account_id, policy_document_id, decision,
                                 supersedes_consent_id, recorded_at)
                            values (:id, :accountId, :policyDocumentId,
                                    cast('ACCEPTED' as identity.consent_decision), :supersedes, :recordedAt)
                            """)
                    .setParameter("id", UUID.randomUUID())
                    .setParameter("accountId", accountId)
                    .setParameter("policyDocumentId", policyDocumentId)
                    .setParameter("supersedes", heads.isEmpty() ? null : heads.getFirst()[0])
                    .setParameter("recordedAt", utc(now))
                    .executeUpdate();
        }
    }

    private boolean hasEffectiveSanction(UUID accountId, Instant now) {
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*) from identity.account_sanctions
                        where account_id = :accountId
                          and status = cast('ACTIVE' as identity.sanction_status)
                          and effective_at <= :now
                          and (expires_at is null or expires_at > :now)
                        """)
                .setParameter("accountId", accountId)
                .setParameter("now", utc(now))
                .getSingleResult();
        return count.longValue() > 0;
    }

    private boolean hasCompromisedPassword(UUID accountId, AccountLifecycleAuthenticationProof proof) {
        if (proof.method() != AccountLifecycleAuthenticationMethod.PASSWORD) {
            return false;
        }
        Number count = (Number) entityManager.createNativeQuery("""
                        select count(*)
                        from identity.login_identities login
                        join identity.auth_providers provider on provider.id = login.provider_id
                        join identity.password_credentials credential on credential.login_identity_id = login.id
                        where login.account_id = :accountId
                          and login.status = cast('ACTIVE' as identity.login_identity_status)
                          and provider.provider_type = cast('PASSWORD' as identity.auth_provider_type)
                          and credential.compromised_at is not null
                        """)
                .setParameter("accountId", accountId)
                .getSingleResult();
        return count.longValue() > 0;
    }

    private boolean consumeOidcChallenge(
            UUID accountId, AccountLifecycleAuthenticationProof proof, Instant now) {
        int updated = entityManager.createNativeQuery("""
                        update identity.oidc_step_up_nonces nonce
                        set consumed_at = :now, consumed_by_account_id = :accountId
                        from identity.auth_providers provider
                        where nonce.id = :challengeId
                          and provider.id = nonce.provider_id
                          and provider.code = :providerCode
                          and provider.provider_type = cast('OIDC' as identity.auth_provider_type)
                          and nonce.consumed_at IS NULL
                          and nonce.expires_at > :now
                        """)
                .setParameter("now", utc(now))
                .setParameter("accountId", accountId)
                .setParameter("challengeId", proof.challengeId())
                .setParameter("providerCode", proof.providerCode())
                .executeUpdate();
        return updated == 1;
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        return ((OffsetDateTime) value).toInstant();
    }

    private record RequiredPolicySet(boolean resolvable, Set<UUID> documentIds) {}
}
