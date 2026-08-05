package com.idea2strategy.backend.api.caseoperations;

import com.idea2strategy.backend.application.caseoperations.CaseSanctionCommandPort;
import com.idea2strategy.backend.application.caseoperations.CaseResponseDeadlinePolicy;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommandService;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommand;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseApiGuardCatalog;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueryService;
import com.idea2strategy.backend.application.caseoperations.OperatorEvidenceRedactor;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.persistence.caseoperations.OperatorCaseJooqAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
public class OperatorCaseConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "spring.datasource", name = "url")
    @ConditionalOnMissingBean(OperatorCaseJooqAdapter.class)
    OperatorCaseJooqAdapter operatorCaseJooqAdapter(JdbcTemplate jdbc, ObjectMapper json) {
        return new OperatorCaseJooqAdapter(jdbc, json);
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "idea2strategy.operator-auth",
            name = "enabled",
            havingValue = "true")
    @ConditionalOnProperty(
            prefix = "idea2strategy.operator-case.guard",
            name = {"queue-permission-id", "detail-permission-id"})
    OperatorCaseApiGuardCatalog configuredOperatorCaseApiGuardCatalog(
            @Value("${idea2strategy.operator-case.guard.queue-permission-id}") UUID queuePermissionId,
            @Value("${idea2strategy.operator-case.guard.detail-permission-id}") UUID detailPermissionId,
            @Value("${idea2strategy.operator-case.guard.assign-permission-id}") UUID assign,
            @Value("${idea2strategy.operator-case.guard.reassign-permission-id}") UUID reassign,
            @Value("${idea2strategy.operator-case.guard.unassign-permission-id}") UUID unassign,
            @Value("${idea2strategy.operator-case.guard.start-review-permission-id}") UUID startReview,
            @Value("${idea2strategy.operator-case.guard.request-information-permission-id}") UUID requestInformation,
            @Value("${idea2strategy.operator-case.guard.resolve-permission-id}") UUID resolve,
            @Value("${idea2strategy.operator-case.guard.reject-permission-id}") UUID reject,
            @Value("${idea2strategy.operator-case.guard.apply-sanction-permission-id}") UUID applySanction,
            @Value("${idea2strategy.operator-case.guard.release-sanction-permission-id}") UUID releaseSanction) {
        var permissions = new EnumMap<OperatorCaseCommand.Action, UUID>(OperatorCaseCommand.Action.class);
        permissions.put(OperatorCaseCommand.Action.ASSIGN, assign);
        permissions.put(OperatorCaseCommand.Action.REASSIGN, reassign);
        permissions.put(OperatorCaseCommand.Action.UNASSIGN, unassign);
        permissions.put(OperatorCaseCommand.Action.START_REVIEW, startReview);
        permissions.put(OperatorCaseCommand.Action.REQUEST_INFORMATION, requestInformation);
        permissions.put(OperatorCaseCommand.Action.RESOLVE, resolve);
        permissions.put(OperatorCaseCommand.Action.REJECT, reject);
        permissions.put(OperatorCaseCommand.Action.APPLY_SANCTION, applySanction);
        permissions.put(OperatorCaseCommand.Action.RELEASE_SANCTION, releaseSanction);
        var guard = new OperatorCaseApiGuardCatalog.Guard(queuePermissionId, detailPermissionId, permissions);
        return () -> guard;
    }

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
            CaseSanctionCommandPort sanctions,
            @Value("${idea2strategy.case.response-deadline.policy-version:case-response-v1}") String policyVersion,
            @Value("${idea2strategy.case.response-deadline.window:PT168H}") Duration responseWindow) {
        return new OperatorCaseCommandService(
                adapter, adapter, adapter, sanctions, adapter,
                new OperatorEvidenceRedactor(),
                new CaseResponseDeadlinePolicy(policyVersion, responseWindow), Clock.systemUTC());
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
