package com.idea2strategy.backend.api.notification;

import com.idea2strategy.backend.application.notification.NotificationPreferenceService;
import com.idea2strategy.backend.application.notification.NotificationQueryService;
import com.idea2strategy.backend.application.notification.NotificationService;
import com.idea2strategy.backend.persistence.notification.NotificationPersistenceAdapter;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationApiConfiguration {
    @Bean
    NotificationService notificationService(NotificationPersistenceAdapter adapter, Clock identityClock) {
        return new NotificationService(adapter, adapter, adapter, identityClock);
    }

    @Bean
    NotificationQueryService notificationQueryService(NotificationPersistenceAdapter adapter) {
        return new NotificationQueryService(adapter);
    }

    @Bean
    NotificationPreferenceService notificationPreferenceService(
            NotificationPersistenceAdapter adapter, Clock identityClock) {
        return new NotificationPreferenceService(adapter, adapter, identityClock);
    }
}
