package com.idea2strategy.backend.api.competition;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.common.CurrentOperatorPrincipal;
import com.idea2strategy.backend.application.competition.OfficialCompetitionRoomCreationService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.UserCompetitionRoomCreationService;
import com.idea2strategy.backend.persistence.competition.CompetitionRoomJpaCommandAdapter;
import com.idea2strategy.backend.persistence.competition.ScoringTemplateCatalogJooqQueryAdapter;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(type = "org.jooq.DSLContext")
@Import({CompetitionRoomJpaCommandAdapter.class, ScoringTemplateCatalogJooqQueryAdapter.class})
public class CompetitionRoomConfiguration {
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
