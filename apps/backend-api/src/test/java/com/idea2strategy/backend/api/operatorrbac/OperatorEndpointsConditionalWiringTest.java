package com.idea2strategy.backend.api.operatorrbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.idea2strategy.backend.api.caseoperations.OperatorCaseController;
import com.idea2strategy.backend.api.caseoperations.OperatorCaseConfiguration;
import com.idea2strategy.backend.api.sanction.AccountSanctionController;
import com.idea2strategy.backend.application.accountsanction.AccountSanctionCommandService;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseApiGuardCatalog;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseCommandService;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueryService;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacApiGuardCatalog;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacCommandService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OperatorEndpointsConditionalWiringTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    OperatorCaseController.class,
                    OperatorRbacController.class,
                    AccountSanctionController.class)
            .withBean(OperatorCaseCommandService.class, () -> mock(OperatorCaseCommandService.class))
            .withBean(OperatorCaseQueryService.class, () -> mock(OperatorCaseQueryService.class))
            .withBean(OperatorCaseApiGuardCatalog.class, () -> mock(OperatorCaseApiGuardCatalog.class))
            .withBean(OperatorRbacCommandService.class, () -> mock(OperatorRbacCommandService.class))
            .withBean(OperatorRbacApiGuardCatalog.class, () -> mock(OperatorRbacApiGuardCatalog.class))
            .withBean(AccountSanctionCommandService.class, () -> mock(AccountSanctionCommandService.class))
            .withPropertyValues(
                    "idea2strategy.operator-case.guard.queue-permission-id=00000000-0000-0000-0000-000000000001",
                    "idea2strategy.operator-case.guard.detail-permission-id=00000000-0000-0000-0000-000000000002",
                    "idea2strategy.operator-rbac.guard.catalog-version=v1",
                    "idea2strategy.operator-rbac.guard.grant-permission-id=00000000-0000-0000-0000-000000000003",
                    "idea2strategy.operator-rbac.guard.revoke-permission-id=00000000-0000-0000-0000-000000000004",
                    "idea2strategy.operator-sanction.guard.apply-permission-id=00000000-0000-0000-0000-000000000005",
                    "idea2strategy.operator-sanction.guard.lift-permission-id=00000000-0000-0000-0000-000000000006");

    @Test
    void operatorEndpointsStayDisabledWhenNoTrustedOperatorContextExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OperatorCaseController.class);
            assertThat(context).doesNotHaveBean(OperatorRbacController.class);
            assertThat(context).doesNotHaveBean(AccountSanctionController.class);
        });
    }

    @Test
    void emptyOperatorPermissionInputsDoNotBreakPublicApiStartupWhenOperatorAuthIsDisabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(OperatorCaseConfiguration.class)
                .withPropertyValues(
                        "idea2strategy.operator-auth.enabled=false",
                        "idea2strategy.operator-case.guard.queue-permission-id=",
                        "idea2strategy.operator-case.guard.detail-permission-id=",
                        "idea2strategy.operator-case.guard.assign-permission-id=",
                        "idea2strategy.operator-case.guard.reassign-permission-id=",
                        "idea2strategy.operator-case.guard.unassign-permission-id=",
                        "idea2strategy.operator-case.guard.start-review-permission-id=",
                        "idea2strategy.operator-case.guard.request-information-permission-id=",
                        "idea2strategy.operator-case.guard.resolve-permission-id=",
                        "idea2strategy.operator-case.guard.reject-permission-id=",
                        "idea2strategy.operator-case.guard.apply-sanction-permission-id=",
                        "idea2strategy.operator-case.guard.release-sanction-permission-id=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OperatorCaseApiGuardCatalog.class);
                });
    }
}
