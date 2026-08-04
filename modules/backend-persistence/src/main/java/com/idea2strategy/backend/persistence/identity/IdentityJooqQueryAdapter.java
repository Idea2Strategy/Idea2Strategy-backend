package com.idea2strategy.backend.persistence.identity;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import com.idea2strategy.backend.application.identity.AccountRecoveryQueryPort;
import com.idea2strategy.backend.application.identity.ActiveSession;
import com.idea2strategy.backend.application.identity.EmailStatus;
import com.idea2strategy.backend.application.identity.IdentityQueryPort;
import com.idea2strategy.backend.application.identity.LoginIdentityStatus;
import com.idea2strategy.backend.application.identity.OidcIdentityQueryPort;
import com.idea2strategy.backend.application.identity.OidcLoginAccount;
import com.idea2strategy.backend.application.identity.OidcProvider;
import com.idea2strategy.backend.application.identity.PasswordLoginAccount;
import com.idea2strategy.backend.application.identity.PasswordRecoveryAccount;
import com.idea2strategy.backend.application.identity.RegistrationQueryPort;
import com.idea2strategy.backend.application.identity.SessionQueryPort;
import com.idea2strategy.backend.application.identity.StoredSession;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityJooqQueryAdapter
        implements IdentityQueryPort,
                RegistrationQueryPort,
                OidcIdentityQueryPort,
                SessionQueryPort,
                AccountRecoveryQueryPort {
    private final DSLContext dsl;

    public IdentityJooqQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public boolean emailExists(String emailLookupHmac) {
        return dsl.fetchExists(dsl.selectOne()
                .from(table(name("identity", "account_emails")))
                .where(field(name("email_lookup_hmac"), String.class).eq(emailLookupHmac)));
    }

    @Override
    public Optional<PasswordRecoveryAccount> findPasswordRecoveryByEmailLookup(String emailLookup) {
        var emails = table(name("identity", "account_emails")).as("email");
        var accounts = table(name("identity", "accounts")).as("account");
        var identities = table(name("identity", "login_identities")).as("login");
        var providers = table(name("identity", "auth_providers")).as("provider");
        var credentials = table(name("identity", "password_credentials")).as("credential");
        var security = table(name("identity", "account_security_states")).as("security");
        var accountId = field(name("account", "id"), UUID.class);
        var loginId = field(name("login", "id"), UUID.class);
        var authEpoch = field(name("security", "auth_epoch"), Long.class);
        var credentialVersion = field(name("credential", "credential_version"), Long.class);
        return dsl.select(accountId, loginId, authEpoch, credentialVersion)
                .from(emails)
                .join(accounts).on(field(name("email", "account_id"), UUID.class).eq(accountId))
                .join(identities).on(field(name("login", "account_id"), UUID.class).eq(accountId))
                .join(providers).on(field(name("provider", "id"), Short.class)
                        .eq(field(name("login", "provider_id"), Short.class)))
                .join(credentials).on(field(name("credential", "login_identity_id"), UUID.class).eq(loginId))
                .join(security).on(field(name("security", "account_id"), UUID.class).eq(accountId))
                .where(field(name("email", "email_lookup_hmac"), String.class).eq(emailLookup)
                        .and(field(name("email", "status")).cast(String.class).eq("VERIFIED"))
                        .and(field(name("account", "lifecycle_status")).cast(String.class).eq("ACTIVE"))
                        .and(field(name("login", "status")).cast(String.class).eq("ACTIVE"))
                        .and(field(name("provider", "code"), String.class).eq("PASSWORD")))
                .fetchOptional(record -> new PasswordRecoveryAccount(
                        record.value1(), record.value2(), record.value3(), record.value4()));
    }

    @Override
    public Optional<PasswordRecoveryAccount> findPasswordRecoveryByAccountId(UUID requestedAccountId) {
        var accounts = table(name("identity", "accounts")).as("account");
        var identities = table(name("identity", "login_identities")).as("login");
        var providers = table(name("identity", "auth_providers")).as("provider");
        var credentials = table(name("identity", "password_credentials")).as("credential");
        var security = table(name("identity", "account_security_states")).as("security");
        var accountId = field(name("account", "id"), UUID.class);
        var loginId = field(name("login", "id"), UUID.class);
        var authEpoch = field(name("security", "auth_epoch"), Long.class);
        var credentialVersion = field(name("credential", "credential_version"), Long.class);
        return dsl.select(accountId, loginId, authEpoch, credentialVersion)
                .from(accounts)
                .join(identities).on(field(name("login", "account_id"), UUID.class).eq(accountId))
                .join(providers).on(field(name("provider", "id"), Short.class)
                        .eq(field(name("login", "provider_id"), Short.class)))
                .join(credentials).on(field(name("credential", "login_identity_id"), UUID.class).eq(loginId))
                .join(security).on(field(name("security", "account_id"), UUID.class).eq(accountId))
                .where(accountId.eq(requestedAccountId)
                        .and(field(name("account", "lifecycle_status")).cast(String.class).eq("ACTIVE"))
                        .and(field(name("login", "status")).cast(String.class).eq("ACTIVE"))
                        .and(field(name("provider", "code"), String.class).eq("PASSWORD")))
                .fetchOptional(record -> new PasswordRecoveryAccount(
                        record.value1(), record.value2(), record.value3(), record.value4()));
    }

    @Override
    public Optional<PasswordLoginAccount> findPasswordLoginByEmailLookup(String emailLookup) {
        var emails = table(name("identity", "account_emails")).as("email");
        var accounts = table(name("identity", "accounts")).as("account");
        var identities = table(name("identity", "login_identities")).as("login");
        var providers = table(name("identity", "auth_providers")).as("provider");
        var credentials = table(name("identity", "password_credentials")).as("credential");
        var security = table(name("identity", "account_security_states")).as("security");

        var accountId = field(name("account", "id"), UUID.class);
        var loginId = field(name("login", "id"), UUID.class);
        var accountStatus = field(name("account", "lifecycle_status")).cast(String.class);
        var emailStatus = field(name("email", "status")).cast(String.class);
        var loginStatus = field(name("login", "status")).cast(String.class);
        var passwordHash = field(name("credential", "password_hash"), String.class);
        var credentialVersion = field(name("credential", "credential_version"), Long.class);
        var authEpoch = field(name("security", "auth_epoch"), Long.class);

        return dsl.select(
                        accountId,
                        loginId,
                        accountStatus,
                        emailStatus,
                        loginStatus,
                        passwordHash,
                        credentialVersion,
                        authEpoch)
                .from(emails)
                .join(accounts).on(field(name("email", "account_id"), UUID.class).eq(accountId))
                .join(identities).on(field(name("login", "account_id"), UUID.class).eq(accountId))
                .join(providers).on(field(name("provider", "id"), Short.class)
                        .eq(field(name("login", "provider_id"), Short.class)))
                .join(credentials).on(field(name("credential", "login_identity_id"), UUID.class).eq(loginId))
                .join(security).on(field(name("security", "account_id"), UUID.class).eq(accountId))
                .where(field(name("email", "email_lookup_hmac"), String.class).eq(emailLookup)
                        .and(field(name("provider", "code"), String.class).eq("PASSWORD")))
                .fetchOptional(record -> new PasswordLoginAccount(
                        record.get(accountId),
                        record.get(loginId),
                        AccountLifecycleStatus.valueOf(record.get(accountStatus)),
                        EmailStatus.valueOf(record.get(emailStatus)),
                        LoginIdentityStatus.valueOf(record.get(loginStatus)),
                        record.get(passwordHash),
                        record.get(credentialVersion),
                        record.get(authEpoch)));
    }

    @Override
    public Optional<OidcProvider> findProvider(String providerCode) {
        var providers = table(name("identity", "auth_providers")).as("provider");
        var id = field(name("provider", "id"), Short.class);
        var code = field(name("provider", "code"), String.class);
        var issuer = field(name("provider", "issuer"), String.class);
        var active = field(name("provider", "is_active"), Boolean.class);
        return dsl.select(id, code, issuer, active)
                .from(providers)
                .where(code.eq(providerCode)
                        .and(field(name("provider", "provider_type")).cast(String.class).eq("OIDC")))
                .fetchOptional(record -> new OidcProvider(
                        record.get(id), record.get(code), record.get(issuer), Boolean.TRUE.equals(record.get(active))));
    }

    @Override
    public Optional<OidcLoginAccount> findActiveLogin(short providerId, String subjectHmac) {
        var identities = table(name("identity", "login_identities")).as("login");
        var accounts = table(name("identity", "accounts")).as("account");
        var security = table(name("identity", "account_security_states")).as("security");
        var accountId = field(name("account", "id"), UUID.class);
        var loginId = field(name("login", "id"), UUID.class);
        var accountStatus = field(name("account", "lifecycle_status")).cast(String.class);
        var loginStatus = field(name("login", "status")).cast(String.class);
        var authEpoch = field(name("security", "auth_epoch"), Long.class);
        return dsl.select(accountId, loginId, accountStatus, loginStatus, authEpoch)
                .from(identities)
                .join(accounts).on(field(name("login", "account_id"), UUID.class).eq(accountId))
                .join(security).on(field(name("security", "account_id"), UUID.class).eq(accountId))
                .where(field(name("login", "provider_id"), Short.class).eq(providerId)
                        .and(field(name("login", "provider_subject_hmac"), String.class).eq(subjectHmac))
                        .and(loginStatus.eq("ACTIVE")))
                .fetchOptional(record -> new OidcLoginAccount(
                        record.get(accountId),
                        record.get(loginId),
                        AccountLifecycleStatus.valueOf(record.get(accountStatus)),
                        LoginIdentityStatus.valueOf(record.get(loginStatus)),
                        record.get(authEpoch)));
    }

    @Override
    public boolean subjectExists(short providerId, String subjectHmac) {
        var identities = table(name("identity", "login_identities"));
        return dsl.fetchExists(dsl.selectOne()
                .from(identities)
                .where(field(name("provider_id"), Short.class).eq(providerId)
                        .and(field(name("provider_subject_hmac"), String.class).eq(subjectHmac))));
    }

    @Override
    public Optional<StoredSession> findByTokenDigest(String tokenDigest) {
        var sessions = table(name("identity", "sessions")).as("session");
        var accounts = table(name("identity", "accounts")).as("account");
        var logins = table(name("identity", "login_identities")).as("login");
        var security = table(name("identity", "account_security_states")).as("security");
        var credentials = table(name("identity", "password_credentials")).as("credential");

        return dsl.select(
                        field(name("session", "id"), UUID.class),
                        field(name("session", "account_id"), UUID.class),
                        field(name("session", "authenticated_by_login_identity_id"), UUID.class),
                        field(name("session", "auth_epoch_at_issue"), Long.class),
                        field(name("security", "auth_epoch"), Long.class),
                        field(name("session", "credential_version_at_issue"), Long.class),
                        field(name("credential", "credential_version"), Long.class),
                        field(name("account", "lifecycle_status")).cast(String.class),
                        field(name("login", "status")).cast(String.class),
                        field(name("session", "device_label"), String.class),
                        field(name("session", "issued_at"), Instant.class),
                        field(name("session", "last_seen_at"), Instant.class),
                        field(name("session", "expires_at"), Instant.class),
                        field(name("session", "revoked_at"), Instant.class),
                        field("""
                                exists (
                                    select 1 from identity.account_sanctions sanction
                                    where sanction.account_id = account.id
                                      and sanction.status = 'ACTIVE'
                                      and sanction.sanction_type in ('SUSPENSION', 'PERMANENT')
                                )
                                """, Boolean.class))
                .from(sessions)
                .join(accounts).on(field(name("account", "id"), UUID.class)
                        .eq(field(name("session", "account_id"), UUID.class)))
                .join(logins).on(field(name("login", "id"), UUID.class)
                        .eq(field(name("session", "authenticated_by_login_identity_id"), UUID.class)))
                .join(security).on(field(name("security", "account_id"), UUID.class)
                        .eq(field(name("session", "account_id"), UUID.class)))
                .leftJoin(credentials).on(field(name("credential", "login_identity_id"), UUID.class)
                        .eq(field(name("login", "id"), UUID.class)))
                .where(field(name("session", "token_digest"), String.class).eq(tokenDigest))
                .fetchOptional(record -> new StoredSession(
                        record.value1(),
                        record.value2(),
                        record.value3(),
                        record.value4(),
                        record.value5(),
                        record.value6(),
                        record.value7(),
                        AccountLifecycleStatus.valueOf(record.value8()),
                        LoginIdentityStatus.valueOf(record.value9()),
                        record.value10(),
                        record.value11(),
                        record.value12(),
                        record.value13(),
                        record.value14(),
                        Boolean.TRUE.equals(record.value15())));
    }

    @Override
    public List<ActiveSession> findActiveByAccountId(UUID accountId, Instant now) {
        var sessions = table(name("identity", "sessions")).as("session");
        var accounts = table(name("identity", "accounts")).as("account");
        var logins = table(name("identity", "login_identities")).as("login");
        var security = table(name("identity", "account_security_states")).as("security");
        var credentials = table(name("identity", "password_credentials")).as("credential");
        var credentialAtIssue = field(name("session", "credential_version_at_issue"), Long.class);
        var currentCredential = field(name("credential", "credential_version"), Long.class);
        return dsl.select(
                        field(name("session", "id"), UUID.class),
                        field(name("session", "device_label"), String.class),
                        field(name("session", "issued_at"), Instant.class),
                        field(name("session", "last_seen_at"), Instant.class),
                        field(name("session", "expires_at"), Instant.class))
                .from(sessions)
                .join(accounts).on(field(name("account", "id"), UUID.class)
                        .eq(field(name("session", "account_id"), UUID.class)))
                .join(logins).on(field(name("login", "id"), UUID.class)
                        .eq(field(name("session", "authenticated_by_login_identity_id"), UUID.class)))
                .join(security).on(field(name("security", "account_id"), UUID.class)
                        .eq(field(name("session", "account_id"), UUID.class)))
                .leftJoin(credentials).on(field(name("credential", "login_identity_id"), UUID.class)
                        .eq(field(name("login", "id"), UUID.class)))
                .where(field(name("session", "account_id"), UUID.class)
                        .eq(accountId)
                        .and(field(name("session", "revoked_at")).isNull())
                        .and(field(name("session", "expires_at"), Instant.class).gt(now))
                        .and(field(name("session", "auth_epoch_at_issue"), Long.class)
                                .eq(field(name("security", "auth_epoch"), Long.class)))
                        .and(field(name("account", "lifecycle_status")).cast(String.class).eq("ACTIVE"))
                        .and(field(name("login", "status")).cast(String.class).eq("ACTIVE"))
                        .and(credentialAtIssue.eq(currentCredential)
                                .or(credentialAtIssue.isNull().and(currentCredential.isNull()))))
                .orderBy(field(name("session", "last_seen_at")).desc(), field(name("session", "id")))
                .fetch(record -> new ActiveSession(
                        record.value1(), record.value2(), record.value3(), record.value4(), record.value5()));
    }
}
