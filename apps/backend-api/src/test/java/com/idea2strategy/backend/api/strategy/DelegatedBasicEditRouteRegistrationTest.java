package com.idea2strategy.backend.api.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.DelegatedBasicStrategyEditService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Pins the routes the external tool CLI calls.
 *
 * <p>This test exists because these endpoints were absent from the API for the whole life of the
 * CLI and nobody noticed: the CLI's own tests drive a stub HTTP server, so they proved the client
 * sent a correct request to a path that did not exist. A stub can only agree with itself. The path
 * strings below are duplicated from {@code Idea2StrategyCli} on purpose — a rename on either side
 * must break a test rather than a released tool.
 */
class DelegatedBasicEditRouteRegistrationTest {
    private static final String BASE = "/api/v1/strategies/{strategyId}/basic-edits";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DelegatedEditDependencies.class, DelegatedBasicEditController.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:postgresql://unused/test",
                    "identity.crypto.customer-jwt-signing-key=test-customer-jwt-signing-key");

    @Test
    void registersTheDelegatedEditControllerWhenTheStrategyDraftModuleIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DelegatedBasicEditController.class);
        });
    }

    @Test
    void keepsTheControllerDisabledWhenTheStrategyDraftModuleIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(DelegatedEditDependencies.class, DelegatedBasicEditController.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(DelegatedBasicEditController.class);
                });
    }

    /**
     * The tool contract names {@code data.diff} as the field an external AI must inspect before
     * applying. If that field ever becomes the resulting document instead of the change list, the
     * review gate still passes mechanically while nothing reviewable was reviewed.
     */
    @Test
    void previewReportsTheChangeListAsTheReviewableDiff() throws Exception {
        var diff = DelegatedBasicEditController.PreviewResponse.class.getRecordComponents()[2];

        assertThat(diff.getName()).isEqualTo("diff");
        assertThat(diff.getType()).isEqualTo(List.class);
    }

    @Test
    void refusalAdviceStaysScopedToTheDelegatedRoute() {
        var advice = DelegatedBasicEditExceptionHandler.class
                .getAnnotation(org.springframework.web.bind.annotation.RestControllerAdvice.class);

        assertThat(advice.assignableTypes()).containsExactly(DelegatedBasicEditController.class);
    }

    @Test
    void exposesExactlyThePathsTheExternalToolCliCalls() {
        RequestMapping base = DelegatedBasicEditController.class.getAnnotation(RequestMapping.class);
        assertThat(base).isNotNull();
        assertThat(base.value()).containsExactly(BASE);

        assertThat(postMappings()).containsExactlyInAnyOrder("/preview", "/apply");
    }

    private static List<String> postMappings() {
        return Arrays.stream(DelegatedBasicEditController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(PostMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    static class DelegatedEditDependencies {
        @Bean
        DelegatedBasicStrategyEditService delegatedBasicStrategyEditService() {
            return mock(DelegatedBasicStrategyEditService.class);
        }

        @Bean
        BasicStrategyCatalogQueryService basicStrategyCatalogQueryService() {
            return mock(BasicStrategyCatalogQueryService.class);
        }

        @Bean
        StrategyDocumentQueryService strategyDocumentQueryService() {
            return mock(StrategyDocumentQueryService.class);
        }

        @Bean
        CurrentPrincipal currentPrincipal() {
            return mock(CurrentPrincipal.class);
        }
    }
}
