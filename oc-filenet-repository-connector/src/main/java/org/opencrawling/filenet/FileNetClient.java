/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.filenet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP client of the FileNet Content Services GraphQL API.
 *
 * <p>Requests follow IBM's own clients (the Content Services sample application and the Content Services MCP server):
 * POST the GraphQL document as JSON to the endpoint, authenticated with Basic, a bearer token or an LTPA cookie, and
 * carrying a client-generated XSRF token both as the {@code ECM-CS-XSRF-Token} header and as a cookie of the same name (the
 * server's double-submit check). GraphQL errors arrive with HTTP 200 and an {@code errors} array and become a
 * {@link FileNetApiException}. Content is downloaded from an element's {@code downloadUrl}, which is relative to the
 * {@code /content-services-graphql} base, with the same headers; a URL to another host is refused, so credentials never
 * leave the FileNet server.
 *
 * <p>The GraphQL documents are resources ({@code filenet-graphql/*.graphql}); the shared {@code *.fragment.graphql} a
 * document spreads is appended to it. They are validated against IBM's published schema by the mock's conformance check.
 * Connection failures, 429 and 502-504 are retried with a growing back-off (honouring {@code Retry-After}); an HTTP 401
 * with an OAuth2 token source fetches a new token once. Credentials and tokens are never logged.
 */
public class FileNetClient {

    private static final Logger log = LoggerFactory.getLogger(FileNetClient.class);
    private static final String XSRF = "ECM-CS-XSRF-Token";
    private static final String USER_AGENT = "OpenCrawling-FileNet-Connector/1.0";
    private static final Pattern FRAGMENT_NAME = Pattern.compile("fragment\\s+(\\w+)");
    private static final List<String> FRAGMENTS = List.of("document-fields");
    private static final Map<String, String> DOCUMENTS = new ConcurrentHashMap<>();

    private final FileNetProperties settings;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration backoff;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Supplier<String> bearer;
    private final URI endpoint;
    private final URI contentBase;
    private final String xsrf = UUID.randomUUID().toString();

    public FileNetClient(FileNetProperties settings, Duration timeout, int maxRetries, Duration backoff, HttpClient http,
                         ObjectMapper mapper, Supplier<String> bearer) {
        this.settings = settings;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.backoff = backoff;
        this.http = http;
        this.mapper = mapper;
        this.bearer = bearer;
        this.endpoint = URI.create(settings.url());
        this.contentBase = URI.create(settings.contentBase() + "/");
    }

    public String getEndpoint() {
        return settings.url();
    }

    /**
     * Runs a GraphQL document ({@code filenet-graphql/<name>.graphql}) and returns its {@code data}.
     *
     * @throws FileNetApiException on an HTTP failure or GraphQL errors
     */
    public JsonNode execute(String document, Map<String, Object> variables) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode();
        body.put("query", document(document));
        body.set("variables", mapper.valueToTree(variables));
        byte[] json = mapper.writeValueAsBytes(body);
        HttpResponse<String> response = send(() -> request(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json)), HttpResponse.BodyHandlers.ofString(), document);
        if (response.statusCode() != 200) {
            throw new FileNetApiException(response.statusCode(), "FileNet GraphQL " + document + " answered HTTP "
                    + response.statusCode() + ": " + abbreviate(response.body()));
        }
        JsonNode result = mapper.readTree(response.body());
        JsonNode errors = result.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(e -> messages.add(e.path("message").asText(e.toString())));
            throw new FileNetApiException(200, "FileNet GraphQL " + document + " failed: " + String.join("; ", messages),
                    messages);
        }
        return result.path("data");
    }

    /**
     * Downloads one content element to a temporary file.
     *
     * @param downloadUrl the element's {@code downloadUrl}: relative to the {@code /content-services-graphql} base, or
     *                    absolute on the same host
     * @param maxBytes    a download larger than this is abandoned (empty result); 0 means no limit
     */
    public Optional<Path> download(String downloadUrl, long maxBytes) throws IOException, InterruptedException {
        URI target = resolve(downloadUrl);
        HttpResponse<InputStream> response = send(() -> request(target).header("Accept", "*/*").GET(),
                HttpResponse.BodyHandlers.ofInputStream(), "download");
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new FileNetApiException(response.statusCode(), "FileNet content download answered HTTP "
                        + response.statusCode());
            }
            Path file = Files.createTempFile("filenet-", ".content");
            try {
                InputStream source = maxBytes > 0 ? new LimitedStream(in, maxBytes) : in;
                Files.copy(source, file, StandardCopyOption.REPLACE_EXISTING);
                return Optional.of(file);
            } catch (LimitedStream.TooLarge e) {
                Files.deleteIfExists(file);
                return Optional.empty();
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(file);
                throw e;
            }
        }
    }

    /** A download URL resolved against the content base; one that points at another host is refused. */
    URI resolve(String downloadUrl) throws FileNetApiException {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new FileNetApiException(0, "The content element has no download URL.");
        }
        String relative = downloadUrl.startsWith("/") ? downloadUrl.substring(1) : downloadUrl;
        URI target = downloadUrl.matches("(?i)^https?://.*") ? URI.create(downloadUrl) : contentBase.resolve(relative);
        if (!sameOrigin(target, endpoint)) {
            throw new FileNetApiException(0, "Refusing to send FileNet credentials to " + target.getHost()
                    + ": the download URL is not on the FileNet server.");
        }
        return target;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header(XSRF, xsrf);
        String cookie = XSRF + "=" + xsrf;
        switch (settings.authType()) {
            case "basic" -> b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8)));
            case "ltpa" -> cookie += "; LtpaToken2=" + settings.ltpaToken();
            default -> b.header("Authorization", "Bearer " + bearer.get());
        }
        return b.header("Cookie", cookie);
    }

    private <T> HttpResponse<T> send(Supplier<HttpRequest.Builder> builder, HttpResponse.BodyHandler<T> handler, String what)
            throws IOException, InterruptedException {
        boolean renewed = false;
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<T> response = http.send(builder.get().build(), handler);
                int status = response.statusCode();
                if (status == 401 && !renewed && bearer instanceof FileNetOAuth2TokenSource tokens) {
                    discard(response);
                    tokens.invalidate();
                    renewed = true;
                    attempt--;
                    continue;
                }
                if ((status == 429 || status == 502 || status == 503 || status == 504) && attempt < maxRetries) {
                    long wait = retryAfter(response).orElse(Math.min(backoff.toMillis() * (1L << attempt), 30_000L));
                    discard(response);
                    log.warn("FileNet {} answered HTTP {}; retrying in {} ms ({}/{}).", what, status, wait, attempt + 1,
                            maxRetries);
                    Thread.sleep(wait);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                log.warn("FileNet {} failed ({}); retrying ({}/{}).", what, e.getMessage(), attempt + 1, maxRetries);
                Thread.sleep(Math.min(backoff.toMillis() * (1L << attempt), 30_000L));
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    private static Optional<Long> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(v -> {
            try {
                return Optional.of(Math.min(Long.parseLong(v.strip()) * 1000L, 120_000L));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        });
    }

    private static void discard(HttpResponse<?> response) {
        if (response.body() instanceof InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
                // nothing to release
            }
        }
    }

    /** A GraphQL document from the resources, with the shared fragments it spreads appended. */
    static String document(String name) {
        return DOCUMENTS.computeIfAbsent(name, n -> {
            String text = resource(n + ".graphql");
            for (String fragment : FRAGMENTS) {
                String fragmentText = resource(fragment + ".fragment.graphql");
                Matcher m = FRAGMENT_NAME.matcher(fragmentText);
                if (m.find() && text.contains("..." + m.group(1))) {
                    text = text + "\n" + fragmentText;
                }
            }
            return text;
        });
    }

    private static String resource(String name) {
        try (InputStream in = FileNetClient.class.getResourceAsStream("/filenet-graphql/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing GraphQL document filenet-graphql/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String b = body.replaceAll("\\s+", " ").strip();
        return b.length() > 300 ? b.substring(0, 300) + "…" : b;
    }

    /** A stream that fails with {@link TooLarge} once more than {@code max} bytes were read. */
    static final class LimitedStream extends java.io.FilterInputStream {
        static final class TooLarge extends IOException {
            TooLarge() {
                super("content larger than the configured maximum");
            }
        }

        private final long max;
        private long read;

        LimitedStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++read > max) {
                throw new TooLarge();
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            int n = super.read(buffer, off, len);
            if (n > 0 && (read += n) > max) {
                throw new TooLarge();
            }
            return n;
        }
    }
}
