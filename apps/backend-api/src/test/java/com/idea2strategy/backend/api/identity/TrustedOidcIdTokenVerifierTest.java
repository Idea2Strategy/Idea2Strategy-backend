package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerificationException;
import com.idea2strategy.backend.application.identity.OidcIdTokenVerificationRequest;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrustedOidcIdTokenVerifierTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final URI JWKS_URI = URI.create("https://issuer.example/.well-known/jwks.json");

    private KeyPair signingKey;
    private TrustedOidcIdTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        signingKey = rsaKeyPair();
        var provider = new TrustedOidcProviderConfiguration(
                "EXAMPLE", "https://issuer.example", JWKS_URI, Set.of("idea2strategy-api"));
        verifier = new TrustedOidcIdTokenVerifier(
                JSON,
                uri -> jwks(signingKey, "key-1"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(10),
                Map.of(provider.providerCode(), provider));
    }

    @Test
    void verifiesRs256SignatureAndRequiredOidcClaims() throws Exception {
        var verified = verifier.verify(request(token(validHeader(), validClaims(), signingKey)));

        assertThat(verified.providerCode()).isEqualTo("EXAMPLE");
        assertThat(verified.issuer()).isEqualTo("https://issuer.example");
        assertThat(verified.subject()).isEqualTo("subject-1");
        assertThat(verified.email()).isEqualTo("person@example.com");
        assertThat(verified.nonce()).isEqualTo("nonce-1");
        assertThat(verified.authenticatedAt()).isEqualTo(NOW.minusSeconds(60));
        assertThat(verified.verifiedAt()).isEqualTo(NOW);
        assertThat(verified.keyId()).isEqualTo("key-1");
    }

    @Test
    void acceptsMultipleAudiencesWhenAuthorizedPartyIsTheTrustedClient() throws Exception {
        ObjectNode claims = validClaims();
        claims.putArray("aud").add("another-client").add("idea2strategy-api");
        claims.put("azp", "idea2strategy-api");

        assertThat(verifier.verify(request(token(validHeader(), claims, signingKey))).subject())
                .isEqualTo("subject-1");
    }

    @Test
    void rejectsMultipleAudiencesWithoutExactTrustedAuthorizedParty() throws Exception {
        ObjectNode missingAuthorizedParty = validClaims();
        missingAuthorizedParty.putArray("aud").add("another-client").add("idea2strategy-api");
        assertRejected(request(token(validHeader(), missingAuthorizedParty, signingKey)));

        ObjectNode wrongAuthorizedParty = validClaims();
        wrongAuthorizedParty.putArray("aud").add("another-client").add("idea2strategy-api");
        wrongAuthorizedParty.put("azp", "another-client");
        assertRejected(request(token(validHeader(), wrongAuthorizedParty, signingKey)));
    }

    @Test
    void rejectsUnknownProviderAndNonRs256Algorithm() throws Exception {
        assertRejected(new OidcIdTokenVerificationRequest("UNKNOWN", "token"));
        ObjectNode header = validHeader().put("alg", "none");
        assertRejected(request(token(header, validClaims(), signingKey)));
    }

    @Test
    void rejectsUnsupportedCriticalHeader() throws Exception {
        ObjectNode header = validHeader();
        header.putArray("crit").add("custom-extension");
        header.put("custom-extension", true);

        assertRejected(request(token(header, validClaims(), signingKey)));
    }

    @Test
    void rejectsMissingOrUnknownKid() throws Exception {
        ObjectNode missingKid = validHeader();
        missingKid.remove("kid");
        assertRejected(request(token(missingKid, validClaims(), signingKey)));
        assertRejected(request(token(validHeader().put("kid", "other-key"), validClaims(), signingKey)));
    }

    @Test
    void rejectsInvalidSignature() throws Exception {
        assertRejected(request(token(validHeader(), validClaims(), rsaKeyPair())));
    }

    @Test
    void rejectsIssuerAndAudienceMismatch() throws Exception {
        assertRejected(request(token(validHeader(), validClaims().put("iss", "https://attacker.example"), signingKey)));
        assertRejected(request(token(validHeader(), validClaims().put("aud", "other-client"), signingKey)));
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        assertRejected(request(token(validHeader(), validClaims().put("exp", NOW.minusSeconds(1).getEpochSecond()), signingKey)));
    }

    @Test
    void rejectsAuthenticationOlderThanTenMinutesAndFutureAuthentication() throws Exception {
        assertRejected(request(token(
                validHeader(), validClaims().put("auth_time", NOW.minus(Duration.ofMinutes(10)).minusSeconds(1).getEpochSecond()), signingKey)));
        assertRejected(request(token(
                validHeader(), validClaims().put("auth_time", NOW.plusSeconds(1).getEpochSecond()), signingKey)));
    }

    @Test
    void rejectsFutureOrNonIntegralIssuedAtWhenPresent() throws Exception {
        assertRejected(request(token(
                validHeader(), validClaims().put("iat", NOW.plusSeconds(1).getEpochSecond()), signingKey)));
        assertRejected(request(token(validHeader(), validClaims().put("iat", "not-an-instant"), signingKey)));
    }

    @Test
    void acceptsAuthenticationExactlyTenMinutesOld() throws Exception {
        ObjectNode claims = validClaims().put("auth_time", NOW.minus(Duration.ofMinutes(10)).getEpochSecond());
        assertThat(verifier.verify(request(token(validHeader(), claims, signingKey))).subject()).isEqualTo("subject-1");
    }

    @Test
    void returnsNonceForDigestComparisonAndRejectsMissingRequiredClaims() throws Exception {
        assertThat(verifier.verify(request(token(validHeader(), validClaims().put("nonce", "different"), signingKey))).nonce())
                .isEqualTo("different");
        for (String claim : List.of("iss", "sub", "aud", "exp", "auth_time", "nonce")) {
            ObjectNode missingClaim = validClaims();
            missingClaim.remove(claim);
            assertRejected(request(token(validHeader(), missingClaim, signingKey)));
        }
    }

    @Test
    void rejectsMalformedTokenAndAmbiguousJwksKid() throws Exception {
        assertRejected(request("not-a-jwt"));
        KeyPair duplicateKidKey = rsaKeyPair();
        verifier = verifierWithSource(uri -> "{\"keys\":[" + jwk(signingKey, "key-1") + "," + jwk(duplicateKidKey, "key-1") + "]}");
        assertRejected(request(token(validHeader(), validClaims(), signingKey)));
    }

    @Test
    void rejectsRsaKeysSmallerThan2048Bits() throws Exception {
        KeyPair weakKey = rsaKeyPair(1024);
        verifier = verifierWithSource(uri -> jwks(weakKey, "key-1"));

        assertRejected(request(token(validHeader(), validClaims(), weakKey)));
    }

    @Test
    void rejectsUnsafeJwksUrisWithoutResolvingHostnames() {
        for (String uri : List.of(
                "http://issuer.example/jwks",
                "https://user@issuer.example/jwks",
                "https://issuer.example/jwks#fragment",
                "https://127.0.0.1/jwks",
                "https://10.0.0.1/jwks",
                "https://172.16.1.1/jwks",
                "https://192.168.1.1/jwks",
                "https://169.254.1.1/jwks",
                "https://[::1]/jwks",
                "https://[fc00::1]/jwks",
                "https://[fe80::1]/jwks")) {
            assertThatThrownBy(() -> new TrustedOidcProviderConfiguration(
                            "EXAMPLE", "https://issuer.example", URI.create(uri), Set.of("idea2strategy-api")))
                    .as(uri)
                    .isInstanceOf(IllegalArgumentException.class);
        }

        assertThat(new TrustedOidcProviderConfiguration(
                        "EXAMPLE", "https://issuer.example", URI.create("https://8.8.8.8/jwks"), Set.of("idea2strategy-api"))
                .jwksUri())
                .isEqualTo(URI.create("https://8.8.8.8/jwks"));
    }

    @Test
    void cachesAProviderKeySoNormalVerificationPerformsNoAdditionalNetworkFetch() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        verifier = verifierWithSource(uri -> {
            fetches.incrementAndGet();
            return jwks(signingKey, "key-1");
        });
        var request = request(token(validHeader(), validClaims(), signingKey));

        verifier.verify(request);
        verifier.verify(request);

        assertThat(fetches).hasValue(1);
    }

    @Test
    void concurrentColdCacheAndUnknownKidUseSingleFlightAndProviderWideNegativeThrottle() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        verifier = verifierWithSource(uri -> {
            fetches.incrementAndGet();
            Thread.sleep(40);
            return jwks(signingKey, "key-1");
        });
        var valid = request(token(validHeader(), validClaims(), signingKey));
        var executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<String>> coldVerifications = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(ignored -> (Callable<String>) () -> verifier.verify(valid).subject())
                    .toList();
            assertThat(executor.invokeAll(coldVerifications).stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }))
                    .allMatch("subject-1"::equals);
            assertThat(fetches).hasValue(1);

            var unknown = request(token(validHeader().put("kid", "unknown-1"), validClaims(), signingKey));
            List<Callable<Boolean>> unknownVerifications = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(ignored -> (Callable<Boolean>) () -> {
                        try {
                            verifier.verify(unknown);
                            return false;
                        } catch (OidcIdTokenVerificationException expected) {
                            return true;
                        }
                    })
                    .toList();
            assertThat(executor.invokeAll(unknownVerifications).stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }))
                    .allMatch(Boolean.TRUE::equals);
            assertThat(fetches).hasValue(2);

            assertRejected(request(token(validHeader().put("kid", "unknown-2"), validClaims(), signingKey)));
            assertThat(fetches).hasValue(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentJwksOutagePerformsOneFetchAndThrottlesFurtherRefreshes() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        verifier = verifierWithSource(uri -> {
            fetches.incrementAndGet();
            Thread.sleep(40);
            throw new java.io.IOException("unavailable");
        });
        var request = request(token(validHeader(), validClaims(), signingKey));
        var executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Boolean>> attempts = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(ignored -> (Callable<Boolean>) () -> {
                        try {
                            verifier.verify(request);
                            return false;
                        } catch (OidcIdTokenVerificationException expected) {
                            return true;
                        }
                    })
                    .toList();
            assertThat(executor.invokeAll(attempts).stream().map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }))
                    .allMatch(Boolean.TRUE::equals);
            assertThat(fetches).hasValue(1);
            assertRejected(request);
            assertThat(fetches).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private TrustedOidcIdTokenVerifier verifierWithSource(JwksSource source) {
        var provider = new TrustedOidcProviderConfiguration(
                "EXAMPLE", "https://issuer.example", JWKS_URI, Set.of("idea2strategy-api"));
        return new TrustedOidcIdTokenVerifier(
                JSON, source, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(10), Map.of("EXAMPLE", provider));
    }

    private void assertRejected(OidcIdTokenVerificationRequest request) {
        assertThatThrownBy(() -> verifier.verify(request)).isInstanceOf(OidcIdTokenVerificationException.class);
    }

    private OidcIdTokenVerificationRequest request(String token) {
        return new OidcIdTokenVerificationRequest("EXAMPLE", token);
    }

    private ObjectNode validHeader() {
        return JSON.createObjectNode().put("alg", "RS256").put("kid", "key-1");
    }

    private ObjectNode validClaims() {
        return JSON.createObjectNode()
                .put("iss", "https://issuer.example")
                .put("sub", "subject-1")
                .put("aud", "idea2strategy-api")
                .put("exp", NOW.plusSeconds(60).getEpochSecond())
                .put("iat", NOW.minusSeconds(65).getEpochSecond())
                .put("auth_time", NOW.minusSeconds(60).getEpochSecond())
                .put("nonce", "nonce-1")
                .put("email", "person@example.com");
    }

    private static KeyPair rsaKeyPair() throws Exception {
        return rsaKeyPair(2048);
    }

    private static KeyPair rsaKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String token(ObjectNode header, ObjectNode claims, KeyPair key) throws Exception {
        String signingInput = encoded(JSON.writeValueAsBytes(header)) + "." + encoded(JSON.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + encoded(signature.sign());
    }

    private static String jwks(KeyPair key, String kid) {
        return "{\"keys\":[" + jwk(key, kid) + "]}";
    }

    private static String jwk(KeyPair key, String kid) {
        RSAPublicKey publicKey = (RSAPublicKey) key.getPublic();
        return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + kid
                + "\",\"n\":\"" + encoded(unsigned(publicKey.getModulus())) + "\",\"e\":\""
                + encoded(unsigned(publicKey.getPublicExponent())) + "\"}";
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes.length > 1 && bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    private static String encoded(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
