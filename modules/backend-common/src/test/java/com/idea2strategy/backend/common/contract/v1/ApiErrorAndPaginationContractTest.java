package com.idea2strategy.backend.common.contract.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ApiErrorAndPaginationContractTest {

    @Test
    void errorResponseCarriesTheRequestCorrelationId() {
        var fixtures = CommonContractFixtures.standard();

        var error = ApiErrorResponse.of(
                "AUTHENTICATION_REQUIRED",
                "Authentication is required.",
                fixtures.requestContext(),
                fixtures.clock().instant(),
                List.of());

        assertEquals(CommonContractVersions.API_ERROR_V1, error.schemaVersion());
        assertEquals(fixtures.requestContext().correlationId(), error.correlationId());
    }

    @Test
    void pageContractRejectsLimitsOutsideTheSharedBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new PageRequest(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new PageRequest(null, 201));

        assertEquals(50, new PageRequest("next-page", 50).limit());
    }
}
