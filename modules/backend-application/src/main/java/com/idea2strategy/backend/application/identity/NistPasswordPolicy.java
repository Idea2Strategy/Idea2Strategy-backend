package com.idea2strategy.backend.application.identity;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class NistPasswordPolicy implements PasswordPolicy {
    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 128;
    private final Set<String> blocked;

    public NistPasswordPolicy(Collection<String> blockedPasswords) {
        this.blocked = Objects.requireNonNull(blockedPasswords, "blockedPasswords").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void validate(String rawPassword) {
        Objects.requireNonNull(rawPassword, "rawPassword");
        int length = rawPassword.codePointCount(0, rawPassword.length());
        if (length < MIN_LENGTH) {
            throw new PasswordPolicyException("Password must contain at least 15 characters");
        }
        if (length > MAX_LENGTH) {
            throw new PasswordPolicyException("Password must contain at most 128 characters");
        }
        if (blocked.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            throw new PasswordPolicyException("Password is present in the blocked password list");
        }
    }
}
