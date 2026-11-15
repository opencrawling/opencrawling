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
package org.opencrawling.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.cli.commands.AuthCommand;
import org.opencrawling.cli.config.CliConfigService;
import org.opencrawling.cli.config.CredentialStore;
import org.opencrawling.sdk.OpenCrawlingClient;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthCommandTest {

    @TempDir
    Path configDir;

    private HttpServer server;
    private String base;
    private final List<String> seen = new CopyOnWriteArrayList<>(); // "path auth body"
    private final Deque<String[]> tokenResponses = new ArrayDeque<>();
    private volatile String issuerInConfig;

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws IOException {
        System.setProperty("oc.config.dir", configDir.toString());
        CliConfigService.setEnvironment(name -> null);
        AuthCommand.setSleeper(seconds -> {});
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        issuerInConfig = base + "/realms/test";
        server.createContext("/api/auth/config", exchange -> reply(exchange, 200, issuerInConfig == null
                ? "{\"mode\":\"none\",\"protectApi\":false,\"oidc\":null}"
                : "{\"mode\":\"jwt\",\"protectApi\":true,\"oidc\":{\"issuer\":\"" + issuerInConfig
                        + "\",\"clientId\":\"opencrawling-admin-ui\",\"scope\":\"openid profile\"}}"));
        server.createContext("/realms/test/.well-known/openid-configuration", exchange -> reply(exchange, 200, """
                {"issuer":"%s/realms/test","token_endpoint":"%s/token","device_authorization_endpoint":"%s/device",
                 "revocation_endpoint":"%s/revoke"}""".formatted(base, base, base, base)));
        server.createContext("/device", exchange -> {
            record(exchange);
            reply(exchange, 200, "{\"device_code\":\"dc\",\"user_code\":\"WDJB-MJHT\",\"verification_uri\":\"" + base
                    + "/device-ui\",\"verification_uri_complete\":\"" + base + "/device-ui?code=WDJB-MJHT\",\"expires_in\":600,\"interval\":5}");
        });
        server.createContext("/token", exchange -> {
            record(exchange);
            String[] next;
            synchronized (tokenResponses) {
                next = tokenResponses.isEmpty() ? new String[] {"400", "{\"error\":\"invalid_grant\"}"} : tokenResponses.poll();
            }
            reply(exchange, Integer.parseInt(next[0]), next[1]);
        });
        server.createContext("/revoke", exchange -> {
            record(exchange);
            reply(exchange, 200, "");
        });
        server.createContext("/api/auth/me", exchange -> {
            record(exchange);
            String auth = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
            if (auth.equals("Bearer user-access") || auth.equals("Bearer user-access-2")) {
                reply(exchange, 200, "{\"authenticated\":true,\"principal\":\"hr_admin\",\"groups\":[\"hr-team\"],"
                        + "\"managementAuthorization\":true,\"admin\":false,\"canCreateJobs\":true}");
            } else if (auth.equals("Bearer robot-access")) {
                reply(exchange, 200, "{\"authenticated\":true,\"principal\":\"service-account-ci\",\"groups\":[]}");
            } else {
                reply(exchange, 401, "");
            }
        });
        server.start();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void stop() {
        System.setOut(originalOut);
        System.setErr(originalErr);
        System.clearProperty("oc.config.dir");
        CliConfigService.setEnvironment(null);
        AuthCommand.setSleeper(null);
        server.stop(0);
    }

    private int oc(String... args) {
        return new CommandLine(new OpenCrawlingCliCommand()).execute(args);
    }

    private void respondToken(int status, String body) {
        synchronized (tokenResponses) {
            tokenResponses.add(new String[] {String.valueOf(status), body});
        }
    }

    private List<String> seenAt(String path) {
        return seen.stream().filter(line -> line.startsWith(path + " ")).toList();
    }

    @Test
    void loginUsesTheDeviceCodeFromTheRuntimesIdentityProviderAndKeepsTheTokensPrivate() throws Exception {
        respondToken(400, "{\"error\":\"authorization_pending\"}");
        respondToken(200, "{\"access_token\":\"user-access\",\"refresh_token\":\"user-refresh\",\"expires_in\":300}");

        assertEquals(0, oc("auth", "login", "--server-url", base));
        String output = out.toString(StandardCharsets.UTF_8);
        assertContains(output, base + "/device-ui?code=WDJB-MJHT", "WDJB-MJHT", "Signed in to " + base + " as hr_admin");
        assertFalse(output.contains("user-access") || output.contains("user-refresh"), "tokens must never be printed");
        assertContains(seenAt("/device").getFirst(), "client_id=opencrawling-cli");

        Path file = configDir.resolve("credentials.json");
        assertContains(Files.readString(file), "user-refresh");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
    }

    @Test
    void theSignInIsUsedForThatServerOnly() throws Exception {
        CliConfigService.credentials().save(login("user-access", "user-refresh", 300));

        try (OpenCrawlingClient client = CliConfigService.createClient(base + "/", null, null)) {
            assertEquals("hr_admin", client.auth().me().principal());
        }
        assertContains(seenAt("/api/auth/me").getLast(), "Bearer user-access");

        try (OpenCrawlingClient other = CliConfigService.createClient("http://localhost:" + server.getAddress().getPort(), null, null)) {
            try {
                other.auth().me();
            } catch (RuntimeException expected) {
                // 401: no token was sent to a server the sign-in was not made for
            }
        }
        assertContains(seenAt("/api/auth/me").getLast(), " null ");
    }

    @Test
    void anExpiredTokenIsRenewedAndTheRenewalSaved() throws Exception {
        CliConfigService.credentials().save(login("old-access", "user-refresh", -10));
        respondToken(200, "{\"access_token\":\"user-access-2\",\"refresh_token\":\"user-refresh-2\",\"expires_in\":300}");

        assertEquals(0, oc("auth", "status", "--server-url", base));
        String output = out.toString(StandardCharsets.UTF_8);
        assertContains(output, "User:   hr_admin", "Groups: hr-team", "Admin:  no, may create jobs: yes");
        assertFalse(output.contains("user-access-2"));
        assertContains(seenAt("/token").getFirst(), "grant_type=refresh_token", "refresh_token=user-refresh");
        assertEquals("user-refresh-2", CliConfigService.credentials().find(base).refreshToken());
    }

    @Test
    void anExpiredSessionTellsTheUserToSignInAgain() throws Exception {
        CliConfigService.credentials().save(login("old-access", "dead-refresh", -10));
        assertEquals(1, oc("auth", "status", "--server-url", base));
        assertContains(out.toString(StandardCharsets.UTF_8), "no longer works", "oc auth login");
    }

    @Test
    void logoutRevokesTheRefreshTokenAndForgetsTheSignIn() throws Exception {
        CliConfigService.credentials().save(login("user-access", "user-refresh", 300));

        assertEquals(0, oc("auth", "logout", "--server-url", base));
        assertContains(seenAt("/revoke").getFirst(), "token=user-refresh", "token_type_hint=refresh_token");
        assertNull(CliConfigService.credentials().find(base));
        assertFalse(Files.exists(configDir.resolve("credentials.json")));
    }

    @Test
    void automationUsesClientCredentialsFromTheEnvironment() throws Exception {
        Map<String, String> env = Map.of("OPENCRAWLING_CLIENT_ID", "ci-robot", "OPENCRAWLING_CLIENT_SECRET", "from-ci-secrets");
        CliConfigService.setEnvironment(env::get);
        respondToken(200, "{\"access_token\":\"robot-access\",\"expires_in\":300}");

        try (OpenCrawlingClient client = CliConfigService.createClient(base, null, null)) {
            assertEquals("service-account-ci", client.auth().me().principal());
        }
        assertContains(seenAt("/token").getFirst(), "grant_type=client_credentials", "Basic ");

        CliConfigService.setEnvironment(Map.of("OPENCRAWLING_CLIENT_ID", "ci-robot")::get);
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> CliConfigService.createClient(base, null, null));
        assertContains(missing.getMessage(), "OPENCRAWLING_CLIENT_SECRET");
    }

    @Test
    void loginExplainsWhenTheRuntimeNeedsNoSignIn() {
        issuerInConfig = null;
        assertEquals(1, oc("auth", "login", "--server-url", base));
        assertContains(out.toString(StandardCharsets.UTF_8), "names no identity provider");
    }

    private static void assertContains(String actual, String... expected) {
        for (String part : expected) {
            assertTrue(actual != null && actual.contains(part), "expected <" + actual + "> to contain <" + part + ">");
        }
    }

    private CredentialStore.Login login(String access, String refresh, long expiresInSeconds) {
        return new CredentialStore.Login(base, base + "/realms/test", "opencrawling-cli", base + "/token",
                base + "/revoke", access, refresh, java.time.Instant.now().plusSeconds(expiresInSeconds));
    }

    private void record(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(exchange.getRequestURI().getPath() + " " + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
