package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.testing.FakeBacktestRoomAdapter;
import com.idea2strategy.backend.application.testing.FakeBotReferenceAdapter;
import com.idea2strategy.backend.application.testing.FakeTradingRoomAdapter;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompetitionDependencyServiceTest {
    private static final UUID ROOM_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Test
    void fakeAdaptersMakeCompetitionIndependentFromOtherDomains() {
        var bots = new FakeBotReferenceAdapter();
        bots.add(BOT_ID);
        var trading = new FakeTradingRoomAdapter(true);
        var backtest = new FakeBacktestRoomAdapter(false);
        var service = new CompetitionDependencyService(bots, trading, backtest);

        var readiness = service.inspect(ROOM_ID, BOT_ID);

        assertThat(readiness.botAvailable()).isTrue();
        assertThat(readiness.tradingAvailable()).isTrue();
        assertThat(readiness.backtestAvailable()).isFalse();
        assertThat(trading.inspectedRooms()).containsExactly(ROOM_ID);
        assertThat(backtest.inspectedRooms()).containsExactly(ROOM_ID);
    }
}
