package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.VirtualLiquidationService;
import com.idea2strategy.backend.persistence.competition.CanonicalVirtualLiquidationQuoteAdapter;
import com.idea2strategy.backend.persistence.competition.VirtualLiquidationJooqAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E24's finalization service, activated now that F93 supplies the quote.
 *
 * <p>The context and result sides were merged with E24 and waited on exactly one dependency: a
 * producer for the virtual liquidation quote. {@link CanonicalVirtualLiquidationQuoteAdapter}
 * computes it from the canonical trading record, so the service can finally be a bean instead of a
 * wiring diagram.
 */
@Configuration(proxyBeanMethods = false)
public class VirtualLiquidationConfiguration {

    @Bean
    @ConditionalOnBean({
            VirtualLiquidationJooqAdapter.class,
            CanonicalVirtualLiquidationQuoteAdapter.class
    })
    VirtualLiquidationService virtualLiquidationService(
            VirtualLiquidationJooqAdapter contextAndResult,
            CanonicalVirtualLiquidationQuoteAdapter quote) {
        return new VirtualLiquidationService(contextAndResult, quote, contextAndResult);
    }
}
