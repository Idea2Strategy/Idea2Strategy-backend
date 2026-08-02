package com.idea2strategy.backend.messaging.backtest.v1;

import java.time.Instant;
import java.util.UUID;

public final class BacktestResultContractFixtures {
    public static final String PROVIDER_COMMIT = "95d0d44d89b7d633b84b6d38b7236a78e1b547b1";
    public static final String COMPLETED_RESOURCE =
            "/contracts/backtest/v1/backtest-result.completed.valid.json";

    private BacktestResultContractFixtures() {}

    public static BacktestResultContractFixture completed() {
        return new BacktestResultContractFixture(
                new BacktestResultContractFixture.Metadata(
                        "backtest.v1",
                        "BACKTEST_COMPLETED",
                        id("90000000-0000-4000-8000-000000000001"),
                        Instant.parse("2024-01-03T01:05:01Z"),
                        id("55555555-5555-4555-8555-555555555555"),
                        "sha256:b9ef3969253ee8495d2e8241d891a75927e62c1d9814530f83ed07540b2e2f78"),
                id("77777777-7777-4777-8777-777777777777"),
                id("00000000-0000-4000-8000-000000000201"),
                id("66666666-6666-4666-8666-666666666666"),
                "sha256:" + "1".repeat(64),
                "sha256:" + "e".repeat(64),
                "official-backtest-policy-v1",
                "precision:1.0.0",
                "COMPLETED",
                Instant.parse("2024-01-03T01:10:00Z"),
                1,
                id("99999999-9999-4999-8999-999999999999"),
                "sha256:" + "a".repeat(64),
                "BACKTEST",
                "BACKTEST_RESULT",
                false);
    }

    private static UUID id(String value) {
        return UUID.fromString(value);
    }
}
