package com.idea2strategy.backend.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.competition.BacktestCompetitionSettlementService;
import com.idea2strategy.backend.application.competition.ScoringTemplateCatalogService;
import com.idea2strategy.backend.persistence.competition.BacktestCompetitionSettlementJooqAdapter;
import com.idea2strategy.backend.persistence.competition.ScoringTemplateCatalogJooqQueryAdapter;
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
        name = "idea2strategy.batch.backtest-competition-settlement.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({ScoringTemplateCatalogJooqQueryAdapter.class, BacktestCompetitionSettlementJooqAdapter.class})
class BacktestCompetitionSettlementBatchConfiguration {
    @Bean
    ScoringTemplateCatalogService backtestCompetitionScoringCatalog(
            ScoringTemplateCatalogJooqQueryAdapter adapter) {
        return new ScoringTemplateCatalogService(
                adapter, Clock.systemUTC(), new ObjectMapper().findAndRegisterModules());
    }

    @Bean
    BacktestCompetitionSettlementService backtestCompetitionSettlementService(
            BacktestCompetitionSettlementJooqAdapter adapter) {
        return new BacktestCompetitionSettlementService(adapter, Clock.systemUTC());
    }

    @Bean
    BacktestCompetitionSettlementBatchRunner backtestCompetitionSettlementBatchRunner(
            BacktestCompetitionSettlementService service,
            @Value("${idea2strategy.batch.backtest-competition-settlement.batch-size:100}") int batchSize) {
        return new BacktestCompetitionSettlementBatchRunner(service, batchSize);
    }
}
