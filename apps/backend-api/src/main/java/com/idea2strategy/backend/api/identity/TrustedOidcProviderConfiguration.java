package com.idea2strategy.backend.api.identity;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Set;

public record TrustedOidcProviderConfiguration(
        String providerCode, String issuer, URI jwksUri, Set<String> audiences) {
    public TrustedOidcProviderConfiguration {
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(jwksUri, "jwksUri");
        audiences = Set.copyOf(Objects.requireNonNull(audiences, "audiences"));
        if (providerCode.isBlank()
                || issuer.isBlank()
                || !validHttpsIssuer(issuer)
                || audiences.isEmpty()
                || audiences.stream().anyMatch(String::isBlank)
                || !isSafeJwksUri(jwksUri)) {
            throw new IllegalArgumentException("Trusted OIDC provider configuration is invalid");
        }
    }

    static boolean isSafeJwksUri(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            return false;
        }
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        boolean numericIpv4 = host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
        boolean numericIpv6 = host.indexOf(':') >= 0;
        if (host.matches("[0-9.]+") && !numericIpv4) {
            return false;
        }
        if (!numericIpv4 && !numericIpv6) {
            return true;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            boolean uniqueLocalIpv6 = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
            return !address.isAnyLocalAddress()
                    && !address.isLoopbackAddress()
                    && !address.isLinkLocalAddress()
                    && !address.isSiteLocalAddress()
                    && !address.isMulticastAddress()
                    && !uniqueLocalIpv6;
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean validHttpsIssuer(String issuer) {
        try {
            URI uri = URI.create(issuer);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
