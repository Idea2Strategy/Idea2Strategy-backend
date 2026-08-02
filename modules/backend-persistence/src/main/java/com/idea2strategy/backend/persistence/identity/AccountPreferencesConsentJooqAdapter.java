package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.identity.AccountPreferencesQueryPort;
import com.idea2strategy.backend.application.identity.PolicyConsentQueryPort;
import com.idea2strategy.backend.domain.identity.AccountConsent;
import com.idea2strategy.backend.domain.identity.AccountPreferences;
import com.idea2strategy.backend.domain.identity.ConsentDecision;
import com.idea2strategy.backend.domain.identity.PolicyDocumentVersion;
import com.idea2strategy.backend.domain.identity.ThemePreference;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

@Repository
public class AccountPreferencesConsentJooqAdapter
        implements AccountPreferencesQueryPort, PolicyConsentQueryPort {
    private final DSLContext dsl;

    public AccountPreferencesConsentJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<AccountPreferences> findByAccountId(UUID accountId) {
        return dsl.fetchOptional("""
                        select language_code, timezone_name, theme_preference::text as theme_preference,
                               updated_at
                        from identity.account_preferences preferences
                        join identity.accounts account on account.id = preferences.account_id
                        where preferences.account_id = ?
                          and account.lifecycle_status = cast('ACTIVE' as identity.account_lifecycle_status)
                        """, accountId)
                .map(record -> new AccountPreferences(
                        record.get("language_code", String.class),
                        record.get("timezone_name", String.class),
                        ThemePreference.valueOf(record.get("theme_preference", String.class)),
                        instant(record.get("updated_at"))));
    }

    @Override
    public List<PolicyDocumentVersion> findCurrentPolicies(String languageCode, Instant now) {
        return dsl.fetch("""
                        select id, policy_code, version, language_code, title, content_format,
                               content_text, content_hash, is_required, published_at, retired_at
                        from (
                            select document.*,
                                   row_number() over (
                                       partition by policy_code, language_code
                                       order by published_at desc, version desc, id desc
                                   ) as current_rank
                            from identity.policy_documents document
                            where language_code = ?
                              and published_at <= cast(? as timestamptz)
                              and retired_at is null
                        ) current_document
                        where current_rank = 1
                        order by policy_code, id
                        """, languageCode, utc(now))
                .map(this::policyDocument);
    }

    @Override
    public Optional<AccountConsent> findLatestConsent(UUID accountId, UUID policyDocumentId) {
        return dsl.fetchOptional("""
                        select consent.id, consent.account_id, consent.policy_document_id,
                               consent.decision::text as decision,
                               consent.supersedes_consent_id, consent.recorded_at
                        from identity.account_consents consent
                        where consent.account_id = ?
                          and consent.policy_document_id = ?
                          and not exists (
                              select 1
                              from identity.account_consents successor
                              where successor.supersedes_consent_id = consent.id
                          )
                        order by consent.recorded_at desc, consent.id desc
                        limit 1
                        """, accountId, policyDocumentId)
                .map(this::accountConsent);
    }

    @Override
    public List<AccountConsent> findConsentHistory(UUID accountId, UUID policyDocumentId) {
        return dsl.fetch("""
                        select id, account_id, policy_document_id, decision::text as decision,
                               supersedes_consent_id, recorded_at
                        from identity.account_consents
                        where account_id = ? and policy_document_id = ?
                        order by recorded_at, id
                        """, accountId, policyDocumentId)
                .map(this::accountConsent);
    }

    private PolicyDocumentVersion policyDocument(Record record) {
        return new PolicyDocumentVersion(
                record.get("id", UUID.class),
                record.get("policy_code", String.class),
                record.get("version", String.class),
                record.get("language_code", String.class),
                record.get("title", String.class),
                record.get("content_format", String.class),
                record.get("content_text", String.class),
                record.get("content_hash", String.class),
                Boolean.TRUE.equals(record.get("is_required", Boolean.class)),
                instant(record.get("published_at")),
                nullableInstant(record.get("retired_at")));
    }

    private AccountConsent accountConsent(Record record) {
        return new AccountConsent(
                record.get("id", UUID.class),
                record.get("account_id", UUID.class),
                record.get("policy_document_id", UUID.class),
                ConsentDecision.valueOf(record.get("decision", String.class)),
                record.get("supersedes_consent_id", UUID.class),
                instant(record.get("recorded_at")));
    }

    private static Instant nullableInstant(Object value) {
        return value == null ? null : instant(value);
    }

    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        throw new IllegalStateException("Unsupported timestamp value: " + value);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
