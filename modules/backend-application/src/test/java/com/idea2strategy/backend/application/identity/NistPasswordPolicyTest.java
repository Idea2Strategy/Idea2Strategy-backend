package com.idea2strategy.backend.application.identity;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class NistPasswordPolicyTest {
    private final NistPasswordPolicy policy = new NistPasswordPolicy(List.of());

    @Test
    void acceptsTenToThirtyAsciiCharactersWithAnEnglishLetterAndSpecialCharacter() {
        assertThatCode(() -> policy.validate("aaaaaaaaa!"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("AAAAAAAAA!"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("Ascii1234!"))
                .doesNotThrowAnyException();
        assertThatCode(() -> policy.validate("a".repeat(29) + "!"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPasswordsOutsideTheTenToThirtyCharacterRange() {
        assertRejected("aaaaaaaa!", "at least 10");
        assertRejected("a".repeat(30) + "!", "at most 30");
    }

    @Test
    void rejectsPasswordsWithoutAnEnglishLetterOrSpecialCharacter() {
        assertRejected("123456789!", "English letter");
        assertRejected("abcdefghij", "special character");
    }

    @Test
    void rejectsWhitespaceKoreanEmojiAndOtherNonAsciiCharacters() {
        assertRejected("abcdefgh !", "ASCII");
        assertRejected("abcdefgh!한", "ASCII");
        assertRejected("abcdefgh!😀", "ASCII");
    }

    @Test
    void keepsTheBlockedPasswordCheckCaseInsensitive() {
        var blockedPolicy = new NistPasswordPolicy(List.of("Allowed123!"));

        assertThatThrownBy(() -> blockedPolicy.validate("allowed123!"))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining("blocked password list");
    }

    private void assertRejected(String password, String message) {
        assertThatThrownBy(() -> policy.validate(password))
                .isInstanceOf(PasswordPolicyException.class)
                .hasMessageContaining(message);
    }
}
