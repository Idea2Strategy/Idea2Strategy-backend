package com.idea2strategy.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperatorBootstrapCommandTest {
    @TempDir Path temp;

    @Test
    void rejectsDetachedHashMismatchWithoutEchoingManifestSecrets() throws Exception {
        Path manifest = temp.resolve("reviewed.json");
        Files.writeString(manifest, "{\"externalIdentityKeyHmac\":\"must-not-be-echoed\"}");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = Idea2StrategyCli.run(new String[] {"operator", "bootstrap", "--manifest",
                        manifest.toString(), "--expected-sha256", "0".repeat(64)},
                new ByteArrayInputStream(new byte[0]), out, err, Map.of());
        assertThat(exit).isEqualTo(5);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .contains("OPERATOR_BOOTSTRAP_MANIFEST_HASH_MISMATCH")
                .doesNotContain("must-not-be-echoed");
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void rejectsDuplicateAndUnknownJsonWithoutEchoingContent() throws Exception {
        for (String json : new String[] {"{\"bootstrapKey\":\"secret\",\"bootstrapKey\":\"again\"}",
                "{\"unknownSecret\":\"never-echo\"}"}) {
            Path manifest = temp.resolve("invalid-" + json.length() + ".json");
            Files.writeString(manifest, json);
            var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
            int exit = Idea2StrategyCli.run(new String[] {"operator", "bootstrap", "--manifest",
                            manifest.toString(), "--expected-sha256", sha256(Files.readAllBytes(manifest))},
                    new ByteArrayInputStream(new byte[0]), out, err, Map.of());
            assertThat(exit).isEqualTo(5);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("OPERATOR_BOOTSTRAP_MANIFEST_INVALID")
                    .doesNotContain("secret").doesNotContain("never-echo");
        }
    }

    @Test
    void rejectsOversizedManifestBeforeParsing() throws Exception {
        Path manifest = temp.resolve("oversized.json");
        Files.write(manifest, new byte[1_048_577]);
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int exit = Idea2StrategyCli.run(new String[] {"operator", "bootstrap", "--manifest",
                        manifest.toString(), "--expected-sha256", "0".repeat(64)},
                new ByteArrayInputStream(new byte[0]), out, err, Map.of());
        assertThat(exit).isEqualTo(5);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("OPERATOR_BOOTSTRAP_MANIFEST_SIZE_INVALID");
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
