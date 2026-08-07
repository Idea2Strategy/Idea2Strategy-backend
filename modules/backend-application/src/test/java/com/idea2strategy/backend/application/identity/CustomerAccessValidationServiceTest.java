package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerAccessValidationServiceTest {
    private static final UUID ACCOUNT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID LOGIN = UUID.fromString("20000000-0000-4000-8000-000000000001");

    @Test
    void acceptsMatchingJwtSecurityClaimsWithoutLookingUpALoginSession() {
        var service = service(state(false));

        assertThat(service.authenticate(ACCOUNT, LOGIN, 4, 7L, CustomerAccessScope.STANDARD))
                .isEqualTo(new AuthenticatedCustomer(ACCOUNT, false));
    }

    @Test
    void rejectsTokensIssuedBeforeSecurityEpochOrCredentialChanges() {
        var service = service(state(false));

        assertThatThrownBy(() -> service.authenticate(ACCOUNT, LOGIN, 3, 7L, CustomerAccessScope.STANDARD))
                .isInstanceOf(AuthenticationRejectedException.class);
        assertThatThrownBy(() -> service.authenticate(ACCOUNT, LOGIN, 4, 6L, CustomerAccessScope.STANDARD))
                .isInstanceOf(AuthenticationRejectedException.class);
    }

    @Test
    void sanctionsStillPermitOnlyExplicitAppealScope() {
        var service = service(state(true));

        assertThatThrownBy(() -> service.authenticate(ACCOUNT, LOGIN, 4, 7L, CustomerAccessScope.STANDARD))
                .isInstanceOf(SanctionedAccountAccessException.class);
        assertThat(service.authenticate(ACCOUNT, LOGIN, 4, 7L, CustomerAccessScope.APPEAL).activeSanction())
                .isTrue();
    }

    private static CustomerAccessValidationService service(CustomerAccessState state) {
        return new CustomerAccessValidationService((accountId, loginIdentityId) -> Optional.of(state));
    }

    private static CustomerAccessState state(boolean sanction) {
        return new CustomerAccessState(
                ACCOUNT, LOGIN, 4, 7L,
                AccountLifecycleStatus.ACTIVE, LoginIdentityStatus.ACTIVE, sanction);
    }
}
