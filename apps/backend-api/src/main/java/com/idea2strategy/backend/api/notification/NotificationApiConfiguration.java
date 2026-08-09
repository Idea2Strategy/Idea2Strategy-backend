package com.idea2strategy.backend.api.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.notification.EmailNotificationPreferenceService;
import com.idea2strategy.backend.application.notification.NotificationQueryService;
import com.idea2strategy.backend.application.notification.NotificationService;
import com.idea2strategy.backend.persistence.notification.NotificationPersistenceAdapter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class NotificationApiConfiguration {
    @Bean
    @ConditionalOnMissingBean(NotificationPersistenceAdapter.class)
    NotificationPersistenceAdapter notificationPersistenceAdapter(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new NotificationPersistenceAdapter(jdbc, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock notificationClock() {
        return Clock.systemUTC();
    }

    @Bean
    NotificationService notificationService(NotificationPersistenceAdapter adapter, Clock identityClock) {
        return new NotificationService(adapter, adapter, adapter, identityClock);
    }

    @Bean
    NotificationQueryService notificationQueryService(NotificationPersistenceAdapter adapter) {
        return new NotificationQueryService(adapter);
    }

    @Bean
    EmailNotificationPreferenceService emailNotificationPreferenceService(
            NotificationPersistenceAdapter adapter, Clock identityClock) {
        return new EmailNotificationPreferenceService(adapter, identityClock);
    }
}
