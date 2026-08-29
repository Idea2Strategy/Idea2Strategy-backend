package com.idea2strategy.backend.persistence.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBenchmarkCatalogPort;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class MarketBenchmarkCatalogJooqAdapter implements MarketBenchmarkCatalogPort {
    private final DSLContext dsl;

    public MarketBenchmarkCatalogJooqAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<SupportedInstrument> findPublishedBenchmarks() {
        return dsl.fetch(
                        """
                        select i.id,
                               i.asset_type,
                               i.primary_exchange_mic,
                               i.currency_code,
                               s.symbol
                          from market_data.instruments i
                          join market_data.instrument_symbols s on s.instrument_id = i.id
                         where i.asset_type = 'INDEX'
                           and i.currency_code = 'USD'
                           and s.symbol in ('NDX', 'SPX')
                           and s.effective_from <= current_timestamp
                           and (s.effective_to is null or s.effective_to > current_timestamp)
                         order by case s.symbol when 'SPX' then 1 when 'NDX' then 2 else 3 end
                        """)
                .map(record -> new SupportedInstrument(
                        record.get("id", java.util.UUID.class),
                        record.get("asset_type", String.class),
                        record.get("primary_exchange_mic", String.class).trim(),
                        record.get("currency_code", String.class).trim(),
                        record.get("symbol", String.class)));
    }
}
