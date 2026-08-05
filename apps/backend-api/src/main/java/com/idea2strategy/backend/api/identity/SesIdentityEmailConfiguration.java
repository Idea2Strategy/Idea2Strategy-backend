package com.idea2strategy.backend.api.identity;

import java.net.URI;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "email.delivery", name = "enabled", havingValue = "true")
public class SesIdentityEmailConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "email.delivery", name = "provider", havingValue = "ses")
    SesV2Client identitySesClient(@Value("${email.delivery.aws-region}") String region) {
        // SES SendEmail has no idempotency token. Never automatically replay an ambiguous send.
        return SesV2Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                        .build())
                .build();
    }

    @Bean
    IdentityEmailAddressResolver identityEmailAddressResolver(
            DSLContext dsl, AesGcmEmailProtector protector) {
        return new DatabaseIdentityEmailAddressResolver(dsl, protector);
    }

    @Bean
    SesIdentityEmailDelivery sesIdentityEmailDelivery(
            SesV2Client identitySesClient,
            IdentityEmailAddressResolver addresses,
            @Value("${email.delivery.from-address}") String fromAddress,
            @Value("${email.delivery.base-url}") URI baseUrl) {
        return new SesIdentityEmailDelivery(identitySesClient, fromAddress, baseUrl, addresses);
    }
}
