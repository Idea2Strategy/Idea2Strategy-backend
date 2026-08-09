package com.idea2strategy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalAiToolE2eTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<Integer> forcedStatus = new AtomicReference<>();
    private final AtomicReference<JsonNode> reviewedOperations = new AtomicReference<>();
    private final List<JsonNode> requestBodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handleRequest);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void publishesMachineReadableToolContractWithoutCredentials() throws Exception {
        ProcessResult result = invoke(false, "tool-contract");

        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).isEmpty();
        JsonNode contract = JSON.readTree(result.stdout()).path("data");
        assertThat(contract.path("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(contract.path("outputMode").asText()).isEqualTo("JSON");
        assertThat(contract.path("reviewGate").path("applyRequiresPreviewHash").asBoolean()).isTrue();
        assertThat(contract.path("allowedEditOperations")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("ADD_BLOCK", "REMOVE_BLOCK", "CONNECT_BLOCKS", "SET_VALUE");
        assertThat(contract.path("forbiddenCapabilities")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("ARBITRARY_CODE", "DIRECT_ORDER", "EXTERNAL_DATA");
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void externalToolReviewsPreviewDiffBeforeApplyingExactHash() throws Exception {
        Path operations = operations("SET_VALUE");

        ProcessResult preview = invokeEdit("preview", operations);
        JsonNode previewData = JSON.readTree(preview.stdout()).path("data");
        assertThat(preview.exitCode()).isZero();
        assertThat(previewData.path("diff").isEmpty()).isFalse();
        String reviewedHash = previewData.path("previewHash").asText();
        assertThat(reviewedHash).isEqualTo("sha256:reviewed-diff");
        String reviewedSequence = previewData.path("expectedEditSequence").asText();
        assertThat(reviewedSequence).isEqualTo("7");

        ProcessResult apply = invokeEdit(
                "apply", operations,
                "--preview-hash", reviewedHash,
                "--expected-edit-sequence", reviewedSequence);

        assertThat(apply.exitCode()).isZero();
        assertThat(JSON.readTree(apply.stdout()).path("data").path("applied").asBoolean()).isTrue();
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(1).path("previewHash").asText()).isEqualTo(reviewedHash);
        assertThat(requestBodies.get(1).path("expectedEditSequence").asLong()).isEqualTo(7L);
    }

    @Test
    void rejectsArbitraryCodeDirectOrdersAndExternalDataBeforeNetworkCall() throws Exception {
        for (String forbidden : List.of("EXECUTE_CODE", "SUBMIT_ORDER", "FETCH_EXTERNAL_DATA")) {
            ProcessResult result = invokeEdit("preview", operations(forbidden));

            assertThat(result.exitCode()).isEqualTo(5);
            assertThat(JSON.readTree(result.stderr()).path("error").path("code").asText())
                    .isEqualTo("OPERATION_NOT_ALLOWED");
        }
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void rejectsApplyWithoutReviewedHashBeforeNetworkCall() throws Exception {
        ProcessResult result = invokeEdit("apply", operations("ADD_BLOCK"));

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(requestCount).hasValue(0);
    }

    @Test
    void mapsScopeOverreachAndTamperedPreviewToStableSafetyExitCodes() throws Exception {
        forcedStatus.set(403);
        ProcessResult denied = invokeEdit("preview", operations("ADD_BLOCK"));
        assertThat(denied.exitCode()).isEqualTo(4);
        assertThat(JSON.readTree(denied.stderr()).path("error").path("code").asText())
                .isEqualTo("SCOPE_DENIED");

        forcedStatus.set(null);
        ProcessResult tampered = invokeEdit(
                "apply", operations("ADD_BLOCK"),
                "--preview-hash", "sha256:tampered",
                "--expected-edit-sequence", "7");
        assertThat(tampered.exitCode()).isEqualTo(5);
        assertThat(JSON.readTree(tampered.stderr()).path("error").path("code").asText())
                .isEqualTo("PREVIEW_MISMATCH");
    }

    private Path operations(String action) throws IOException {
        Path file = tempDir.resolve(action + "-operations.json");
        Files.writeString(file, "[{\"action\":\"" + action + "\",\"arguments\":{}}]");
        return file;
    }

    private ProcessResult invokeEdit(String phase, Path operations, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of(
                "strategy", "edit", phase,
                "--strategy-id", "strategy-1",
                "--authorization-id", "authorization-1",
                "--credential-id", "credential-1",
                "--operations-file", operations.toString()));
        args.addAll(List.of(extra));
        return invoke(true, args.toArray(String[]::new));
    }

    private ProcessResult invoke(boolean withToken, String... args) throws Exception {
        Path installDir = Path.of(System.getProperty("idea2strategy.cli.installDir"));
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path executable = installDir.resolve("bin").resolve(windows ? "idea2strategy.bat" : "idea2strategy");
        List<String> command = new ArrayList<>();
        if (windows) {
            command.addAll(List.of("cmd.exe", "/d", "/c", executable.toString()));
        } else {
            command.add(executable.toString());
        }
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("I2S_BASE_URL", baseUrl);
        builder.environment().put("I2S_CONFIG_DIR", tempDir.resolve("config").toString());
        if (withToken) {
            builder.environment().put("I2S_TOKEN", "external-ai-token");
        } else {
            builder.environment().remove("I2S_TOKEN");
        }
        Process process = builder.start();
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), stdout.trim(), stderr.trim());
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        JsonNode body = JSON.readTree(exchange.getRequestBody());
        requestBodies.add(body);
        if (forcedStatus.get() != null) {
            respond(exchange, forcedStatus.get(), "{\"code\":\"SCOPE_DENIED\",\"message\":\"scope denied\"}");
            return;
        }
        if (exchange.getRequestURI().getPath().endsWith("/preview")) {
            reviewedOperations.set(body.path("operations").deepCopy());
            respond(exchange, 200,
                    "{\"previewHash\":\"sha256:reviewed-diff\",\"expectedEditSequence\":7,"
                            + "\"diff\":[{\"op\":\"replace\",\"path\":\"/blocks/0/value\"}]}");
            return;
        }
        if (!"sha256:reviewed-diff".equals(body.path("previewHash").asText())
                || body.path("expectedEditSequence").asLong(-1) != 7L
                || !body.path("operations").equals(reviewedOperations.get())) {
            respond(exchange, 409,
                    "{\"code\":\"PREVIEW_MISMATCH\",\"message\":\"preview hash was not reviewed\"}");
            return;
        }
        respond(exchange, 200, "{\"applied\":true}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
