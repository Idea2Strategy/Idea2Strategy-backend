package com.idea2strategy.backend.messaging.strategybot.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StrategyBotContractFixturesTest {

    @Test
    void createsDeterministicChecksumsAndIdempotencyKeysFromTheSameReleaseSnapshot() {
        var first = StrategyBotContractFixtures.standard();
        var second = StrategyBotContractFixtures.standard();

        assertEquals(first.compiledPlan().planChecksum(), second.compiledPlan().planChecksum());
        assertEquals(
                first.compiledPlan().planChecksum(),
                StrategyBotContractFixtures.calculatePlanChecksum(first.compiledPlan()));
        assertEquals(first.runCommand().metadata().idempotencyKey(), second.runCommand().metadata().idempotencyKey());
        assertEquals(first.stopCommand().metadata().idempotencyKey(), second.stopCommand().metadata().idempotencyKey());
        assertEquals(
                first.officialBacktestRequest().metadata().idempotencyKey(),
                second.officialBacktestRequest().metadata().idempotencyKey());
    }

    @Test
    void scopesIdempotencyKeysToEachCommandPurpose() {
        var fixtures = StrategyBotContractFixtures.standard();

        assertNotEquals(
                fixtures.runCommand().metadata().idempotencyKey(),
                fixtures.stopCommand().metadata().idempotencyKey());
        assertNotEquals(
                fixtures.runCommand().metadata().idempotencyKey(),
                fixtures.officialBacktestRequest().metadata().idempotencyKey());
    }

    @Test
    void rejectsAnUnsupportedContractVersionWithoutSubstitution() {
        var exception = assertThrows(
                ContractVersionGuard.UnsupportedContractVersionException.class,
                () -> ContractVersionGuard.requireSupported("strategy-bot.v999"));

        assertEquals("strategy-bot.v1", exception.expectedVersion());
        assertEquals("strategy-bot.v999", exception.actualVersion());
    }
}
