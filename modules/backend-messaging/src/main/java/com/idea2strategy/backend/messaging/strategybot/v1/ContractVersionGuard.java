package com.idea2strategy.backend.messaging.strategybot.v1;

public final class ContractVersionGuard {

    private ContractVersionGuard() {}

    public static void requireSupported(String actualVersion) {
        if (!StrategyBotContractFixtures.CONTRACT_VERSION.equals(actualVersion)) {
            throw new UnsupportedContractVersionException(
                    StrategyBotContractFixtures.CONTRACT_VERSION,
                    actualVersion);
        }
    }

    public static final class UnsupportedContractVersionException extends IllegalArgumentException {
        private final String expectedVersion;
        private final String actualVersion;

        UnsupportedContractVersionException(String expectedVersion, String actualVersion) {
            super("Unsupported strategy-bot contract version: expected "
                    + expectedVersion
                    + " but received "
                    + actualVersion);
            this.expectedVersion = expectedVersion;
            this.actualVersion = actualVersion;
        }

        public String expectedVersion() {
            return expectedVersion;
        }

        public String actualVersion() {
            return actualVersion;
        }
    }
}
