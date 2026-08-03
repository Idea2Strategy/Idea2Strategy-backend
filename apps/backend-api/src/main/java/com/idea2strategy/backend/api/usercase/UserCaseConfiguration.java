package com.idea2strategy.backend.api.usercase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.usercase.UserCaseEvidenceOwnershipPort;
import com.idea2strategy.backend.application.usercase.UserCaseService;
import com.idea2strategy.backend.persistence.usercase.UserCaseJooqStore;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class UserCaseConfiguration {
    @Bean
    @ConditionalOnMissingBean(UserCaseEvidenceOwnershipPort.class)
    UserCaseEvidenceOwnershipPort unavailableEvidenceOwnership() {
        return (accountId, reference, verifiedAt) -> java.util.Optional.empty();
    }

    @Bean
    UserCaseJooqStore userCaseJooqStore(
            DSLContext dsl, UserCaseEvidenceOwnershipPort ownership, ObjectMapper objectMapper) {
        return new UserCaseJooqStore(dsl, ownership, objectMapper);
    }

    @Bean
    UserCaseService userCaseService(UserCaseJooqStore store) {
        return new UserCaseService(store, Clock.systemUTC());
    }
}
