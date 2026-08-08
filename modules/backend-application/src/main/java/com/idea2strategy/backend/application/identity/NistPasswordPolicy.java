package com.idea2strategy.backend.application.identity;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class NistPasswordPolicy implements PasswordPolicy {
    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 30;
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
            throw new PasswordPolicyException("Password must contain at least 10 characters");
        }
        if (length > MAX_LENGTH) {
            throw new PasswordPolicyException("Password must contain at most 30 characters");
        }
        boolean containsEnglishLetter = false;
        boolean containsSpecialCharacter = false;
        for (int index = 0; index < rawPassword.length(); index++) {
            char character = rawPassword.charAt(index);
            if (character < 0x21 || character > 0x7e) {
                throw new PasswordPolicyException(
                        "Password must contain only ASCII English letters, numbers, and special characters without spaces");
            }
            if ((character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z')) {
                containsEnglishLetter = true;
            } else if (character < '0' || character > '9') {
                containsSpecialCharacter = true;
            }
        }
        if (!containsEnglishLetter) {
            throw new PasswordPolicyException("Password must contain at least one English letter");
        }
        if (!containsSpecialCharacter) {
            throw new PasswordPolicyException("Password must contain at least one special character");
        }
        if (blocked.contains(rawPassword.toLowerCase(Locale.ROOT))) {
            throw new PasswordPolicyException("Password is present in the blocked password list");
        }
    }
}
