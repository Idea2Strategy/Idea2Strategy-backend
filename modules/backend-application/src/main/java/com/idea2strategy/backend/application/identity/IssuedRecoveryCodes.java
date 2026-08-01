package com.idea2strategy.backend.application.identity;

import java.util.List;

public record IssuedRecoveryCodes(List<String> recoveryCodes) {
    public IssuedRecoveryCodes {
        recoveryCodes = List.copyOf(recoveryCodes);
    }

    @Override
    public String toString() {
        return "IssuedRecoveryCodes[codes=REDACTED, count=" + recoveryCodes.size() + "]";
    }
}
