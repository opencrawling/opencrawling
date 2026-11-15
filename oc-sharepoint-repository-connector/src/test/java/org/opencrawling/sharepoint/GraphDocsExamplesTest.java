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
package org.opencrawling.sharepoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Microsoft's own example payloads, from the Microsoft Graph documentation, through the connector's code. Runs only when
 * {@code GRAPH_DOCS_EXAMPLES} points at the output of {@code graph-mock/conformance/fetch_doc_examples.py} (the examples
 * are Microsoft's documentation and are not stored in this repository); {@code scripts/test-sharepoint-repository-connector.sh}
 * fetches them at a pinned commit.
 */
@EnabledIfEnvironmentVariable(named = "GRAPH_DOCS_EXAMPLES", matches = ".+")
class GraphDocsExamplesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path EXAMPLES = Path.of(System.getenv().getOrDefault("GRAPH_DOCS_EXAMPLES", "."));

    @TempDir
    Path state;

    private static List<JsonNode> examples(String kind, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(EXAMPLES.resolve(kind))) {
            List<JsonNode> out = new ArrayList<>();
            for (Path f : files.filter(f -> f.getFileName().toString().startsWith(prefix)).sorted().toList()) {
                out.add(MAPPER.readTree(f.toFile()));
            }
            return out;
        }
    }

    /** Every permission object in an example body: a collection's values, or the body itself. */
    private static List<JsonNode> permissions(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (body.path("value").isArray()) {
            body.path("value").forEach(out::add);
        } else if (body.isObject()) {
            out.add(body);
        }
        return out.stream().filter(p -> p.has("roles")).toList();
    }

    private static void collect(JsonNode node, String key, Set<String> into) {
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getKey().equals(key) && e.getValue().isTextual()) {
                    into.add(e.getValue().asText().toLowerCase(Locale.ROOT));
                }
                collect(e.getValue(), key, into);
            });
        } else if (node.isArray()) {
            node.forEach(n -> collect(n, key, into));
        }
    }

    @Test
    void documentedPermissionsMapToIdentitiesNeverToDisplayNames() throws Exception {
        List<JsonNode> documented = new ArrayList<>();
        for (String page : List.of("driveitem-list-permissions", "permission-get", "driveitem-invite", "driveitem-createlink")) {
            examples("exchanges", page).forEach(e -> documented.addAll(permissions(e.path("body"))));
        }
        examples("resources", "permission").forEach(e -> documented.addAll(permissions(e.path("body"))));
        assertFalse(documented.isEmpty(), "no documented permissions found under " + EXAMPLES);
        int people = 0;
        for (String userIdentity : List.of("email", "id")) {
            SharePointSecurityMapper mapper = new SharePointSecurityMapper(
                    SharePointProperties.fromConfiguration(Map.of("userIdentity", userIdentity)),
                    new SharePointPrincipals(SharePointProperties.fromConfiguration(Map.of("userIdentity", userIdentity)), "site-1"));
            for (JsonNode permission : documented) {
                Set<String> names = new HashSet<>();
                collect(permission, "displayName", names);
                Set<String> userIds = new HashSet<>();
                permission.path("grantedToV2").path("user").path("id").asText("").transform(id -> id.isEmpty() ? userIds : add(userIds, id));
                permission.path("grantedToIdentitiesV2").forEach(set -> {
                    String id = set.path("user").path("id").asText("");
                    if (!id.isEmpty()) {
                        add(userIds, id);
                    }
                });
                List<PermissionRule> rules = mapper.map(List.of(permission)).security().permissions();
                for (PermissionRule rule : rules) {
                    assertFalse(names.contains(rule.identity()) && !rule.identity().contains("@"),
                            "a display name became an identity: " + rule.identity() + " from " + permission);
                }
                boolean grants = permission.path("link").isMissingNode()
                        || "users".equals(permission.path("link").path("scope").asText());
                for (String id : grants ? userIds : Set.<String>of()) {
                    people++;
                    assertTrue(rules.stream().anyMatch(r -> r.identity().equals(id)),
                            "the documented user " + id + " is not identifiable in " + rules + " from " + permission);
                }
            }
        }
        assertTrue(people > 0, "the examples should document at least one user grant");
    }

    private static Set<String> add(Set<String> set, String value) {
        set.add(value.toLowerCase(Locale.ROOT));
        return set;
    }

    @Test
    void documentedItemsBuildDocuments() throws Exception {
        List<JsonNode> items = new ArrayList<>();
        for (String page : List.of("driveitem-get", "driveitem-list-children", "driveitem-delta")) {
            for (JsonNode e : examples("exchanges", page)) {
                JsonNode body = e.path("body");
                if (body.path("value").isArray()) {
                    body.path("value").forEach(items::add);
                } else if (body.has("id")) {
                    items.add(body);
                }
            }
        }
        assertFalse(items.isEmpty());
        SharePointDocumentBuilder.Context context = new SharePointDocumentBuilder.Context("contoso.sharepoint.com", "site-1",
                "https://contoso.sharepoint.com/sites/x", "b!drive", "Documents", "");
        for (JsonNode item : items.stream().filter(i -> i.has("file")).toList()) {
            RepositoryDocument d = SharePointDocumentBuilder.build(context, item, item.path("listItem").path("fields"), true,
                    "sharepoint_field_", null, -1, SharePointSecurityMapper.publicAccess());
            assertEquals(SharePointDocumentBuilder.documentId("contoso.sharepoint.com", "b!drive", item.path("id").asText()), d.id());
            assertEquals(List.of(item.path("name").asText()), d.metadata().get("name"));
        }
    }

    @Test
    void documentedDeltaPagesCrawl() throws Exception {
        List<JsonNode> pages = examples("exchanges", "driveitem-delta");
        assertFalse(pages.isEmpty());
        for (int n = 0; n < pages.size(); n++) {
            JsonNode example = pages.get(n);
            try (MockGraph graph = new MockGraph()) {
                graph.json("POST /tenant-1/oauth2/v2.0/token", "token.json");
                graph.json("GET /v1.0/sites/contoso.sharepoint.com:/sites/engineering", "site.json");
                graph.json("GET /v1.0/sites/" + MockGraph.SITE_ID + "/drives", "drives.json");
                // the documented page as the whole first enumeration: items verbatim, Microsoft's links pointed at this server
                ObjectNode page = (ObjectNode) example.path("body").deepCopy();
                page.remove("@odata.nextLink");
                page.put("@odata.deltaLink", graph.base() + "/v1.0/drives/b!lib1/root/delta?token=next");
                String body = MAPPER.writeValueAsString(page);
                graph.routes.put(MockGraph.DELTA_START, r -> new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(body));
                for (JsonNode item : page.path("value")) {
                    String id = item.path("id").asText();
                    graph.json("GET /v1.0/drives/b!lib1/items/" + id + "/permissions", "permissions-empty.json");
                    graph.routes.put("GET /v1.0/drives/b!lib1/items/" + id + "/content", r -> new MockResponse().setResponseCode(302)
                            .setHeader("Location", graph.base() + "/download/" + id));
                    graph.body("GET /download/" + id, "text/plain", "Content of " + item.path("name").asText());
                    graph.routes.put("GET /v1.0/drives/b!lib1/items/" + id, r -> new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json").setBody(item.toString()));
                }
                SharePointRepositoryConnector connector = new SharePointRepositoryConnector(SharePointProperties.fromConfiguration(
                        Map.of("tenantId", "tenant-1", "clientId", "client-1", "clientSecret", "s", "authorityUrl", graph.base(),
                                "graphUrl", graph.base() + "/v1.0", "siteUrl", "https://contoso.sharepoint.com/sites/engineering",
                                "driveName", "Documents", "stateDirectory", state.resolve("example-" + n).toString())));
                List<RepositoryDocument> documents = connector.scan("default").collectList().block();
                assertNotNull(documents);
                for (RepositoryDocument d : documents) {
                    if (d.contentStream() != null) {
                        try (InputStream in = d.contentStream()) {
                            in.transferTo(OutputStream.nullOutputStream());
                        }
                    }
                }
                for (JsonNode item : page.path("value")) {
                    String id = SharePointDocumentBuilder.documentId("contoso.sharepoint.com", "b!lib1", item.path("id").asText());
                    if (item.has("deleted") && item.has("file")) {
                        assertTrue(documents.stream().anyMatch(d -> d.id().equals(id) && d.action() == DocumentAction.DELETE),
                                "a documented deleted file becomes a tombstone: " + example.path("source").asText());
                    } else if (item.has("file") && !item.has("deleted")) {
                        assertTrue(documents.stream().anyMatch(d -> d.id().equals(id) && d.action() == DocumentAction.UPSERT),
                                "a documented file is crawled: " + item + " from " + example.path("source").asText());
                    }
                }
            }
        }
    }
}
