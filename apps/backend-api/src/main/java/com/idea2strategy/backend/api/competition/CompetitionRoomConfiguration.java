package com.idea2strategy.backend.api.competition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.OfficialCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.OperatorRoomManagementService;
import com.idea2strategy.backend.application.competition.OwnedBotComparisonQueryService;
import com.idea2strategy.backend.application.competition.PlatformRoomInvalidationService;
import com.idea2strategy.backend.application.competition.PublicRoomDiscoveryService;
import com.idea2strategy.backend.application.competition.RoomInvitationSecretIssuer;
import com.idea2strategy.backend.application.competition.RoomInvitationService;
import com.idea2strategy.backend.application.competition.RoomInputCatalogQueryService;
import com.idea2strategy.backend.application.competition.RoomLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionService;
import com.idea2strategy.backend.application.competition.RoomStrategyParticipationService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.UserCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.UserPostEvaluationChoiceService;
import com.idea2strategy.backend.application.competition.UserRoomConfigurationService;
import com.idea2strategy.backend.application.competition.UserRoomTerminationService;
import com.idea2strategy.backend.application.strategy.BasicExecutionPlanCommandService;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandService;
import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogQueryService;
import com.idea2strategy.backend.persistence.botcontrol.BotRunCommandJooqAdapter;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import com.idea2strategy.backend.persistence.competition.AnonymousLeaderboardJooqAdapter;
import com.idea2strategy.backend.persistence.competition.CompetitionRoomJpaCommandAdapter;
import com.idea2strategy.backend.persistence.competition.OperatorRoomJooqAdapter;
import com.idea2strategy.backend.persistence.competition.PostEvaluationChoiceJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomConfigurationJooqAdapter;
import com.idea2strategy.backend.persistence.competition.PublicRoomSearchJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomInvitationJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomExecutionPolicyCatalogJooqQueryAdapter;
import com.idea2strategy.backend.persistence.competition.RoomLeaderboardJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomParticipationAdmissionJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomStrategyBotProvisioningJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomTerminationJooqAdapter;
import com.idea2strategy.backend.persistence.competition.ScoringTemplateCatalogJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.CompiledFlowPlanJooqCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.ImmutableStrategyReleaseJooqCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyValidationRunJooqQueryAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(type = "org.jooq.DSLContext")
@Import({
    CompetitionRoomJpaCommandAdapter.class,
    OperatorRoomJooqAdapter.class,
    AnonymousLeaderboardJooqAdapter.class,
    RoomConfigurationJooqAdapter.class,
    RoomExecutionPolicyCatalogJooqQueryAdapter.class,
    ScoringTemplateCatalogJooqQueryAdapter.class,
    PublicRoomSearchJooqAdapter.class,
    RoomInvitationJooqAdapter.class,
    RoomLeaderboardJooqAdapter.class,
    RoomParticipationAdmissionJooqAdapter.class,
    RoomStrategyBotProvisioningJooqAdapter.class,
    PostEvaluationChoiceJooqAdapter.class,
    RoomTerminationJooqAdapter.class,
    BotRunCommandJooqAdapter.class,
    BotStopCommandJooqAdapter.class,
    CompiledFlowPlanJooqCommandAdapter.class,
    ImmutableStrategyReleaseJooqCommandAdapter.class,
    StrategyDocumentJooqQueryAdapter.class,
    StrategyJooqQueryAdapter.class,
    StrategyValidationRunJooqQueryAdapter.class
})
public class CompetitionRoomConfiguration {
    @Bean
    AnonymousLeaderboardQueryService anonymousLeaderboardQueryService(
            AnonymousLeaderboardJooqAdapter adapter, ObjectProvider<CurrentPrincipal> principalProvider) {
        return new AnonymousLeaderboardQueryService(adapter, () -> {
            CurrentPrincipal principal = principalProvider.getIfAvailable();
            return principal == null ? null : principal.accountId();
        });
    }

    @Bean
    OwnedBotComparisonQueryService ownedBotComparisonQueryService(
            AnonymousLeaderboardJooqAdapter adapter, ObjectProvider<CurrentPrincipal> principalProvider) {
        return new OwnedBotComparisonQueryService(adapter, () -> {
            CurrentPrincipal principal = principalProvider.getIfAvailable();
            return principal == null ? null : principal.accountId();
        });
    }

    @Bean
    RoomLeaderboardQueryService roomLeaderboardQueryService(
            RoomLeaderboardJooqAdapter adapter, ObjectProvider<CurrentPrincipal> principalProvider) {
        return new RoomLeaderboardQueryService(adapter, () -> {
            CurrentPrincipal principal = principalProvider.getIfAvailable();
            return principal == null ? null : principal.accountId();
        });
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    UserPostEvaluationChoiceService userPostEvaluationChoiceService(
            PostEvaluationChoiceJooqAdapter adapter, CurrentPrincipal principal) {
        return new UserPostEvaluationChoiceService(adapter, principal, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    UserRoomTerminationService userRoomTerminationService(
            RoomTerminationJooqAdapter adapter, CurrentPrincipal principal) {
        return new UserRoomTerminationService(adapter, principal, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(CurrentOperatorPrincipal.class)
    PlatformRoomInvalidationService platformRoomInvalidationService(
            RoomTerminationJooqAdapter adapter,
            OperatorRoomJooqAdapter operatorRoomAdapter,
            CurrentOperatorPrincipal principal) {
        return new PlatformRoomInvalidationService(
                adapter, operatorRoomAdapter, principal, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(CurrentOperatorPrincipal.class)
    OperatorRoomManagementService operatorRoomManagementService(
            OperatorRoomJooqAdapter operatorRoomAdapter,
            RoomTerminationJooqAdapter terminationAdapter,
            CurrentOperatorPrincipal principal) {
        return new OperatorRoomManagementService(
                operatorRoomAdapter,
                operatorRoomAdapter,
                terminationAdapter,
                principal,
                Clock.systemUTC());
    }

    @Bean
    PublicRoomDiscoveryService publicRoomDiscoveryService(PublicRoomSearchJooqAdapter searchAdapter) {
        return new PublicRoomDiscoveryService(searchAdapter);
    }

    @Bean
    RoomInvitationSecretIssuer roomInvitationSecretIssuer() {
        return new SecureRoomInvitationSecretIssuer();
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    RoomInvitationService roomInvitationService(
            RoomInvitationJooqAdapter invitationAdapter,
            RoomInvitationSecretIssuer secretIssuer,
            CurrentPrincipal principal) {
        return new RoomInvitationService(
                invitationAdapter, principal, secretIssuer, Clock.systemUTC(), UUID::randomUUID);
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    RoomParticipationAdmissionService roomParticipationAdmissionService(
            RoomParticipationAdmissionJooqAdapter admissionAdapter, CurrentPrincipal principal) {
        return new RoomParticipationAdmissionService(
                admissionAdapter,
                principal,
                Clock.systemUTC(),
                UUID::randomUUID,
                UUID::randomUUID);
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    BasicExecutionPlanCommandService roomBasicExecutionPlanCommandService(
            CompiledFlowPlanJooqCommandAdapter planAdapter,
            StrategyValidationRunJooqQueryAdapter validationAdapter,
            StrategyJooqQueryAdapter strategyAdapter,
            StrategyDocumentJooqQueryAdapter documentAdapter,
            CurrentPrincipal principal) {
        return new BasicExecutionPlanCommandService(
                planAdapter,
                validationAdapter,
                strategyAdapter,
                documentAdapter,
                principal,
                UUID::randomUUID,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    ImmutableStrategyReleaseCommandService roomImmutableStrategyReleaseCommandService(
            ImmutableStrategyReleaseJooqCommandAdapter releaseAdapter,
            BasicExecutionPlanCommandService planService,
            StrategyValidationRunJooqQueryAdapter validationAdapter,
            StrategyJooqQueryAdapter strategyAdapter,
            StrategyDocumentJooqQueryAdapter documentAdapter,
            CurrentPrincipal principal) {
        return new ImmutableStrategyReleaseCommandService(
                releaseAdapter,
                planService,
                validationAdapter,
                strategyAdapter,
                documentAdapter,
                principal,
                Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    RoomStrategyParticipationService roomStrategyParticipationService(
            RoomParticipationAdmissionService admissionService,
            RoomStrategyBotProvisioningJooqAdapter provisioningAdapter,
            ImmutableStrategyReleaseCommandService releaseService,
            BasicStrategyCatalogQueryService catalogService,
            StrategyValidationRunJooqQueryAdapter validationAdapter,
            CurrentPrincipal principal) {
        return new RoomStrategyParticipationService(
                admissionService,
                provisioningAdapter,
                releaseService,
                catalogService,
                validationAdapter,
                principal,
                UUID::randomUUID);
    }

    @Bean
    ScoringTemplateCatalogService scoringTemplateCatalogService(
            ScoringTemplateCatalogJooqQueryAdapter queryAdapter) {
        return new ScoringTemplateCatalogService(queryAdapter, Clock.systemUTC(), new ObjectMapper());
    }

    @Bean
    RoomInputCatalogQueryService roomInputCatalogQueryService(
            ScoringTemplateCatalogService scoringCatalog,
            RoomExecutionPolicyCatalogJooqQueryAdapter executionPolicies) {
        return new RoomInputCatalogQueryService(scoringCatalog, executionPolicies, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    OwnedStrategyValidationCatalogQueryService ownedStrategyValidationCatalogQueryService(
            StrategyValidationRunJooqQueryAdapter validationAdapter, CurrentPrincipal principal) {
        return new OwnedStrategyValidationCatalogQueryService(validationAdapter, principal);
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    UserCompetitionRoomCreationService userCompetitionRoomCreationService(
            CompetitionRoomJpaCommandAdapter commandAdapter,
            ScoringTemplateCatalogService scoringCatalog,
            CurrentPrincipal principal) {
        return new UserCompetitionRoomCreationService(
                commandAdapter,
                scoringCatalog,
                principal,
                Clock.systemUTC(),
                UUID::randomUUID,
                new ObjectMapper());
    }

    @Bean
    @ConditionalOnBean(CurrentPrincipal.class)
    UserRoomConfigurationService userRoomConfigurationService(
            RoomConfigurationJooqAdapter configurationAdapter,
            ScoringTemplateCatalogService scoringCatalog,
            CurrentPrincipal principal) {
        return new UserRoomConfigurationService(
                configurationAdapter,
                scoringCatalog,
                principal,
                Clock.systemUTC(),
                new ObjectMapper());
    }

    @Bean
    @ConditionalOnBean(CurrentOperatorPrincipal.class)
    OfficialCompetitionRoomCreationService officialCompetitionRoomCreationService(
            CompetitionRoomJpaCommandAdapter commandAdapter,
            ScoringTemplateCatalogService scoringCatalog,
            CurrentOperatorPrincipal principal) {
        return new OfficialCompetitionRoomCreationService(
                commandAdapter,
                scoringCatalog,
                principal,
                Clock.systemUTC(),
                UUID::randomUUID,
                new ObjectMapper());
    }
}
