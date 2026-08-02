package com.idea2strategy.backend.api.identity;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * Fetches JWKS only from deployment-configured HTTPS endpoints. Literal local/private
 * addresses are rejected; deployments must additionally enforce an egress allowlist
 * because a configured hostname can still be affected by DNS rebinding.
 */
public final class HttpJwksSource implements JwksSource {
    private static final int MAX_JWKS_BYTES = 1_048_576;

    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public HttpJwksSource(HttpClient httpClient, Duration requestTimeout) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("JWKS request timeout must be positive");
        }
    }

    public static HttpJwksSource createDefault() {
        return new HttpJwksSource(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Duration.ofSeconds(5));
    }

    @Override
    public String load(URI jwksUri) throws IOException, InterruptedException {
        Objects.requireNonNull(jwksUri, "jwksUri");
        if (!TrustedOidcProviderConfiguration.isSafeJwksUri(jwksUri)) {
            throw new IOException("JWKS URI is not allowed");
        }
        HttpRequest request = HttpRequest.newBuilder(jwksUri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream responseBody = response.body();
        if (responseBody == null) {
            throw new IOException("JWKS endpoint returned an invalid response");
        }
        try (responseBody) {
            if (response.statusCode() != 200) {
                throw new IOException("JWKS endpoint returned an invalid response");
            }
            String contentType = response.headers().firstValue("Content-Type")
                    .map(value -> value.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT))
                    .orElse("");
            if (!"application/json".equals(contentType)
                    && !"application/jwk-set+json".equals(contentType)) {
                throw new IOException("JWKS endpoint returned an invalid response");
            }
            long declaredLength;
            try {
                declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            } catch (NumberFormatException exception) {
                throw new IOException("JWKS endpoint returned an invalid response", exception);
            }
            if (declaredLength == 0 || declaredLength > MAX_JWKS_BYTES) {
                throw new IOException("JWKS endpoint returned an invalid response");
            }
            byte[] boundedBody = responseBody.readNBytes(MAX_JWKS_BYTES + 1);
            if (boundedBody.length == 0 || boundedBody.length > MAX_JWKS_BYTES) {
                throw new IOException("JWKS endpoint returned an invalid response");
            }
            return new String(boundedBody, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
