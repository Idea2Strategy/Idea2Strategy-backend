package com.idea2strategy.backend.application.operatorrbac;

import java.util.Set;

/**
 * The rejection codes that mean an operator was refused on authorization grounds.
 *
 * <p>Every one of these must reach the client as {@code 403}. The distinction matters to a caller:
 * a permission failure is not retryable and not a conflict, so returning {@code 409} or {@code 422}
 * tells an operator UI to offer a retry for something that will never succeed, and hides a real
 * authorization problem inside a status that reads like ordinary contention.
 *
 * <p>This exists because each operator area was deriving the status from the shape of the code
 * string, and the three areas disagreed:
 *
 * <ul>
 *   <li>case commands matched {@code contains("PERMISSION")} or {@code contains("MFA")}, so
 *       {@code RBAC_CATALOG_NOT_ACTIVE} fell through to {@code 422};
 *   <li>account sanctions mapped everything that did not end in {@code NOT_FOUND} to {@code 409},
 *       which swallowed all four of its denial codes;
 *   <li>room management and RBAC reads were already correct.
 * </ul>
 *
 * <p>Substring matching cannot be made safe here — it silently classifies whatever code is added
 * next. An explicit set can be wrong, but it is wrong loudly:
 * {@code OperatorAuthorizationDenialsTest} derives the codes the persistence adapters actually
 * return and fails when one of them is not listed here.
 */
public final class OperatorAuthorizationDenials {

    /**
     * Authorization was evaluated and refused, or could not be established at all.
     *
     * <p>{@code RBAC_CATALOG_NOT_ACTIVE} belongs here on purpose. No permission can be resolved
     * without an active catalog, so the request is refused — fail closed. The refusal is an
     * authorization outcome even though its cause is server state, and answering {@code 422} would
     * describe the request as malformed when nothing about it was.
     */
    private static final Set<String> CODES = Set.of(
            "CASE_PERMISSION_DENIED",
            "SANCTION_PERMISSION_DENIED",
            "PERMISSION_DENIED",
            "OPERATOR_MFA_REQUIRED",
            "OPERATOR_NOT_ACTIVE",
            "RBAC_CATALOG_NOT_ACTIVE");

    private OperatorAuthorizationDenials() {}

    /** Whether {@code code} is an authorization refusal and must therefore be answered 403. */
    public static boolean isAuthorizationDenial(String code) {
        return code != null && CODES.contains(code);
    }

    /** The classified codes, for tests and for callers that need to enumerate them. */
    public static Set<String> codes() {
        return CODES;
    }
}
