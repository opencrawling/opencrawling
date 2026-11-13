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
package org.opencrawling.cli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.sdk.OpenCrawlingClient;
import org.opencrawling.sdk.auth.ClientCredentialsTokenProvider;
import org.opencrawling.sdk.auth.OAuth2Client;
import org.opencrawling.sdk.auth.RefreshTokenProvider;
import org.opencrawling.sdk.auth.TokenProvider;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.function.UnaryOperator;

/**
 * Service for managing local CLI configurations in ~/.oc/config.json
 * and creating OpenCrawlingClient instances.
 *
 * <p>How a client authenticates, first match wins:
 * <ol>
 *   <li>an explicit bearer token ({@code --token}, {@code --bearer-token});</li>
 *   <li>client credentials from the environment ({@code OPENCRAWLING_CLIENT_ID} and
 *       {@code OPENCRAWLING_CLIENT_SECRET}, optionally {@code OPENCRAWLING_TOKEN_URL} and
 *       {@code OPENCRAWLING_CLIENT_SCOPE}), for automation;</li>
 *   <li>the sign-in from {@code oc auth login} for that server;</li>
 *   <li>the bearer token saved with {@code oc config set}.</li>
 * </ol>
 * The directory is {@code ~/.oc}, or {@code oc.config.dir} / {@code OC_CONFIG_DIR} when set.
 */
public class CliConfigService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static volatile UnaryOperator<String> environment = System::getenv;

    /** Replaces how environment variables are read; for tests. {@code null} restores {@link System#getenv}. */
    public static void setEnvironment(UnaryOperator<String> reader) {
        environment = reader != null ? reader : System::getenv;
    }

    public static Path configDir() {
        String override = System.getProperty("oc.config.dir");
        if (override == null || override.isBlank()) {
            override = environment.apply("OC_CONFIG_DIR");
        }
        return override != null && !override.isBlank()
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".oc");
    }

    private static Path configFile() {
        return configDir().resolve("config.json");
    }

    public static CredentialStore credentials() {
        return new CredentialStore(configDir());
    }

    public static CliConfig loadConfig() {
        Path file = configFile();
        if (!Files.exists(file)) {
            return CliConfig.defaultConfig();
        }
        try {
            return OBJECT_MAPPER.readValue(file.toFile(), CliConfig.class);
        } catch (IOException e) {
            return CliConfig.defaultConfig();
        }
    }

    public static void saveConfig(CliConfig config) throws IOException {
        Files.createDirectories(configDir());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(configFile().toFile(), config);
    }

    /** The server to talk to: the override if given, else the configured one. */
    public static String serverUrl(String overrideUrl) {
        return overrideUrl != null && !overrideUrl.isBlank() ? overrideUrl : loadConfig().serverUrl();
    }

    public static OpenCrawlingClient createClient(String overrideUrl, String overrideApiKey) {
        return createClient(overrideUrl, overrideApiKey, null);
    }

    public static OpenCrawlingClient createClient(String overrideUrl, String overrideApiKey, String overrideBearerToken) {
        CliConfig config = loadConfig();
        String baseUrl = overrideUrl != null && !overrideUrl.isBlank() ? overrideUrl : config.serverUrl();
        String apiKey = overrideApiKey != null && !overrideApiKey.isBlank() ? overrideApiKey : config.apiKey();

        OpenCrawlingClient.Builder builder = OpenCrawlingClient.builder()
                .baseUrl(baseUrl);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }
        builder.tokenProvider(tokenProvider(baseUrl, overrideBearerToken, config));
        return builder.build();
    }

    static TokenProvider tokenProvider(String baseUrl, String overrideBearerToken, CliConfig config) {
        if (overrideBearerToken != null && !overrideBearerToken.isBlank()) {
            return TokenProvider.of(overrideBearerToken);
        }
        String clientId = environment.apply("OPENCRAWLING_CLIENT_ID");
        if (clientId != null && !clientId.isBlank()) {
            String secret = environment.apply("OPENCRAWLING_CLIENT_SECRET");
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException("OPENCRAWLING_CLIENT_ID is set but OPENCRAWLING_CLIENT_SECRET is not.");
            }
            String tokenUrl = environment.apply("OPENCRAWLING_TOKEN_URL");
            OAuth2Client oauth = new OAuth2Client();
            if (tokenUrl == null || tokenUrl.isBlank()) {
                String issuer = issuerOf(baseUrl);
                if (issuer == null) {
                    throw new IllegalStateException("Set OPENCRAWLING_TOKEN_URL: the runtime at " + baseUrl
                            + " does not name an identity provider.");
                }
                tokenUrl = oauth.discover(issuer).tokenEndpoint();
            }
            return new ClientCredentialsTokenProvider(oauth, tokenUrl, clientId, secret,
                    environment.apply("OPENCRAWLING_CLIENT_SCOPE"), null, null);
        }
        CredentialStore store = credentials();
        CredentialStore.Login login = store.find(baseUrl);
        if (login != null) {
            return new RefreshTokenProvider(new OAuth2Client(), login.tokenEndpoint(), login.clientId(),
                    new RefreshTokenProvider.Tokens(login.accessToken(), login.refreshToken(), login.expiresAt()),
                    renewed -> {
                        try {
                            store.save(login.withTokens(renewed.accessToken(), renewed.refreshToken(), renewed.expiresAt()));
                        } catch (IOException e) {
                            System.err.println("Warning: could not save the renewed sign-in: " + e.getMessage());
                        }
                    }, null);
        }
        return TokenProvider.of(config.bearerToken());
    }

    /**
     * The issuer the runtime's admin UI signs in with ({@code GET /api/auth/config}, public), or null when the runtime
     * names none or is older than that endpoint.
     */
    public static String issuerOf(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(HttpRequest.newBuilder(URI.create(base + "/api/auth/config")).timeout(Duration.ofSeconds(15))
                            .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonNode issuer = OBJECT_MAPPER.readTree(response.body()).path("oidc").path("issuer");
            return issuer.isTextual() && !issuer.asText().isBlank() ? issuer.asText() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
