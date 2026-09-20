package com.idea2strategy.backend.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.idea2strategy.backend.application.competition.FinalRoomResultService;
import com.idea2strategy.backend.application.competition.RoomFinalizationService;
import com.idea2strategy.backend.application.competition.ScoringEvidenceService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.application.competition.VirtualLiquidationService;
import com.idea2strategy.backend.persistence.competition.CanonicalVirtualLiquidationQuoteAdapter;
import com.idea2strategy.backend.persistence.competition.FinalRoomResultJooqAdapter;
import com.idea2strategy.backend.persistence.competition.RoomFinalizationWorkJooqAdapter;
import com.idea2strategy.backend.persistence.competition.ScoringEvidenceJooqAdapter;
import com.idea2strategy.backend.persistence.competition.ScoringTemplateCatalogJooqQueryAdapter;
import com.idea2strategy.backend.persistence.competition.VirtualLiquidationJooqAdapter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "idea2strategy.batch.room-finalization.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({
        RoomFinalizationWorkJooqAdapter.class,
        VirtualLiquidationJooqAdapter.class,
        CanonicalVirtualLiquidationQuoteAdapter.class,
        ScoringEvidenceJooqAdapter.class,
        FinalRoomResultJooqAdapter.class,
        ScoringTemplateCatalogJooqQueryAdapter.class
})
class RoomFinalizationBatchConfiguration {
    @Bean
    RoomFinalizationService roomFinalizationService(
            RoomFinalizationWorkJooqAdapter work,
            VirtualLiquidationJooqAdapter liquidationStore,
            CanonicalVirtualLiquidationQuoteAdapter quote,
            ScoringEvidenceJooqAdapter evidence,
            FinalRoomResultJooqAdapter results,
            ScoringTemplateCatalogJooqQueryAdapter templates) {
        Clock clock = Clock.systemUTC();
        ObjectMapper mapper = JsonMapper.builder().build();
        return new RoomFinalizationService(
                work,
                new VirtualLiquidationService(liquidationStore, quote, liquidationStore),
                new ScoringEvidenceService(evidence),
                new FinalRoomResultService(results, clock),
                new ScoringTemplateCatalogService(templates, clock, mapper),
                clock,
                mapper);
    }

    @Bean
    RoomFinalizationBatchRunner roomFinalizationBatchRunner(
            RoomFinalizationService service,
            @Value("${idea2strategy.batch.room-finalization.batch-size:100}") int batchSize) {
        return new RoomFinalizationBatchRunner(service, batchSize);
    }
}
