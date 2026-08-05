package com.idea2strategy.backend.batch;

import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.sesv2.SesV2Client;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "email.delivery", name = "enabled", havingValue = "true")
public class SesNotificationEmailConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "email.delivery", name = "provider", havingValue = "ses")
    SesV2Client notificationSesClient(@Value("${email.delivery.aws-region}") String region) {
        // The durable outbox owns retries; SDK retries could duplicate an accepted email.
        return SesV2Client.builder()
                .region(Region.of(region))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build())
                        .build())
                .build();
    }

    @Bean
    NotificationRecipientResolver notificationRecipientResolver(
            JdbcTemplate jdbc,
            @Value("${identity.crypto.email-encryption-key}") String encodedKey,
            @Value("${identity.crypto.email-encryption-key-version:1}") short keyVersion) {
        try {
            return new EncryptedNotificationRecipientResolver(
                    jdbc, Base64.getDecoder().decode(encodedKey), keyVersion);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Identity email encryption key must use standard Base64", exception);
        }
    }

    @Bean
    SesNotificationEmailDeliveryGateway sesNotificationEmailDeliveryGateway(
            SesV2Client notificationSesClient,
            NotificationRecipientResolver recipients,
            @Value("${email.delivery.from-address}") String fromAddress) {
        return new SesNotificationEmailDeliveryGateway(notificationSesClient, fromAddress, recipients);
    }
}
