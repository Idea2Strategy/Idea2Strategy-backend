package com.idea2strategy.backend.persistence.operatorbootstrap;

public record OperatorBootstrapCredential(
        String passwordHash, String passwordParameters, short passwordVersion,
        byte[] totpCiphertext, byte[] totpNonce, short totpKeyVersion) {
    public OperatorBootstrapCredential {
        totpCiphertext = totpCiphertext.clone();
        totpNonce = totpNonce.clone();
        if (passwordHash == null || passwordHash.isBlank() || passwordParameters == null
                || passwordVersion <= 0 || totpNonce.length != 12 || totpKeyVersion <= 0) {
            throw new IllegalArgumentException("OPERATOR_BOOTSTRAP_CREDENTIAL_INVALID");
        }
    }
    @Override public byte[] totpCiphertext() { return totpCiphertext.clone(); }
    @Override public byte[] totpNonce() { return totpNonce.clone(); }
}
