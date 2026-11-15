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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.opencrawling.core.document.RepositoryDocument;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A read-only probe against a real M-Files Web Service. It never writes to the vault. It records the payloads the public
 * MFWS reference leaves open — the authentication response, the search response with {@code p21>=}, and above all the
 * permissions payload — and then runs a connector scan capped at {@code MFILES_MAX_DOCUMENTS}.
 *
 * <pre>
 * MFILES_LIVE_PROBE=1 MFILES_URL=https://host/REST MFILES_VAULT={GUID} MFILES_USER=… MFILES_PASSWORD=… \
 * mvn test -pl oc-mfiles-repository-connector -Dtest=MFilesLiveProbeTest -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 * Optional: {@code MFILES_AUTH_TYPE}, {@code MFILES_DOMAIN}, {@code MFILES_OBJECT_TYPE} (default 0),
 * {@code MFILES_MAX_DOCUMENTS} (default 5), {@code MFILES_PROBE_REPORT} (default {@code target/mfiles-live-probe.txt}).
 * The report holds vault payloads, so keep it out of source control.
 */
@EnabledIfEnvironmentVariable(named = "MFILES_LIVE_PROBE", matches = "1")
class MFilesLiveProbeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static MFilesProperties settings() {
        Map<String, String> config = new HashMap<>();
        config.put("url", env("MFILES_URL", null));
        config.put("vaultGuid", env("MFILES_VAULT", null));
        config.put("authType", env("MFILES_AUTH_TYPE", "token"));
        config.put("username", env("MFILES_USER", null));
        config.put("password", env("MFILES_PASSWORD", null));
        config.put("domain", env("MFILES_DOMAIN", null));
        config.put("objectType", env("MFILES_OBJECT_TYPE", "0"));
        config.put("maxDocuments", env("MFILES_MAX_DOCUMENTS", "5"));
        config.put("batchSize", "10");
        config.put("parallelism", "1");
        config.values().removeIf(v -> v == null);
        return MFilesProperties.fromConfiguration(config);
    }

    @Test
    void readOnlyProbe() throws Exception {
        MFilesProperties settings = settings();
        assertTrue(settings.validate().isEmpty(), String.join(" ", settings.validate()));
        Path report = Path.of(env("MFILES_PROBE_REPORT", "target/mfiles-live-probe.txt"));
        Files.createDirectories(report.toAbsolutePath().getParent());
        try (PrintStream out = new PrintStream(Files.newOutputStream(report), true, StandardCharsets.UTF_8);
             MFilesClient client = MFilesRepositoryConnector.newClient(settings, Duration.ofSeconds(60), 1)) {
            client.login();
            JsonNode vault = client.getVault().deepCopy();
            if (vault instanceof com.fasterxml.jackson.databind.node.ObjectNode object && object.has("Authentication")) {
                object.put("Authentication", "<redacted>"); // a vault-scoped token: never written to the report
            }
            out.println("== GET /session/vault\n" + vault.toPrettyString());
            out.println("== GET /structure/objecttypes: " + MFilesClient.items(client.getObjectTypes()).size() + " object types");
            out.println("== GET /structure/properties: " + MFilesClient.items(client.getPropertyDefinitions()).size()
                    + " property definitions");

            String query = (settings.objectType() >= 0 ? "o=" + settings.objectType() + "&" : "")
                    + "d=include&p21%3E=" + MFilesClient.enc("2000-01-01T00:00:00Z") + "&limit=3";
            MFilesClient.Page page = client.search(query);
            out.println("== GET /objects?" + query + " -> " + page.items().size() + " items, MoreResults=" + page.moreResults());
            for (JsonNode object : page.items()) {
                JsonNode objVer = object.path("ObjVer");
                int type = objVer.path("Type").asInt();
                int id = objVer.path("ID").asInt();
                int version = objVer.path("Version").asInt();
                out.println("\n== ObjectVersion " + type + "/" + id + "/" + version + "\n" + object.toPrettyString());
                out.println("== properties: " + client.getProperties(type, id, version).size() + " values");
                Optional<JsonNode> permissions = client.getPermissions(type, id, version);
                out.println("== GET /objects/" + type + "/" + id + "/" + version + "/permissions -> "
                        + permissions.map(JsonNode::toPrettyString).orElse("not offered by this server (404/405/501)"));
                MFilesSecurityMapper.Mapped mapped = new MFilesSecurityMapper(settings, null).fromPermissions(permissions);
                out.println("== mapped security: acl='" + mapped.acl() + "' rules=" + mapped.security().permissions());
            }
        }

        // a capped scan through the connector itself; content is size-checked and discarded
        List<RepositoryDocument> documents = new MFilesRepositoryConnector(settings).scan("default").collectList().block();
        assertNotNull(documents);
        try (PrintStream out = new PrintStream(Files.newOutputStream(report, java.nio.file.StandardOpenOption.APPEND), true,
                StandardCharsets.UTF_8)) {
            out.println("\n== connector scan: " + documents.size() + " documents");
            for (RepositoryDocument document : documents) {
                long bytes = -1;
                if (document.contentStream() != null) {
                    try (InputStream in = document.contentStream()) {
                        bytes = in.transferTo(java.io.OutputStream.nullOutputStream());
                    }
                }
                out.println(document.action() + " " + document.id() + " bytes=" + bytes + " acl='" + document.acl() + "' "
                        + MAPPER.writeValueAsString(document.metadata()));
            }
        }
        System.out.println("M-Files live probe report: " + report.toAbsolutePath());
    }
}
