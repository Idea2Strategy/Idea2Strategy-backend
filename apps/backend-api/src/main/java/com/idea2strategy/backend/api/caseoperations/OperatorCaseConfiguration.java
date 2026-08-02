package com.idea2strategy.backend.api.caseoperations;

import com.idea2strategy.backend.application.caseoperations.CaseSanctionCommandPort;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommandService;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueryService;
import com.idea2strategy.backend.application.caseoperations.OperatorEvidenceRedactor;
import com.idea2strategy.backend.persistence.caseoperations.OperatorCaseJooqAdapter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class OperatorCaseConfiguration {
    @Bean
    @ConditionalOnMissingBean(CaseSanctionCommandPort.class)
    CaseSanctionCommandPort failClosedCaseSanctionCommandPort() {
        return request -> new CaseSanctionCommandPort.Result(
                CaseSanctionCommandPort.Result.Status.UNKNOWN,
                "SANCTION_PROVIDER_NOT_INTEGRATED",
                null);
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
}
