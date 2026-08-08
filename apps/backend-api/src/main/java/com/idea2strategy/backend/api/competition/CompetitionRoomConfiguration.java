package com.idea2strategy.backend.api.competition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.competition.AnonymousLeaderboardQueryService;
import com.idea2strategy.backend.application.competition.OfficialCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.OperatorRoomManagementService;
import com.idea2strategy.backend.application.competition.OwnedBotComparisonQueryService;
import com.idea2strategy.backend.application.competition.OwnedRoomManagementQueryService;
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
import com.idea2strategy.backend.persistence.competition.CompetitionLiveRoomRulesJpaEntity;
import com.idea2strategy.backend.persistence.competition.CompetitionRoomJpaEntity;
import com.idea2strategy.backend.persistence.competition.CompetitionRoomRulesJpaEntity;
import com.idea2strategy.backend.persistence.competition.CompetitionRoomScheduleJpaEntity;
import com.idea2strategy.backend.persistence.competition.OperatorRoomJooqAdapter;
import com.idea2strategy.backend.persistence.competition.OwnedRoomManagementJooqAdapter;
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
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyValidationRunJooqQueryAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
// Spring Boot 의 기본 엔티티 스캔은 @EntityScan 이 하나라도 선언되면 그것으로 대체된다. 다른
// 설정(IdentityAuthConfiguration, StrategyDraftConfiguration)이 이미 선언하고 있으므로, 방
// 엔티티를 여기서 함께 선언하지 않으면 persistence unit 에 들어가지 못한다. 그러면 검증은 전부
// 통과한 뒤 저장 시점에 "does not belong to this persistence unit" 으로 실패하고, 방을 만들 수
// 없다.
//
// @EnableJpaRepositories 는 함께 두지 않는다. 이 경로의 쓰기는
// CompetitionRoomJpaCommandAdapter 가 EntityManager 로 직접 하고, 이 패키지의 Spring Data
// 리포지터리들은 package-private 으로 캡슐화되어 있어 밖에서 참조할 대상이 아니다.
@EntityScan(basePackageClasses = {
    CompetitionRoomJpaEntity.class,
    CompetitionRoomRulesJpaEntity.class,
    CompetitionLiveRoomRulesJpaEntity.class,
    CompetitionRoomScheduleJpaEntity.class
})
@Import({
    CompetitionRoomJpaCommandAdapter.class,
    OperatorRoomJooqAdapter.class,
    OwnedRoomManagementJooqAdapter.class,
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
    FeatureMaterializationPinResolver.class,
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
    OwnedRoomManagementQueryService ownedRoomManagementQueryService(
            OwnedRoomManagementJooqAdapter adapter, CurrentPrincipal principal) {
        return new OwnedRoomManagementQueryService(adapter, principal);
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
    UserPostEvaluationChoiceService userPostEvaluationChoiceService(
            PostEvaluationChoiceJooqAdapter adapter, CurrentPrincipal principal) {
        return new UserPostEvaluationChoiceService(adapter, principal, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
    RoomInvitationService roomInvitationService(
            RoomInvitationJooqAdapter invitationAdapter,
            RoomInvitationSecretIssuer secretIssuer,
            CurrentPrincipal principal) {
        return new RoomInvitationService(
                invitationAdapter, principal, secretIssuer, Clock.systemUTC(), UUID::randomUUID);
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
    OwnedStrategyValidationCatalogQueryService ownedStrategyValidationCatalogQueryService(
            StrategyValidationRunJooqQueryAdapter validationAdapter, CurrentPrincipal principal) {
        return new OwnedStrategyValidationCatalogQueryService(validationAdapter, principal);
    }

    @Bean
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
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
    @ConditionalOnProperty(prefix = "identity.crypto", name = {"refresh-token-hmac-key", "customer-jwt-signing-key"})
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
