package com.idea2strategy.backend.application.operatorrbac;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every operator authorization refusal the persistence adapters can produce must be classified.
 *
 * <p>A hand-written list of codes rots. This derives the codes from the adapters that actually
 * return them, so adding a new refusal without deciding its HTTP status fails here rather than
 * shipping as a {@code 409} or {@code 422} that a client reads as retryable.
 */
class OperatorAuthorizationDenialsTest {

    /**
     * Where refusals originate. Both adapters build the port decisions the API layer later maps to a
     * status, so these two files are the full producer set for operator authorization codes.
     */
    private static final List<String> ADAPTERS = List.of(
            "modules/backend-persistence/src/main/java/com/idea2strategy/backend/persistence/"
                    + "caseoperations/OperatorCaseJooqAdapter.java",
            "modules/backend-persistence/src/main/java/com/idea2strategy/backend/persistence/"
                    + "competition/OperatorRoomJooqAdapter.java");

    /**
     * Refusal codes look like {@code rejected("CODE"} or a {@code Decision(false, "CODE"} literal, and
     * the sanction path builds its code through a conditional chain of bare string literals. Rather
     * than model each shape, take every screaming-snake literal in those files and keep the ones that
     * name a refusal — a granted code is not a refusal and neither is a table or column name.
     */
    private static final Pattern LITERAL = Pattern.compile("\"([A-Z][A-Z0-9_]{4,})\"");

    private static final Set<String> NOT_AUTHORIZATION_OUTCOMES = Set.of(
            // Grant, not refusal.
            "SANCTION_PERMISSION_GRANTED",
            "PERMISSION_GRANTED",
            // Refusals, but not on authorization grounds — these are genuinely 404/409/422.
            "CASE_NOT_AVAILABLE",
            "CASE_CONCURRENT_MUTATION",
            "CASE_STORE_SCOPE_VIOLATION",
            "CASE_TIMESTAMP_INVALID",
            "STALE_CASE_VERSION",
            "EVIDENCE_NOT_AVAILABLE");

    @Test
    @DisplayName("every refusal an adapter returns is classified as an authorization denial")
    void classifiesEveryRefusalTheAdaptersProduce() {
        Set<String> candidates = new LinkedHashSet<>();
        for (String adapter : ADAPTERS) {
            String source = read(repositoryRoot().resolve(adapter));
            Matcher matcher = LITERAL.matcher(source);
            while (matcher.find()) {
                candidates.add(matcher.group(1));
            }
        }
        // A vacuous pass here would be worse than a failure: it would report full coverage of nothing.
        assertTrue(candidates.size() > 5,
                "parsed the operator adapters and found almost no code literals — the pattern is "
                        + "broken, not the adapters. Found: " + candidates);

        Set<String> refusals = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (NOT_AUTHORIZATION_OUTCOMES.contains(candidate)) {
                continue;
            }
            if (candidate.endsWith("_DENIED")
                    || candidate.equals("OPERATOR_MFA_REQUIRED")
                    || candidate.equals("OPERATOR_NOT_ACTIVE")
                    || candidate.equals("RBAC_CATALOG_NOT_ACTIVE")) {
                refusals.add(candidate);
            }
        }
        assertTrue(refusals.size() >= 6,
                "expected at least the six known operator refusals, found " + refusals);

        for (String refusal : refusals) {
            assertTrue(OperatorAuthorizationDenials.isAuthorizationDenial(refusal),
                    refusal + " is returned by an operator authorization adapter but is not "
                            + "classified as an authorization denial, so it will not be answered 403. "
                            + "Add it to OperatorAuthorizationDenials, or if it genuinely is not an "
                            + "authorization outcome, list it in NOT_AUTHORIZATION_OUTCOMES here with "
                            + "the status it should carry instead.");
        }
    }

    @Test
    @DisplayName("the classification does not claim codes that are not refusals")
    void doesNotClassifyGrantsOrUnrelatedOutcomes() {
        for (String code : NOT_AUTHORIZATION_OUTCOMES) {
            assertFalse(OperatorAuthorizationDenials.isAuthorizationDenial(code),
                    code + " is not an authorization refusal and must not be answered 403");
        }
        assertFalse(OperatorAuthorizationDenials.isAuthorizationDenial(null));
        assertFalse(OperatorAuthorizationDenials.isAuthorizationDenial(""));
        // Substring matching is what this replaces; a code that merely mentions permission must not
        // be swept in by accident.
        assertFalse(OperatorAuthorizationDenials.isAuthorizationDenial("SOME_PERMISSION_THING"));
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("settings.gradle.kts"))
                && !Files.exists(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("could not locate the backend repository root");
        }
        return candidate;
    }

    private static String read(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException("operator authorization adapter is missing: " + path
                    + " — if it moved, update ADAPTERS in this test rather than deleting the check");
        }
        try (Stream<String> lines = Files.lines(path)) {
            return String.join("\n", lines.toList());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
