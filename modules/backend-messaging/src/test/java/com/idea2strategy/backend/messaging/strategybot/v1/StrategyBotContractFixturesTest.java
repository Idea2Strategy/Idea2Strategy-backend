package com.idea2strategy.backend.messaging.strategybot.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
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
    void pinsCompleteWarmupRequirementsWithoutNameBasedInference() {
        var feature = StrategyBotContractFixtures.standard().compiledPlan().requiredFeatures().getFirst();

        assertEquals("rsi-14-pt1m", feature.requirementId());
        assertEquals("00000000-0000-4000-8000-000000000401", feature.featureId());
        assertEquals("1.0.0", feature.featureVersion());
        assertEquals(List.of("00000000-0000-4000-8000-000000000301"), feature.instruments());
        assertEquals("PT1M", feature.resolution());
        assertEquals(14, feature.requiredObservations());
    }

    @Test
    void coversEveryWarmupRequirementFieldWithThePlanChecksum() {
        var original = StrategyBotContractFixtures.standard().compiledPlan();
        var feature = original.requiredFeatures().getFirst();

        assertNotEquals(original.planChecksum(), checksumWith(original, new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId() + "-changed", feature.featureId(), feature.featureVersion(),
                feature.instruments(), feature.resolution(), feature.requiredObservations())));
        assertNotEquals(original.planChecksum(), checksumWith(original, new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId(), "00000000-0000-4000-8000-000000000402", feature.featureVersion(),
                feature.instruments(), feature.resolution(), feature.requiredObservations())));
        assertNotEquals(original.planChecksum(), checksumWith(original, new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId(), feature.featureId(), "1.0.1",
                feature.instruments(), feature.resolution(), feature.requiredObservations())));
        assertNotEquals(original.planChecksum(), checksumWith(original, new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId(), feature.featureId(), feature.featureVersion(),
                List.of("00000000-0000-4000-8000-000000000302"), feature.resolution(),
                feature.requiredObservations())));
        assertNotEquals(original.planChecksum(), checksumWith(original, new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId(), feature.featureId(), feature.featureVersion(),
                feature.instruments(), "PT5M", feature.requiredObservations())));
        assertNotEquals(original.planChecksum(), checksumWith(original, new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId(), feature.featureId(), feature.featureVersion(),
                feature.instruments(), feature.resolution(), feature.requiredObservations() + 1)));
    }

    @Test
    void rejectsDuplicateOrAmbiguousWarmupRequirements() {
        var original = StrategyBotContractFixtures.standard().compiledPlan();
        var feature = original.requiredFeatures().getFirst();

        assertThrows(IllegalArgumentException.class, () -> copyWith(original, List.of(feature, feature)));
        assertThrows(IllegalArgumentException.class, () -> new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId(), feature.featureId(), "latest", feature.instruments(),
                feature.resolution(), feature.requiredObservations()));
        assertThrows(IllegalArgumentException.class, () -> new StrategyBotContractFixtures.RequiredFeature(
                feature.requirementId(), feature.featureId(), feature.featureVersion(), feature.instruments(),
                "1m", feature.requiredObservations()));
    }

    @Test
    void rejectsAnUnsupportedContractVersionWithoutSubstitution() {
        var exception = assertThrows(
                ContractVersionGuard.UnsupportedContractVersionException.class,
                () -> ContractVersionGuard.requireSupported("strategy-bot.v999"));

        assertEquals("strategy-bot.v1", exception.expectedVersion());
        assertEquals("strategy-bot.v999", exception.actualVersion());
    }

    private String checksumWith(
            StrategyBotContractFixtures.BasicCompiledPlan original,
            StrategyBotContractFixtures.RequiredFeature feature) {
        return StrategyBotContractFixtures.calculatePlanChecksum(copyWith(original, List.of(feature)));
    }

    private StrategyBotContractFixtures.BasicCompiledPlan copyWith(
            StrategyBotContractFixtures.BasicCompiledPlan original,
            List<StrategyBotContractFixtures.RequiredFeature> features) {
        return new StrategyBotContractFixtures.BasicCompiledPlan(
                original.contractVersion(),
                original.schemaVersion(),
                original.elementCatalogVersion(),
                original.instrumentCatalogVersion(),
                original.compilerVersion(),
                original.requiredFeatureSetHash(),
                features,
                original.executionSnapshot(),
                original.steps(),
                original.planChecksum());
    }
}
