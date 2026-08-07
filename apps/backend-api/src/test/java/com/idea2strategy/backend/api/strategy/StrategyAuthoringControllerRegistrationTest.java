package com.idea2strategy.backend.api.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.StrategyCopyCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StrategyAuthoringControllerRegistrationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    StrategyCopyController.class,
                    StrategyDocumentController.class,
                    StrategyAuthoringDependencies.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://unused/test",
                    "identity.crypto.refresh-token-hmac-key=test-refresh-token-hmac-key");

    @Test
    void registersAuthoringControllersWhenTheStrategyDraftModuleIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(StrategyCopyController.class);
            assertThat(context).hasSingleBean(StrategyDocumentController.class);
        });
    }

    @Test
    void keepsAuthoringControllersDisabledWhenTheStrategyDraftModuleIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        StrategyCopyController.class,
                        StrategyDocumentController.class,
                        StrategyAuthoringDependencies.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(StrategyCopyController.class);
                    assertThat(context).doesNotHaveBean(StrategyDocumentController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class StrategyAuthoringDependencies {
        @Bean
        StrategyDocumentQueryService strategyDocumentQueryService() {
            return mock(StrategyDocumentQueryService.class);
        }

        @Bean
        BasicStrategyDraftCommandService basicStrategyDraftCommandService() {
            return mock(BasicStrategyDraftCommandService.class);
        }

        @Bean
        StrategyEditLeaseService strategyEditLeaseService() {
            return mock(StrategyEditLeaseService.class);
        }

        @Bean
        StrategyCopyCommandService strategyCopyCommandService() {
            return mock(StrategyCopyCommandService.class);
        }
    }
}
