package com.idea2strategy.backend.api.delegation;

import com.idea2strategy.backend.application.delegation.DelegatedAuthorizationService;
import com.idea2strategy.backend.persistence.delegation.DelegatedAuthorizationJooqAdapter;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
@Import(DelegatedAuthorizationJooqAdapter.class)
public class DelegationConfiguration {
    @Bean
    DelegatedAuthorizationService delegatedAuthorizationService(
            DelegatedAuthorizationJooqAdapter adapter,
            HmacDelegatedCredentials credentials,
            Clock identityClock) {
        return new DelegatedAuthorizationService(adapter, credentials, identityClock, UUID::randomUUID);
    }

    /**
     * Falls back to the refresh-token key so a deployment that has not provisioned a dedicated
     * delegation key still stores digests rather than raw credentials. Both are 256-bit identity
     * secrets from the same store; a separate key is preferable and is what the property is for.
     */
    @Bean
    HmacDelegatedCredentials hmacDelegatedCredentials(
            @Value("${identity.crypto.delegated-credential-hmac-key:${identity.crypto.refresh-token-hmac-key}}")
                    String key,
            @Value("${identity.crypto.delegated-credential-key-version:1}") short keyVersion) {
        return new HmacDelegatedCredentials(Base64.getDecoder().decode(key), keyVersion);
    }
}
