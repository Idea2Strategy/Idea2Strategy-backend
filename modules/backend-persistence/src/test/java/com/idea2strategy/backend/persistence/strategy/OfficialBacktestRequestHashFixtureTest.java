package com.idea2strategy.backend.persistence.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.strategy.OfficialBacktestRequest;
import com.idea2strategy.backend.persistence.backtest.BacktestRunInputPinWriter.FeaturePin;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialBacktestRequestHashFixtureTest {

    @Test
    void matchesTheLanguageNeutralBasicRequestHashFixture() {
        UUID botId = UUID.fromString("20000000-0000-4000-8000-000000000001");
        var metadata = new OfficialBacktestRequest.MessageMetadata(
                OfficialBacktestRequest.CONTRACT_VERSION,
                OfficialBacktestRequest.MESSAGE_TYPE,
                UUID.fromString("10000000-0000-4000-8000-000000000001"),
                Instant.parse("2026-08-05T00:00:00Z"),
                botId,
                "sha256:" + "4".repeat(64));
        var request = new OfficialBacktestRequest(
                metadata,
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                botId,
                "sha256:" + "1".repeat(64),
                "sha256:" + "2".repeat(64),
                UUID.fromString("40000000-0000-4000-8000-000000000001"),
                "accounting-v1",
                "backtest-policy-v1",
                OfficialBacktestRequest.REQUEST_REASON);

        assertThat(ImmutableStrategyReleaseJooqCommandAdapter.basicRequestHash(
                        request,
                        "sha256:" + "3".repeat(64),
                        LocalDate.parse("2025-01-01"),
                        LocalDate.parse("2025-12-31"),
                        List.of(new FeaturePin(
                                UUID.fromString("50000000-0000-4000-8000-000000000001"),
                                "sha256:" + "5".repeat(64)))))
                .isEqualTo("sha256:bdf3474e890de34991f6caf4c6e9c0a2807f5a6728701eb03000f05797deebfb");
    }
}
