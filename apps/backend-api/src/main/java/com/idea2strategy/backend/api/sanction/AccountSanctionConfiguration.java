package com.idea2strategy.backend.api.sanction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionAuthorizationPort;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionExpiryWorker;
import com.idea2strategy.backend.persistence.sanction.AccountSanctionJdbcAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class AccountSanctionConfiguration {
    static final UUID APPLY_PERMISSION = UUID.fromString("40000000-0000-4000-8000-000000000004");
    static final UUID LIFT_PERMISSION = UUID.fromString("50000000-0000-4000-8000-000000000005");

    @Bean
    AccountSanctionJdbcAdapter accountSanctionJdbcAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        return new AccountSanctionJdbcAdapter(jdbc, json);
    }

    @Bean
    @ConditionalOnBean(AccountSanctionAuthorizationPort.class)
    AccountSanctionCommandService accountSanctionCommandService(
            AccountSanctionJdbcAdapter adapter,
            AccountSanctionAuthorizationPort authorization,
            @Value("${idea2strategy.operator-sanction.guard.apply-permission-id:"
                    + "40000000-0000-4000-8000-000000000004}") UUID applyPermissionId,
            @Value("${idea2strategy.operator-sanction.guard.lift-permission-id:"
                    + "50000000-0000-4000-8000-000000000005}") UUID liftPermissionId) {
        return new AccountSanctionCommandService(
                adapter, authorization, adapter, adapter,
                applyPermissionId, liftPermissionId, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(AccountSanctionCommandService.class)
    AccountSanctionExpiryWorker accountSanctionExpiryWorker(
            AccountSanctionJdbcAdapter adapter, AccountSanctionCommandService commands) {
        return new AccountSanctionExpiryWorker(adapter, commands);
    }
}
