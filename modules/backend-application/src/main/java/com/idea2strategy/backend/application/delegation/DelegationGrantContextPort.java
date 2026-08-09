package com.idea2strategy.backend.application.delegation;

import java.util.UUID;

/**
 * The two facts a grant needs that the caller must not supply.
 *
 * <p>Both are read rather than accepted from the request on purpose. The auth epoch is what makes a
 * delegation die when the account re-authenticates, so a client that could name it could outlive a
 * password change. The disclosure document is the text the customer was actually shown; letting a
 * request choose it would let a delegation claim consent to something else.
 */
public interface DelegationGrantContextPort {
    long currentAuthEpoch(UUID accountId);

    UUID currentDisclosurePolicyDocumentId(String policyCode);
}
