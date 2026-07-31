package com.idea2strategy.backend.common.contract.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ContractTimeAndAdapterTest {

    @Test
    void storesUtcAndInterpretsEasternTimeAcrossDaylightSavingBoundary() {
        var before = Instant.parse("2026-03-08T06:59:59Z");
        var after = Instant.parse("2026-03-08T07:00:00Z");

        var beforeEastern = ContractTime.toEastern(before);
        var afterEastern = ContractTime.toEastern(after);

        assertEquals(ZoneOffset.ofHours(-5), beforeEastern.getOffset());
        assertEquals(ZoneOffset.ofHours(-4), afterEastern.getOffset());
        assertEquals(before, ContractTime.toUtc(beforeEastern));
        assertEquals(after, ContractTime.toUtc(afterEastern));
    }

    @Test
    void fakeClockAndAuthenticationAreDeterministicAndAdvanceExplicitly() {
        var fixtures = CommonContractFixtures.standard();
        var clock = fixtures.clock();
        var authentication = fixtures.authentication();

        assertEquals(fixtures.principal(), authentication.currentPrincipal());
        assertEquals(Instant.parse("2026-07-31T12:00:00Z"), clock.instant());

        clock.advance(Duration.ofMinutes(5));

        assertEquals(Instant.parse("2026-07-31T12:05:00Z"), clock.instant());
    }
}
