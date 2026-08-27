package com.idea2strategy.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

final class ApiClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI baseUri;
    private final HttpClient client;

    ApiClient(String baseUrl) {
        try {
            String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.baseUri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw Arguments.usage("Invalid --base-url");
        }
        if (!"http".equals(baseUri.getScheme()) && !"https".equals(baseUri.getScheme())) {
            throw Arguments.usage("--base-url must use http or https");
        }
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    JsonNode get(String path, String token) {
        return send("GET", path, null, token);
    }

    JsonNode post(String path, JsonNode body, String token) {
        return send("POST", path, body, token, Map.of());
    }

    JsonNode post(String path, JsonNode body, String token, Map<String, String> headers) {
        return send("POST", path, body, token, headers);
    }

    JsonNode delete(String path, String token) {
        return send("DELETE", path, null, token, Map.of());
    }

    private JsonNode send(String method, String path, JsonNode body, String token) {
        return send(method, path, body, token, Map.of());
    }

    private JsonNode send(String method, String path, JsonNode body, String token, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("X-Client", "idea2strategy-cli/0.1");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        String encoded = body == null ? "" : body.toString();
        switch (method) {
            case "GET" -> request.GET();
            case "POST" -> request.POST(HttpRequest.BodyPublishers.ofString(encoded));
            case "DELETE" -> request.DELETE();
            default -> throw new IllegalStateException("Unsupported HTTP method");
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode responseBody = parse(response.body());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return responseBody;
            }
            throw httpFailure(response.statusCode(), responseBody);
        } catch (CliFailure failure) {
            throw failure;
        } catch (IOException exception) {
            throw new CliFailure(6, "SERVICE_UNAVAILABLE", "Idea2Strategy API is unavailable");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CliFailure(6, "REQUEST_INTERRUPTED", "Idea2Strategy API request was interrupted");
        }
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return JSON.createObjectNode();
        }
        try {
            return JSON.readTree(body);
        } catch (IOException exception) {
            throw new CliFailure(6, "INVALID_SERVER_RESPONSE", "Idea2Strategy API returned invalid JSON");
        }
    }

    private static CliFailure httpFailure(int status, JsonNode body) {
        int exitCode = switch (status) {
            case 401 -> 3;
            case 403 -> 4;
            case 400, 404, 409, 422 -> 5;
            default -> 6;
        };
        String fallbackCode = switch (status) {
            case 401 -> "AUTHENTICATION_FAILED";
            case 403 -> "AUTHORIZATION_FAILED";
            case 409 -> "CONFLICT";
            default -> status >= 500 ? "SERVICE_ERROR" : "REQUEST_REJECTED";
        };
        String code = body.path("code").asText(fallbackCode);
        String message = body.path("message").asText();
        if (message.isBlank()) {
            message = body.path("detail").isTextual()
                    ? body.path("detail").asText()
                    : body.path("title").asText("Idea2Strategy API rejected the request");
        }
        return new CliFailure(exitCode, code, message, status);
    }
}
