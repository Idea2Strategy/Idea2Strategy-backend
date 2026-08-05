package com.idea2strategy.backend.api.identity;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.UUID;
import org.jooq.DSLContext;

public final class DatabaseIdentityEmailAddressResolver implements IdentityEmailAddressResolver {
    private final DSLContext dsl;
    private final AesGcmEmailProtector protector;

    public DatabaseIdentityEmailAddressResolver(DSLContext dsl, AesGcmEmailProtector protector) {
        this.dsl = dsl;
        this.protector = protector;
    }

    @Override
    public String requireEmail(UUID accountId) {
        String ciphertext = dsl.select(field(name("email_ciphertext"), String.class))
                .from(table(name("identity", "account_emails")))
                .where(field(name("account_id"), UUID.class).eq(accountId))
                .fetchOptional(field(name("email_ciphertext"), String.class))
                .orElseThrow(() -> new EmailDeliveryUnavailableException(false));
        return protector.reveal(ciphertext);
    }

    @Override
    public String toString() {
        return "DatabaseIdentityEmailAddressResolver[database-backed]";
    }
}
