package com.idea2strategy.backend.application.identity;

import java.util.Optional;
import java.util.UUID;

public interface CustomerAccessStateQueryPort {
    Optional<CustomerAccessState> findCustomerAccessState(UUID accountId, UUID loginIdentityId);
}
