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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The MFWS client: logins, credentials on requests, token renewal, retries, logout, downloads. */
class MFilesClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private MockMFiles mfiles;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws Exception {
        mfiles = new MockMFiles();
        mfiles.session();
    }

    @AfterEach
    void tearDown() throws Exception {
        mfiles.close();
    }

    private MFilesClient client(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("url", mfiles.url(), "vaultGuid", MockMFiles.VAULT_KEY,
                "username", "crawler", "password", "s3cret", "maxRetries", "2"));
        config.putAll(extra);
        MFilesProperties settings = MFilesProperties.fromConfiguration(config);
        return MFilesRepositoryConnector.newClient(settings, Duration.ofSeconds(5), settings.maxRetries());
    }

    private RecordedRequest request(String key) {
        for (int i = 0; i < mfiles.requests.size(); i++) {
            if (mfiles.requests.get(i).equals(key)) {
                return mfiles.recorded.get(i);
            }
        }
        throw new AssertionError("no request " + key + " in " + mfiles.requests);
    }

    @Test
    void tokenLoginIsVaultScopedAndCarriesASessionId() throws Exception {
        try (MFilesClient client = client(Map.of("domain", "CORP"))) {
            assertEquals("Contracts", client.getVault().path("Name").asText());
        }
        JsonNode body = MAPPER.readTree(request("POST /server/authenticationtokens").getBody().readUtf8());
        assertEquals("crawler", body.path("Username").asText());
        assertEquals("s3cret", body.path("Password").asText());
        assertEquals("CORP", body.path("Domain").asText());
        assertEquals(MockMFiles.VAULT, body.path("VaultGuid").asText(), "braces added");
        assertFalse(body.path("SessionID").asText().isBlank(), "a session id makes DELETE /session possible");

        RecordedRequest vault = request("GET /session/vault");
        assertEquals("tok-1", vault.getHeader("X-Authentication"));
        assertEquals(MockMFiles.VAULT, vault.getHeader("X-Vault"));
        assertEquals("tok-1", request("DELETE /session").getHeader("X-Authentication"));
    }

    @Test
    void bareStringTokenIsAccepted() throws Exception {
        mfiles.body("POST /server/authenticationtokens", "application/json", "\"tok-bare\"");
        try (MFilesClient client = client(Map.of())) {
            client.getVault();
        }
        assertEquals("tok-bare", request("GET /session/vault").getHeader("X-Authentication"));
    }

    @Test
    void emptyTokenIsAnError() {
        mfiles.body("POST /server/authenticationtokens", "application/json", "{\"Value\":\"\"}");
        IOException e = assertThrows(IOException.class, () -> client(Map.of()).login());
        assertTrue(e.getMessage().contains("empty token"));
    }

    @Test
    void emptyCredentialsAreRefusedBeforeCallingTheServer() {
        MFilesProperties settings = MFilesProperties.fromConfiguration(Map.of("url", mfiles.url(), "vaultGuid", MockMFiles.VAULT_KEY));
        MFilesClient client = MFilesRepositoryConnector.newClient(settings, Duration.ofSeconds(5), 0);
        assertThrows(IOException.class, client::login);
        assertTrue(mfiles.requests.isEmpty(), "an anonymous token must never be requested");
    }

    @Test
    void expiredTokenIsRenewedOnce() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        mfiles.routes.put("POST /server/authenticationtokens", r -> new MockResponse().setResponseCode(200)
                .setBody("{\"Value\":\"tok-" + logins.incrementAndGet() + "\"}"));
        mfiles.routes.put("GET /session/vault", r -> "tok-1".equals(r.getHeader("X-Authentication"))
                ? new MockResponse().setResponseCode(403).setBody("{\"Message\":\"Authentication token expired\"}")
                : new MockResponse().setResponseCode(200).setBody(MockMFiles.fixture("vault.json")));
        try (MFilesClient client = client(Map.of())) {
            assertEquals("Contracts", client.getVault().path("Name").asText());
        }
        assertEquals(2, logins.get());
    }

    @Test
    void forbiddenAfterRenewalNamesTheVault() {
        mfiles.status("GET /structure/objecttypes", 403);
        MFilesApiException e = assertThrows(MFilesApiException.class, () -> client(Map.of()).getObjectTypes());
        assertEquals(403, e.status());
        assertTrue(e.getMessage().contains(MockMFiles.VAULT));
        assertEquals(2, mfiles.count("POST /server/authenticationtokens"));
        assertFalse(e.getMessage().contains("s3cret"));
    }

    @Test
    void basicModeSendsCredentialHeadersAndNeverLogsIn() throws Exception {
        try (MFilesClient client = client(Map.of("authType", "basic", "domain", "CORP"))) {
            client.getVault();
        }
        RecordedRequest vault = request("GET /session/vault");
        assertEquals("CORP\\crawler", vault.getHeader("X-Username"));
        assertEquals("s3cret", vault.getHeader("X-Password"));
        assertEquals(MockMFiles.VAULT, vault.getHeader("X-Vault"));
        assertEquals(0, mfiles.count("POST /server/authenticationtokens"));
        assertEquals(0, mfiles.count("DELETE /session"));
    }

    @Test
    void oauth2ModeSendsTheBearerToken() throws Exception {
        try (MFilesClient client = client(Map.of("authType", "oauth2", "oauth2AccessToken", "eyJ.test"))) {
            client.getVault();
        }
        RecordedRequest vault = request("GET /session/vault");
        assertEquals("Bearer eyJ.test", vault.getHeader("Authorization"));
        assertEquals(MockMFiles.VAULT, vault.getHeader("X-Vault"));
        assertNull(vault.getHeader("X-Authentication"));
    }

    @Test
    void oauth2ClientCredentialsGrantFetchesAToken() throws Exception {
        mfiles.body("POST /oauth/token", "application/json", "{\"access_token\":\"granted\",\"expires_in\":3600}");
        try (MFilesClient client = client(Map.of("authType", "oauth2", "oauth2TokenUrl", mfiles.url() + "/oauth/token",
                "oauth2ClientId", "oc", "oauth2ClientSecret", "shh"))) {
            client.getVault();
            client.getObjectTypes();
        }
        assertEquals("Bearer granted", request("GET /structure/objecttypes").getHeader("Authorization"));
        assertEquals(1, mfiles.count("POST /oauth/token"), "cached until it expires");
        String form = request("POST /oauth/token").getBody().readUtf8();
        assertTrue(form.contains("grant_type=client_credentials") && form.contains("client_id=oc"));
    }

    @Test
    void serverErrorsAreRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        mfiles.routes.put("GET /structure/objecttypes", r -> calls.incrementAndGet() < 3
                ? new MockResponse().setResponseCode(503)
                : new MockResponse().setResponseCode(200).setBody(MockMFiles.fixture("object-types.json")));
        assertEquals(2, client(Map.of()).getObjectTypes().size());
        assertEquals(3, calls.get());
    }

    @Test
    void logoutFallsBackToTheMethodOverride() throws Exception {
        mfiles.status("DELETE /session", 405);
        mfiles.status("POST /session?_method=DELETE", 204);
        try (MFilesClient client = client(Map.of())) {
            client.getVault();
        }
        assertEquals(1, mfiles.count("POST /session?_method=DELETE"));
    }

    @Test
    void searchReadsItemsAndMoreResults() throws Exception {
        mfiles.body("GET /objects?q=x&limit=1", "application/json", "{\"Items\":[{\"Title\":\"a\"}],\"MoreResults\":true}");
        MFilesClient.Page page = client(Map.of()).search("q=x&limit=1");
        assertEquals(1, page.items().size());
        assertTrue(page.moreResults());
    }

    @Test
    void missingPermissionsResourceIsEmptyNotAnError() throws Exception {
        assertTrue(client(Map.of()).getPermissions(0, 1, -1).isEmpty());
        assertTrue(mfiles.requests.contains("GET /objects/0/1/latest/permissions"));
    }

    @Test
    void downloadStreamsToFileAndStopsAtTheLimit() throws Exception {
        mfiles.body("GET /objects/0/1/2/files/3/content", "application/pdf", "0123456789");
        MFilesClient client = client(Map.of());
        Path target = dir.resolve("f.bin");
        assertEquals(10, client.downloadFile(0, 1, 2, 3, target, 100));
        assertEquals("0123456789", Files.readString(target));
        Path big = dir.resolve("big.bin");
        assertEquals(-1, client.downloadFile(0, 1, 2, 3, big, 5));
        assertFalse(Files.exists(big) && Files.size(big) > 5);
    }

    @Test
    void downloadUrlRefusesOtherHosts() throws Exception {
        MFilesClient client = client(Map.of());
        IOException e = assertThrows(IOException.class,
                () -> client.downloadUrl("https://attacker.example.com/x", dir.resolve("x"), 100));
        assertTrue(e.getMessage().contains("attacker.example.com"));
        mfiles.body("GET /objects/0/1/2/files/3/content", "application/pdf", "ok");
        assertEquals(2, client.downloadUrl("objects/0/1/2/files/3/content", dir.resolve("y"), 100));
        assertTrue(List.copyOf(mfiles.requests).stream().noneMatch(r -> r.contains("attacker")));
    }
}
