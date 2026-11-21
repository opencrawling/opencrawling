/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.googledrive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Lightweight, zero-SDK-bloat Google Drive REST API v3 HTTP client built on Java 25 {@link HttpClient}.
 *
 * <p>Supports:
 * <ul>
 *   <li>Google Cloud Service Account RS256 JWT assertion signing ({@code java.security} PKCS#8 RSA) with optional
 *       Domain-Wide Delegation ({@code sub} claim, never required) and {@code drive.readonly} scope;</li>
 *   <li>OAuth2 Refresh Token grant and static Bearer token authentication;</li>
 *   <li>Shared throttling gate honoring {@code Retry-After} (seconds or HTTP-date) plus jittered exponential backoff
 *       on HTTP 429, Drive 403 rate-limit errors, 5xx errors, and transient I/O errors;</li>
 *   <li>Constant-heap binary streaming ({@code alt=media}) and Google Workspace export ({@code /export}) directly to
 *       temporary files with strict byte-size enforcement.</li>
 * </ul>
 *
 * <p>Secrets, private keys, JWT assertions, and access tokens are never logged.
 */
public class GoogleDriveClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GoogleDriveClient.class);

    static final String USER_AGENT = "OpenCrawling-GoogleDrive-Connector/1.0";
    private static final Duration TOKEN_MARGIN = Duration.ofMinutes(5);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(2);
    private static final String JWT_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    public static final String FILE_FIELDS =
            "id,name,mimeType,parents,driveId,webViewLink,createdTime,modifiedTime,version,headRevisionId,"
                    + "md5Checksum,size,trashed,explicitlyTrashed,owners(emailAddress,displayName),"
                    + "lastModifyingUser(emailAddress,displayName),properties,appProperties,"
                    + "shortcutDetails(targetId,targetMimeType),description,capabilities(canDownload)";

    private static final String FILES_PAGE_FIELDS = "nextPageToken,files(" + FILE_FIELDS + ")";
    private static final String CHANGES_PAGE_FIELDS =
            "nextPageToken,newStartPageToken,changes(changeType,time,removed,fileId,driveId,file(" + FILE_FIELDS + "))";
    private static final String PERMISSIONS_FIELDS =
            "nextPageToken,permissions(id,type,role,emailAddress,domain,displayName,deleted,expirationTime,"
                    + "permissionDetails(inherited))";

    /** One page returned by {@code GET /drive/v3/files}. */
    public record FilesPage(List<JsonNode> files, String nextPageToken) {
    }

    /** One page returned by {@code GET /drive/v3/changes}. */
    public record ChangesPage(List<JsonNode> changes, String nextPageToken, String newStartPageToken) {
    }

    /** Parsed Google Service Account credentials (never logged). */
    record ServiceAccountCredentials(String clientEmail, PrivateKey privateKey, String privateKeyId, String tokenUri) {
    }

    private final GoogleDriveProperties settings;
    private final URI apiBase;
    private final HttpClient http;
    private final boolean ownsHttp;
    private final ObjectMapper mapper;
    private final Clock clock;

    private ServiceAccountCredentials cachedServiceAccount;
    private String accessToken;
    private Instant tokenExpiry = Instant.MIN;
    private volatile Instant throttledUntil = Instant.MIN;
    private volatile Instant lastServerTime;

    public GoogleDriveClient(GoogleDriveProperties settings) {
        this(settings,
                HttpClient.newBuilder()
                        .connectTimeout(settings.timeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                true,
                new ObjectMapper(),
                Clock.systemUTC());
    }

    GoogleDriveClient(GoogleDriveProperties settings, HttpClient http, ObjectMapper mapper, Clock clock) {
        this(settings, http, false, mapper, clock);
    }

    private GoogleDriveClient(
            GoogleDriveProperties settings,
            HttpClient http,
            boolean ownsHttp,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.apiBase = URI.create(GoogleDriveProperties.stripTrailingSlashes(settings.apiBaseUrl()) + "/");
        this.http = Objects.requireNonNull(http, "http");
        this.ownsHttp = ownsHttp;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<Instant> lastServerTime() {
        return Optional.ofNullable(lastServerTime);
    }

    @Override
    public void close() {
        if (ownsHttp) {
            http.close();
        }
    }

    // ------------------------------------------------------------------ Authentication

    synchronized String accessToken() throws IOException, InterruptedException {
        if (settings.authType() == GoogleDriveProperties.AuthType.BEARER) {
            return settings.accessToken();
        }
        if (accessToken != null && clock.instant().isBefore(tokenExpiry)) {
            return accessToken;
        }
        return switch (settings.authType()) {
            case SERVICE_ACCOUNT -> acquireServiceAccountToken();
            case OAUTH2 -> acquireRefreshToken();
            case BEARER -> settings.accessToken();
        };
    }

    private synchronized void invalidateToken(String used) {
        if (Objects.equals(accessToken, used)) {
            accessToken = null;
            tokenExpiry = Instant.MIN;
        }
    }

    private String acquireServiceAccountToken() throws IOException, InterruptedException {
        ServiceAccountCredentials creds = loadServiceAccount();
        String targetTokenUrl = effectiveTokenUrl(creds);
        String assertion = buildSignedJwtAssertion(creds, targetTokenUrl);
        String form = "grant_type=" + enc(JWT_GRANT_TYPE) + "&assertion=" + enc(assertion);
        return exchangeToken(URI.create(targetTokenUrl), form, "Service Account JWT");
    }

    private String acquireRefreshToken() throws IOException, InterruptedException {
        String form = "grant_type=refresh_token"
                + "&client_id=" + enc(settings.clientId())
                + "&client_secret=" + enc(settings.clientSecret())
                + "&refresh_token=" + enc(settings.refreshToken());
        return exchangeToken(URI.create(settings.tokenUrl()), form, "OAuth2 refresh_token");
    }

    private String exchangeToken(URI tokenUri, String formBody, String flowName)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .timeout(settings.timeout())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(formBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendWithRetries(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        recordServerTime(response);
        JsonNode body = parseJson(response.body());
        if (response.statusCode() != 200 || !body.path("access_token").isTextual()) {
            String err = body.path("error").asText(null);
            String desc = body.path("error_description").asText("");
            throw new GoogleDriveApiException(
                    response.statusCode(),
                    err,
                    "oauth2",
                    "Google " + flowName + " token request failed: HTTP " + response.statusCode()
                            + (err != null ? " " + err : "")
                            + (!desc.isBlank() ? " (" + firstLine(desc) + ")" : "")
            );
        }
        accessToken = body.path("access_token").asText();
        long expiresIn = body.path("expires_in").asLong(3600L);
        Duration margin = expiresIn > 600L ? TOKEN_MARGIN : Duration.ofSeconds(Math.max(1L, expiresIn / 4));
        tokenExpiry = clock.instant().plusSeconds(Math.max(1L, expiresIn)).minus(margin);
        return accessToken;
    }

    private String effectiveTokenUrl(ServiceAccountCredentials creds) {
        if (settings.tokenUrl() != null && !settings.tokenUrl().isBlank()
                && !GoogleDriveProperties.DEFAULT_TOKEN_URL.equalsIgnoreCase(settings.tokenUrl())) {
            return settings.tokenUrl();
        }
        if (creds.tokenUri() != null && !creds.tokenUri().isBlank()) {
            return creds.tokenUri();
        }
        return GoogleDriveProperties.DEFAULT_TOKEN_URL;
    }

    ServiceAccountCredentials loadServiceAccount() throws IOException {
        if (cachedServiceAccount != null) {
            return cachedServiceAccount;
        }
        String raw = settings.serviceAccountJson();
        if (raw == null || raw.isBlank()) {
            throw new IOException("Google Drive service-account-json is not configured.");
        }
        String trimmed = raw.strip();
        String jsonText;
        if (trimmed.startsWith("{")) {
            jsonText = trimmed;
        } else {
            Path keyPath = Path.of(trimmed);
            if (!Files.isRegularFile(keyPath)) {
                throw new IOException("Google Drive service-account-json file not found: " + keyPath);
            }
            jsonText = Files.readString(keyPath, StandardCharsets.UTF_8);
        }
        JsonNode root = parseJson(jsonText);
        String clientEmail = text(root, "client_email");
        String privateKeyPem = text(root, "private_key");
        String privateKeyId = text(root, "private_key_id");
        String tokenUri = text(root, "token_uri");
        if (clientEmail == null || clientEmail.isBlank()) {
            throw new IOException("Service account JSON is missing required field 'client_email'.");
        }
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new IOException("Service account JSON is missing required field 'private_key'.");
        }
        PrivateKey privateKey = parsePkcs8PrivateKey(privateKeyPem);
        cachedServiceAccount = new ServiceAccountCredentials(clientEmail.strip(), privateKey, privateKeyId, tokenUri);
        return cachedServiceAccount;
    }

    String buildSignedJwtAssertion(ServiceAccountCredentials creds, String audienceUrl) throws IOException {
        long now = clock.instant().getEpochSecond();
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (creds.privateKeyId() != null && !creds.privateKeyId().isBlank()) {
            header.put("kid", creds.privateKeyId().strip());
        }

        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", creds.clientEmail());
        claims.put("scope", settings.scope() != null && !settings.scope().isBlank()
                ? settings.scope() : GoogleDriveProperties.DEFAULT_SCOPE);
        claims.put("aud", audienceUrl);
        claims.put("iat", now);
        claims.put("exp", now + 3600L);
        if (settings.delegatedUser() != null && !settings.delegatedUser().isBlank()) {
            claims.put("sub", settings.delegatedUser().strip());
        }

        Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = urlEncoder.encodeToString(mapper.writeValueAsBytes(header));
        String encodedClaims = urlEncoder.encodeToString(mapper.writeValueAsBytes(claims));
        String signingInput = encodedHeader + "." + encodedClaims;

        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(creds.privateKey());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String encodedSig = urlEncoder.encodeToString(signature.sign());
            return signingInput + "." + encodedSig;
        } catch (GeneralSecurityException e) {
            throw new IOException("Failed to sign Google Service Account RS256 JWT assertion: " + e.getMessage(), e);
        }
    }

    static PrivateKey parsePkcs8PrivateKey(String pem) throws IOException {
        String cleaned = pem
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        try {
            byte[] der = Base64.getDecoder().decode(cleaned);
            PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IOException("Failed to parse PKCS#8 RSA private key from service-account-json: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ Drive API v3 endpoints

    /** Connectivity probe: {@code GET /about?fields=user(displayName,emailAddress),storageQuota}. */
    public JsonNode getAbout() throws IOException, InterruptedException {
        return getJson("about?fields=" + enc("user(displayName,emailAddress),storageQuota"));
    }

    /** Retrieves Shared Drive metadata: {@code GET /drives/{driveId}}. */
    public JsonNode getDrive(String driveId) throws IOException, InterruptedException {
        return getJson("drives/" + encPathSegment(driveId) + "?fields=" + enc("id,name"));
    }

    /** Lists accessible Shared Drives: {@code GET /drives}. */
    public List<JsonNode> listDrives() throws IOException, InterruptedException {
        List<JsonNode> out = new ArrayList<>();
        String nextToken = null;
        do {
            StringBuilder path = new StringBuilder("drives?pageSize=")
                    .append(Math.min(100, settings.pageSize()))
                    .append("&fields=").append(enc("nextPageToken,drives(id,name)"));
            if (nextToken != null) {
                path.append("&pageToken=").append(enc(nextToken));
            }
            JsonNode body = getJson(path.toString());
            body.path("drives").forEach(out::add);
            nextToken = text(body, "nextPageToken");
        } while (nextToken != null && !nextToken.isBlank());
        return out;
    }

    /** Retrieves metadata for one file or folder ({@code Optional.empty()} on HTTP 404). */
    public Optional<JsonNode> getFile(String fileId) throws IOException, InterruptedException {
        String path = "files/" + encPathSegment(fileId)
                + "?fields=" + enc(FILE_FIELDS)
                + "&supportsAllDrives=" + settings.includeSharedDrives();
        try {
            return Optional.of(getJson(path));
        } catch (GoogleDriveApiException e) {
            if (e.isNotFound()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** Lists files or folder children matching a Google Drive query {@code q}. */
    public FilesPage listFiles(String query, String pageToken, String driveId)
            throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("files?pageSize=").append(settings.pageSize())
                .append("&fields=").append(enc(FILES_PAGE_FIELDS))
                .append("&supportsAllDrives=").append(settings.includeSharedDrives())
                .append("&includeItemsFromAllDrives=").append(settings.includeSharedDrives());
        if (driveId != null && !driveId.isBlank()) {
            path.append("&corpora=drive&driveId=").append(enc(driveId.strip()));
        } else if (settings.includeSharedDrives()) {
            path.append("&corpora=allDrives");
        }
        if (query != null && !query.isBlank()) {
            path.append("&q=").append(enc(query));
        }
        if (pageToken != null && !pageToken.isBlank()) {
            path.append("&pageToken=").append(enc(pageToken));
        }
        JsonNode body = getJson(path.toString());
        List<JsonNode> files = new ArrayList<>();
        body.path("files").forEach(files::add);
        return new FilesPage(files, text(body, "nextPageToken"));
    }

    /** Acquires the initial Changes API start page token. */
    public String getStartPageToken(String driveId) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("changes/startPageToken?supportsAllDrives=")
                .append(settings.includeSharedDrives());
        if (driveId != null && !driveId.isBlank()) {
            path.append("&driveId=").append(enc(driveId.strip()));
        }
        JsonNode body = getJson(path.toString());
        String token = text(body, "startPageToken");
        if (token == null || token.isBlank()) {
            throw new IOException("Google Drive /changes/startPageToken response did not contain 'startPageToken'.");
        }
        return token;
    }

    /** Lists incremental changes starting from {@code pageToken}. */
    public ChangesPage listChanges(String pageToken, String driveId) throws IOException, InterruptedException {
        StringBuilder path = new StringBuilder("changes?pageToken=").append(enc(pageToken))
                .append("&pageSize=").append(settings.pageSize())
                .append("&fields=").append(enc(CHANGES_PAGE_FIELDS))
                .append("&includeRemoved=true")
                .append("&supportsAllDrives=").append(settings.includeSharedDrives())
                .append("&includeItemsFromAllDrives=").append(settings.includeSharedDrives());
        if (driveId != null && !driveId.isBlank()) {
            path.append("&driveId=").append(enc(driveId.strip()));
        }
        JsonNode body = getJson(path.toString());
        List<JsonNode> changes = new ArrayList<>();
        body.path("changes").forEach(changes::add);
        return new ChangesPage(changes, text(body, "nextPageToken"), text(body, "newStartPageToken"));
    }

    /** Retrieves all pages of permissions for a file or folder. */
    public List<JsonNode> listPermissions(String fileId) throws IOException, InterruptedException {
        List<JsonNode> permissions = new ArrayList<>();
        String nextToken = null;
        do {
            StringBuilder path = new StringBuilder("files/").append(encPathSegment(fileId))
                    .append("/permissions?pageSize=100")
                    .append("&fields=").append(enc(PERMISSIONS_FIELDS))
                    .append("&supportsAllDrives=").append(settings.includeSharedDrives());
            if (nextToken != null) {
                path.append("&pageToken=").append(enc(nextToken));
            }
            JsonNode body = getJson(path.toString());
            body.path("permissions").forEach(permissions::add);
            nextToken = text(body, "nextPageToken");
        } while (nextToken != null && !nextToken.isBlank());
        return permissions;
    }

    /**
     * Streams a standard binary file ({@code alt=media}) directly into {@code target}.
     *
     * @return bytes written, or {@code -1} if content exceeds {@code maxBytes} (partial file is deleted)
     */
    public long downloadFile(String fileId, Path target, long maxBytes) throws IOException, InterruptedException {
        String path = "files/" + encPathSegment(fileId)
                + "?alt=media&supportsAllDrives=" + settings.includeSharedDrives();
        return streamToFile(path, "*/*", "GET /files/" + fileId + "?alt=media", target, maxBytes);
    }

    /**
     * Exports a native Google Workspace document ({@code /export}) directly into {@code target}.
     *
     * @return bytes written, or {@code -1} if exported content exceeds {@code maxBytes} (partial file is deleted)
     */
    public long exportFile(String fileId, String exportMimeType, Path target, long maxBytes)
            throws IOException, InterruptedException {
        String path = "files/" + encPathSegment(fileId) + "/export?mimeType=" + enc(exportMimeType);
        return streamToFile(path, exportMimeType, "GET /files/" + fileId + "/export", target, maxBytes);
    }

    // ------------------------------------------------------------------ HTTP plumbing

    JsonNode getJson(String relativePath) throws IOException, InterruptedException {
        URI uri = resolve(relativePath);
        HttpResponse<String> response = authorizedRequest(uri, "application/json",
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        recordServerTime(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw toApiException(response.statusCode(), "GET " + uri.getRawPath(), response.body());
        }
        return parseJson(response.body());
    }

    private long streamToFile(String relativePath, String accept, String what, Path target, long maxBytes)
            throws IOException, InterruptedException {
        URI uri = resolve(relativePath);
        for (int attempt = 0; ; attempt++) {
            HttpResponse<InputStream> response = authorizedRequest(uri, accept, HttpResponse.BodyHandlers.ofInputStream());
            recordServerTime(response);
            try (InputStream in = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    String errBody = new String(in.readNBytes(8192), StandardCharsets.UTF_8);
                    GoogleDriveApiException apiEx = toApiException(response.statusCode(), what, errBody);
                    if (apiEx.isRateLimited() && attempt < settings.maxRetries()) {
                        Duration wait = waitAfter(response, attempt);
                        throttledUntil = later(throttledUntil, clock.instant().plus(wait));
                        log.info("Google Drive stream {} rate-limited (HTTP {}); pausing for {} ms (attempt {} of {}).",
                                what, response.statusCode(), wait.toMillis(), attempt + 1, settings.maxRetries());
                        continue;
                    }
                    throw apiEx;
                }
                if (maxBytes > 0) {
                    long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                    if (declared > maxBytes) {
                        return -1L;
                    }
                }
                long written = 0L;
                byte[] buffer = new byte[64 * 1024];
                try (OutputStream out = Files.newOutputStream(target)) {
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        written += n;
                        if (maxBytes > 0 && written > maxBytes) {
                            out.close();
                            Files.deleteIfExists(target);
                            return -1L;
                        }
                        out.write(buffer, 0, n);
                    }
                }
                return written;
            }
        }
    }

    URI resolve(String relativePath) throws IOException {
        URI uri = apiBase.resolve(relativePath);
        if (!Objects.equals(uri.getScheme(), apiBase.getScheme())
                || !Objects.equals(uri.getAuthority(), apiBase.getAuthority())) {
            throw new IOException("Refusing to send Google Drive credentials to unexpected host "
                    + uri.getScheme() + "://" + uri.getAuthority());
        }
        return uri;
    }

    private <T> HttpResponse<T> authorizedRequest(URI uri, String accept, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        String used = accessToken();
        HttpResponse<T> response = sendWithRetries(buildGet(uri, used, accept), handler);
        if (response.statusCode() == 401 && settings.authType() != GoogleDriveProperties.AuthType.BEARER) {
            discard(response);
            invalidateToken(used);
            response = sendWithRetries(buildGet(uri, accessToken(), accept), handler);
        }
        return response;
    }

    private HttpRequest buildGet(URI uri, String token, String accept) {
        return HttpRequest.newBuilder(uri)
                .timeout(settings.timeout())
                .header("Authorization", "Bearer " + token)
                .header("Accept", accept)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
    }

    private <T> HttpResponse<T> sendWithRetries(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        for (int attempt = 0; ; attempt++) {
            awaitThrottle();
            try {
                HttpResponse<T> response = http.send(request, handler);
                int status = response.statusCode();
                if (attempt < settings.maxRetries() && isRetryableResponse(status, response)) {
                    Duration wait = waitAfter(response, attempt);
                    throttledUntil = later(throttledUntil, clock.instant().plus(wait));
                    log.info("Google Drive API answered HTTP {}; pausing requests for {} ms (attempt {} of {}).",
                            status, wait.toMillis(), attempt + 1, settings.maxRetries());
                    discard(response);
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= settings.maxRetries()) {
                    throw new IOException(request.method() + " " + request.uri().getHost() + request.uri().getRawPath()
                            + " failed after " + (attempt + 1) + " attempt(s): " + e.getMessage(), e);
                }
                Thread.sleep(backoff(attempt).toMillis());
            }
        }
    }

    /**
     * Checks whether a response should be retried. Note: for HTTP 403 on streaming responses, the body is not peeked
     * here (it is inspected on non-2xx); for String JSON responses, Drive 403 rate-limit errors are retried cleanly.
     */
    private boolean isRetryableResponse(int status, HttpResponse<?> response) {
        if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
            return true;
        }
        if (status == 403 && response.body() instanceof String bodyStr) {
            GoogleDriveApiException parsed = toApiException(status, "Drive API", bodyStr);
            return parsed.isRateLimited();
        }
        return false;
    }

    private void awaitThrottle() throws InterruptedException {
        Duration wait = Duration.between(clock.instant(), throttledUntil);
        if (!wait.isNegative() && !wait.isZero()) {
            Thread.sleep(wait.toMillis());
        }
    }

    Duration waitAfter(HttpResponse<?> response, int attempt) {
        Duration backoff = backoff(attempt);
        return retryAfter(response)
                .map(d -> d.plusSeconds(1))
                .filter(d -> d.compareTo(backoff) > 0)
                .orElse(backoff);
    }

    Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").flatMap(value -> {
            String trimmed = value.strip();
            try {
                return Optional.of(Duration.ofSeconds(Math.max(0L, Long.parseLong(trimmed))));
            } catch (NumberFormatException ignored) {
                try {
                    Instant at = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    Duration d = Duration.between(clock.instant(), at);
                    return Optional.of(d.isNegative() ? Duration.ZERO : d);
                } catch (DateTimeParseException ignoredDate) {
                    return Optional.empty();
                }
            }
        }).map(d -> d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d);
    }

    Duration backoff(int attempt) {
        long base = settings.initialBackoffMs() * (1L << Math.min(attempt, 16));
        long capped = Math.min(base, MAX_BACKOFF.toMillis());
        long jitter = (long) (capped * 0.25 * (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0));
        return Duration.ofMillis(Math.max(1L, capped + jitter));
    }

    private void recordServerTime(HttpResponse<?> response) {
        response.headers().firstValue("Date").ifPresent(dateHeader -> {
            try {
                lastServerTime = ZonedDateTime.parse(dateHeader.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (DateTimeParseException ignored) {
            }
        });
    }

    private GoogleDriveApiException toApiException(int status, String what, String body) {
        String reason = null;
        String domain = null;
        String message = body == null ? "" : body.strip();
        try {
            JsonNode err = parseJson(body).path("error");
            if (err.isObject()) {
                message = err.path("message").asText(message);
                JsonNode errors = err.path("errors");
                if (errors.isArray() && !errors.isEmpty()) {
                    reason = text(errors.get(0), "reason");
                    domain = text(errors.get(0), "domain");
                }
                if (reason == null) {
                    reason = text(err, "status");
                }
            }
        } catch (IOException ignored) {
        }
        if (message.length() > 300) {
            message = message.substring(0, 300) + "...";
        }
        return new GoogleDriveApiException(
                status,
                reason,
                domain,
                what + " answered HTTP " + status + (reason != null ? " (" + reason + ")" : "") + ": " + firstLine(message)
        );
    }

    private JsonNode parseJson(String body) throws IOException {
        return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
    }

    private static void discard(HttpResponse<?> response) {
        if (response.body() instanceof InputStream in) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String encPathSegment(String value) {
        return URLEncoder.encode(value == null ? "" : value.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
