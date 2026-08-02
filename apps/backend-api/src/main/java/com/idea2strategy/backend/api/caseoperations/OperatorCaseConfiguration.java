package com.idea2strategy.backend.api.caseoperations;

import com.idea2strategy.backend.application.caseoperations.CaseSanctionCommandPort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommandService;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommand;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseApiGuardCatalog;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueryService;
import com.idea2strategy.backend.application.caseoperations.OperatorEvidenceRedactor;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.persistence.caseoperations.OperatorCaseJooqAdapter;
import java.time.Clock;
import java.util.EnumMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OperatorCaseConfiguration {
    @Bean
    @ConditionalOnMissingBean(OperatorCaseApiGuardCatalog.class)
    OperatorCaseApiGuardCatalog operatorCaseApiGuardCatalog() {
        var permissions = new EnumMap<OperatorCaseCommand.Action, UUID>(OperatorCaseCommand.Action.class);
        int suffix = 20;
        for (OperatorCaseCommand.Action action : OperatorCaseCommand.Action.values()) {
            permissions.put(action, permission(suffix++));
        }
        var guard = new OperatorCaseApiGuardCatalog.Guard(permission(18), permission(19), permissions);
        return () -> guard;
    }

    @Bean
    @ConditionalOnMissingBean(CaseSanctionCommandPort.class)
    CaseSanctionCommandPort caseSanctionCommandPort(
            ObjectProvider<AccountSanctionCommandService> services) {
        return new AccountSanctionCaseCommandAdapter(services);
    }

    @Bean
    @ConditionalOnBean({OperatorCaseJooqAdapter.class, CaseSanctionCommandPort.class})
    OperatorCaseCommandService operatorCaseCommandService(
            OperatorCaseJooqAdapter adapter,
            CaseSanctionCommandPort sanctions) {
        return new OperatorCaseCommandService(
                adapter, adapter, adapter, sanctions, adapter,
                new OperatorEvidenceRedactor(), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(OperatorCaseJooqAdapter.class)
    OperatorCaseQueryService operatorCaseQueryService(OperatorCaseJooqAdapter adapter) {
        return new OperatorCaseQueryService(
                adapter, adapter, new OperatorEvidenceRedactor(), Clock.systemUTC());
    }

    private static UUID permission(int suffix) {
        return UUID.fromString("a2000000-0000-4000-8000-%012d".formatted(suffix));
    }
}
