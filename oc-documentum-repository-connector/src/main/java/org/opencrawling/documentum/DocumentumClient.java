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
package org.opencrawling.documentum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.Optional;
import java.util.function.Supplier;

/**
 * HTTP client of Documentum REST Services, as OpenText's own REST clients use them.
 *
 * <p>Every call is a GET with {@code Accept: application/vnd.emc.documentum+json}, authenticated on each request with Basic
 * or a bearer token (REST Services keep no session for it, and a GET needs no CSRF token). DQL runs as
 * {@code GET <repository>?dql=...&page=...&items-per-page=...}, the repository's {@code dql} link template; the rows are
 * the {@code entries[].content.properties} of the result feed. Content metadata comes from
 * {@code objects/<id>/contents/content?media-url-policy=local}, whose {@code enclosure} link (title {@code LOCAL}) serves
 * the bytes from the REST server itself; an enclosure on another host (an ACS or BOCS server) is refused, so credentials
 * never leave the REST server.
 *
 * <p>Errors arrive as {@code {status, code, message, details, id}} and become a {@link DocumentumApiException}. Connection
 * failures, 429 and 502-504 are retried with a growing back-off (honouring {@code Retry-After}); an HTTP 401 with an OAuth2
 * token source fetches a new token once. Credentials and tokens are never logged.
 */
public class DocumentumClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentumClient.class);
    static final String DM_JSON = "application/vnd.emc.documentum+json";
    private static final String USER_AGENT = "OpenCrawling-Documentum-Connector/1.0";

    private final DocumentumProperties settings;
    private final Duration timeout;
    private final int maxRetries;
    private final Duration backoff;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Supplier<String> bearer;
    /** The code of the error for a content link on another server, which is never followed with credentials. */
    static final String FOREIGN_LINK = "FOREIGN_LINK";
    private static final int MAX_REDIRECTS = 3;

    private final URI base;

    public DocumentumClient(DocumentumProperties settings, Duration timeout, int maxRetries, Duration backoff, HttpClient http,
                            ObjectMapper mapper, Supplier<String> bearer) {
        this.settings = settings;
        this.timeout = timeout;
        this.maxRetries = maxRetries;
        this.backoff = backoff;
        this.http = http;
        this.mapper = mapper;
        this.bearer = bearer;
        this.base = URI.create(settings.url());
    }

    public String getEndpoint() {
        return settings.repositoryUrl();
    }

    /** The repository resource (name, id, servers): proves the URL, the credentials and the repository name. */
    public JsonNode repository() throws IOException, InterruptedException {
        return getJson(settings.repositoryUrl(), "repository");
    }

    /**
     * One page of a DQL query's rows: the {@code properties} of each entry, whether a {@code next} page is linked, and the
     * feed's {@code updated} time (the REST server's clock, with its time zone offset).
     */
    public record Page(List<JsonNode> rows, boolean hasNext, String updated) {
    }

    public Page dql(String dql, int page, int itemsPerPage) throws IOException, InterruptedException {
        String uri = settings.repositoryUrl() + "?dql=" + enc(dql) + "&page=" + page + "&items-per-page=" + itemsPerPage;
        JsonNode feed = getJson(uri, "dql");
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode entry : feed.path("entries")) { // a page past the end has no "entries" at all
            rows.add(entry.path("content").path("properties"));
        }
        boolean next = false;
        for (JsonNode link : feed.path("links")) {
            next |= "next".equals(link.path("rel").asText());
        }
        log.debug("DQL page {} ({} rows): {}", page, rows.size(), dql);
        return new Page(rows, next, feed.path("updated").asText(null));
    }

    /**
     * Every row of a DQL query, paged until a page has no rows. Each page re-runs the query, so a query that can span
     * pages must be ordered (the connector's are, by {@code r_object_id}).
     */
    public List<JsonNode> dqlAll(String dql, int itemsPerPage) throws IOException, InterruptedException {
        List<JsonNode> rows = new ArrayList<>();
        for (int page = 1; ; page++) {
            Page p = dql(dql, page, itemsPerPage);
            rows.addAll(p.rows());
            if (p.rows().isEmpty() || !p.hasNext()) {
                return rows;
            }
        }
    }

    /** The authenticated user ({@code currentuser}): its {@code user_privileges} and {@code user_xprivileges}. */
    public JsonNode currentUser() throws IOException, InterruptedException {
        return getJson(settings.repositoryUrl() + "/currentuser", "currentuser");
    }

    /** An object with all its attributes ({@code properties}); empty when it does not exist (any more). */
    public Optional<JsonNode> object(String objectId) throws IOException, InterruptedException {
        try {
            return Optional.of(getJson(settings.repositoryUrl() + "/objects/" + enc(objectId), "object"));
        } catch (DocumentumApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * The metadata of an object's content (page 0) in a format, or its primary content when {@code format} is null, with
     * the {@code LOCAL} enclosure link; empty when there is no such content.
     */
    public Optional<JsonNode> content(String objectId, String format) throws IOException, InterruptedException {
        String uri = settings.repositoryUrl() + "/objects/" + enc(objectId) + "/contents/content?page=0&media-url-policy=local"
                + (format == null ? "" : "&format=" + enc(format));
        try {
            return Optional.of(getJson(uri, "content"));
        } catch (DocumentumApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The enclosure to download from a content resource: the {@code LOCAL} one, else the only one. */
    static Optional<String> enclosure(JsonNode content) {
        String any = null;
        for (JsonNode link : content.path("links")) {
            if ("enclosure".equals(link.path("rel").asText()) && link.hasNonNull("href")) {
                if ("LOCAL".equalsIgnoreCase(link.path("title").asText())) {
                    return Optional.of(link.path("href").asText());
                }
                any = any == null ? link.path("href").asText() : any;
            }
        }
        return Optional.ofNullable(any);
    }

    /**
     * Downloads content to a temporary file.
     *
     * @param href     the enclosure link; it must be on the REST server
     * @param maxBytes a download larger than this is abandoned (empty result); 0 means no limit
     */
    public Optional<Path> download(String href, long maxBytes) throws IOException, InterruptedException {
        URI target = checked(href);
        HttpResponse<InputStream> response = null;
        for (int hop = 0; ; hop++) { // a server may redirect content requests; only to itself, as for the link
            URI current = target;
            response = send(() -> request(current).header("Accept", "*/*").GET(), HttpResponse.BodyHandlers.ofInputStream(),
                    "download");
            Optional<String> location = response.headers().firstValue("Location");
            if (response.statusCode() / 100 != 3 || location.isEmpty() || hop == MAX_REDIRECTS) {
                break;
            }
            response.body().close();
            target = checked(current.resolve(location.get()).toString());
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new DocumentumApiException(response.statusCode(), null, "Documentum content download answered HTTP "
                        + response.statusCode());
            }
            Path file = Files.createTempFile("documentum-", ".content");
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

    /**
     * A link of the REST server. The server writes its links with the host it believes it has, which behind a proxy or
     * TLS offloading is not the configured URL: a link to this REST application's own repositories is followed on the
     * configured URL. Anything else on another host (an ACS or BOCS server) is refused, so credentials never leave it.
     */
    URI checked(String href) throws DocumentumApiException {
        if (href == null || href.isBlank()) {
            throw new DocumentumApiException(0, null, "The content has no enclosure link.");
        }
        URI target = base.resolve(href);
        if (sameOrigin(target, base)) {
            return target;
        }
        String root = base.getRawPath().replaceAll("/+$", "") + "/repositories/";
        if (target.getRawPath() != null && target.getRawPath().startsWith(root)) {
            return URI.create(base.getScheme() + "://" + base.getRawAuthority() + target.getRawPath()
                    + (target.getRawQuery() == null ? "" : "?" + target.getRawQuery()));
        }
        throw new DocumentumApiException(0, FOREIGN_LINK, "Refusing to send Documentum credentials to " + target.getHost()
                + ": the content link is not on the REST server.");
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private JsonNode getJson(String uri, String what) throws IOException, InterruptedException {
        URI target = URI.create(uri);
        HttpResponse<String> response = send(() -> request(target).header("Accept", DM_JSON + ", application/json").GET(),
                HttpResponse.BodyHandlers.ofString(), what);
        if (response.statusCode() != 200) {
            throw error(response, what);
        }
        return mapper.readTree(response.body());
    }

    private DocumentumApiException error(HttpResponse<String> response, String what) {
        String code = null;
        String message = abbreviate(response.body());
        try {
            JsonNode body = mapper.readTree(response.body());
            if (body != null && body.has("code")) {
                code = body.path("code").asText(null);
                message = body.path("message").asText(message);
            }
        } catch (IOException | RuntimeException ignored) {
            // not a REST error body
        }
        return new DocumentumApiException(response.statusCode(), code, "Documentum REST " + what + " answered HTTP "
                + response.statusCode() + (code == null ? "" : " " + code) + ": " + message);
    }

    private HttpRequest.Builder request(URI uri) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT);
        if (settings.authType().equals("basic")) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (settings.username() + ":" + settings.password()).getBytes(StandardCharsets.UTF_8)));
        } else {
            b.header("Authorization", "Bearer " + bearer.get());
        }
        return b;
    }

    private <T> HttpResponse<T> send(Supplier<HttpRequest.Builder> builder, HttpResponse.BodyHandler<T> handler, String what)
            throws IOException, InterruptedException {
        boolean renewed = false;
        for (int attempt = 0; ; attempt++) {
            try {
                HttpResponse<T> response = http.send(builder.get().build(), handler);
                int status = response.statusCode();
                if (status == 401 && !renewed && bearer instanceof DocumentumOAuth2TokenSource tokens) {
                    discard(response);
                    tokens.invalidate();
                    renewed = true;
                    attempt--;
                    continue;
                }
                if ((status == 429 || status == 502 || status == 503 || status == 504) && attempt < maxRetries) {
                    long wait = retryAfter(response).orElse(Math.min(backoff.toMillis() * (1L << attempt), 30_000L));
                    discard(response);
                    log.warn("Documentum {} answered HTTP {}; retrying in {} ms ({}/{}).", what, status, wait, attempt + 1,
                            maxRetries);
                    Thread.sleep(wait);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= maxRetries) {
                    throw e;
                }
                log.warn("Documentum {} failed ({}); retrying ({}/{}).", what, e.getMessage(), attempt + 1, maxRetries);
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

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
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
