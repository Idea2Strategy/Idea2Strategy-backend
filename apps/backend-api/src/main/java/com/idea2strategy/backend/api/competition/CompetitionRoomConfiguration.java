package com.idea2strategy.backend.api.competition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import com.idea2strategy.backend.application.competition.OfficialCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.PublicRoomDiscoveryService;
import com.idea2strategy.backend.application.competition.RoomInvitationSecretIssuer;
import com.idea2strategy.backend.application.competition.RoomInvitationService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.UserCompetitionRoomCreationService;
import com.idea2strategy.backend.persistence.competition.CompetitionRoomJpaCommandAdapter;
import com.idea2strategy.backend.persistence.competition.PublicRoomSearchJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomInvitationJooqAdapter;
import com.idea2strategy.backend.persistence.competition.ScoringTemplateCatalogJooqQueryAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(type = "org.jooq.DSLContext")
@Import({
    CompetitionRoomJpaCommandAdapter.class,
    ScoringTemplateCatalogJooqQueryAdapter.class,
    PublicRoomSearchJooqAdapter.class,
    RoomInvitationJooqAdapter.class
})
public class CompetitionRoomConfiguration {
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
    ScoringTemplateCatalogService scoringTemplateCatalogService(
            ScoringTemplateCatalogJooqQueryAdapter queryAdapter) {
        return new ScoringTemplateCatalogService(queryAdapter, Clock.systemUTC(), new ObjectMapper());
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
