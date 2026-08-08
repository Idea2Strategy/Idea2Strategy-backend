package com.idea2strategy.backend.api.sanction;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.api.caseoperations.OperatorCaseController;
import com.idea2strategy.backend.application.operatorrbac.OperatorAuthorizationDenials;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

/**
 * Every operator area must answer an authorization refusal with 403.
 *
 * <p>A90 asked for the permission-denied path to be verified across the operator surface rather than
 * sampled. It cannot be sampled by hand: producing a 403 from a live operator session needs a second
 * operator holding a strictly smaller role, and the development catalog has none — its root operator
 * holds every permission in the catalog. So the contract is verified here instead, over every refusal
 * code the adapters can produce, which covers more endpoints than a second operator could reach.
 *
 * <p>Two areas were wrong when this was written. Account sanctions mapped every non-{@code NOT_FOUND}
 * rejection to 409, which swallowed all four of its refusal codes. Case commands matched on substrings
 * and let {@code RBAC_CATALOG_NOT_ACTIVE} fall through to 422. Room management and RBAC reads were
 * already correct.
 */
class OperatorDenialStatusContractTest {

    @Test
    @DisplayName("account sanctions answer every authorization refusal with 403")
    void accountSanctionRefusalsAre403() {
        AccountSanctionExceptionHandler handler = new AccountSanctionExceptionHandler();
        for (String code : OperatorAuthorizationDenials.codes()) {
            ProblemDetail detail = rejected(handler, code);
            assertThat(detail.getStatus())
                    .as("sanction refusal %s must be 403, not a conflict a client would retry", code)
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("account sanctions still distinguish not-found and conflict")
    void accountSanctionKeepsItsOtherStatuses() {
        AccountSanctionExceptionHandler handler = new AccountSanctionExceptionHandler();
        assertThat(rejected(handler, "SANCTION_NOT_FOUND").getStatus()).isEqualTo(404);
        // A genuine version conflict must stay 409 — the fix must not turn every rejection into 403.
        assertThat(rejected(handler, "STALE_SANCTION_VERSION").getStatus()).isEqualTo(409);
    }

    @Test
    @DisplayName("case commands answer every authorization refusal with 403")
    void caseCommandRefusalsAre403() throws Exception {
        Method rejectedStatus = OperatorCaseController.class
                .getDeclaredMethod("rejectedStatus", String.class);
        rejectedStatus.setAccessible(true);
        for (String code : OperatorAuthorizationDenials.codes()) {
            int status = (int) rejectedStatus.invoke(null, code);
            assertThat(status)
                    .as("case refusal %s must be 403", code)
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("case commands still distinguish conflict, not-found and unprocessable")
    void caseCommandKeepsItsOtherStatuses() throws Exception {
        Method rejectedStatus = OperatorCaseController.class
                .getDeclaredMethod("rejectedStatus", String.class);
        rejectedStatus.setAccessible(true);
        assertThat((int) rejectedStatus.invoke(null, "STALE_CASE_VERSION")).isEqualTo(409);
        assertThat((int) rejectedStatus.invoke(null, "CASE_NOT_AVAILABLE")).isEqualTo(404);
        assertThat((int) rejectedStatus.invoke(null, "EVIDENCE_NOT_AVAILABLE")).isEqualTo(404);
        assertThat((int) rejectedStatus.invoke(null, "SOMETHING_ELSE")).isEqualTo(422);
    }

    private static ProblemDetail rejected(AccountSanctionExceptionHandler handler, String code) {
        return handler.rejected(new AccountSanctionRejectedException(code, UUID.randomUUID()));
    }
}
