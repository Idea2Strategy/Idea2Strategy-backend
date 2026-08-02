package com.idea2strategy.backend.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpJwksSourceTest {
    private static final int MAX_JWKS_BYTES = 1_048_576;
    private static final URI JWKS_URI = URI.create("https://issuer.example/.well-known/jwks.json");

    @Test
    void rejectsOversizedContentLengthBeforeReadingAndClosesTheStream() throws Exception {
        var body = new TrackingInputStream(MAX_JWKS_BYTES + 10_000);
        var source = source(response(body, Map.of(
                "Content-Length", List.of(Integer.toString(MAX_JWKS_BYTES + 1)))));

        assertThatThrownBy(() -> source.load(JWKS_URI)).isInstanceOf(java.io.IOException.class);
        assertThat(body.bytesRead).isZero();
        assertThat(body.closed).isTrue();
    }

    @Test
    void hardBoundsChunkedResponsesToOneBytePastTheLimitAndClosesTheStream() throws Exception {
        var body = new TrackingInputStream(MAX_JWKS_BYTES + 10_000);
        var source = source(response(body, Map.of("Transfer-Encoding", List.of("chunked"))));

        assertThatThrownBy(() -> source.load(JWKS_URI)).isInstanceOf(java.io.IOException.class);
        assertThat(body.bytesRead).isEqualTo(MAX_JWKS_BYTES + 1);
        assertThat(body.closed).isTrue();
    }

    @Test
    void returnsAValidBoundedResponseAndClosesTheStream() throws Exception {
        byte[] json = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
        var body = new TrackingInputStream(json);
        var source = source(response(body, Map.of("Content-Length", List.of(Integer.toString(json.length)))));

        assertThat(source.load(JWKS_URI)).isEqualTo("{\"keys\":[]}");
        assertThat(body.closed).isTrue();
    }

    @Test
    void rejectsNonJwksContentTypeWithoutReadingAndClosesTheStream() throws Exception {
        var body = new TrackingInputStream(128);
        var source = source(response(body, Map.of("Content-Type", List.of("text/html"))));

        assertThatThrownBy(() -> source.load(JWKS_URI)).isInstanceOf(java.io.IOException.class);
        assertThat(body.bytesRead).isZero();
        assertThat(body.closed).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static HttpJwksSource source(HttpResponse<InputStream> response) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        return new HttpJwksSource(client, Duration.ofSeconds(1));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(
            InputStream body, Map<String, List<String>> headers) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        var responseHeaders = new java.util.LinkedHashMap<>(headers);
        responseHeaders.putIfAbsent("Content-Type", List.of("application/jwk-set+json"));
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(responseHeaders, (name, value) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }

    private static final class TrackingInputStream extends InputStream {
        private final byte[] content;
        private int position;
        private int bytesRead;
        private boolean closed;

        private TrackingInputStream(int length) {
            this.content = new byte[length];
            java.util.Arrays.fill(this.content, (byte) 'x');
        }

        private TrackingInputStream(byte[] content) {
            this.content = content.clone();
        }

        @Override
        public int read() {
            if (position >= content.length) {
                return -1;
            }
            bytesRead++;
            return content[position++] & 0xff;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(length, content.length - position);
            System.arraycopy(content, position, target, offset, count);
            position += count;
            bytesRead += count;
            return count;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
