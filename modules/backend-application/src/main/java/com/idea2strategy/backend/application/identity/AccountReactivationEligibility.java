package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record AccountReactivationEligibility(boolean eligible, String rejectionCode) {
    public AccountReactivationEligibility {
        if (eligible != (rejectionCode == null)) {
            throw new IllegalArgumentException("Eligible results cannot carry a rejection code");
        }
        if (rejectionCode != null && rejectionCode.isBlank()) {
            throw new IllegalArgumentException("rejectionCode must not be blank");
        }
    }

    public static AccountReactivationEligibility allowed() {
        return new AccountReactivationEligibility(true, null);
    }

    public static AccountReactivationEligibility rejected(String code) {
        return new AccountReactivationEligibility(false, Objects.requireNonNull(code, "code"));
    }
}
