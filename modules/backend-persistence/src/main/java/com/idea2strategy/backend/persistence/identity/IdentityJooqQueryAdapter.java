package com.idea2strategy.backend.persistence.identity;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.idea2strategy.backend.application.identity.AccountLifecycleStatus;
import com.idea2strategy.backend.application.identity.EmailStatus;
import com.idea2strategy.backend.application.identity.IdentityQueryPort;
import com.idea2strategy.backend.application.identity.LoginIdentityStatus;
import com.idea2strategy.backend.application.identity.PasswordLoginAccount;
import com.idea2strategy.backend.application.identity.RegistrationQueryPort;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class IdentityJooqQueryAdapter implements IdentityQueryPort, RegistrationQueryPort {
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
}
