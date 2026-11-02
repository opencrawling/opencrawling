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
package org.opencrawling.runtime.authorization;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.junit.jupiter.api.Test;
import org.opencrawling.authorization.openfga.OpenFgaAuthorizationProvider;
import org.opencrawling.authorization.openfga.OpenFgaBootstrap;
import org.opencrawling.authorization.openfga.OpenFgaProperties;
import org.opencrawling.authorization.openfga.OpenFgaTupleWriter;
import org.opencrawling.core.security.Caller;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.core.security.SecurityConfig;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.openfga.OpenFGAContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guarantee behind {@code shadow} → {@code openfga}: for every document and caller, OpenFGA (fed by the
 * tuple writer) decides exactly what the stored-metadata provider decides on what the store writers index.
 */
@Testcontainers
class OpenFgaDifferentialIT {

    @Container
    static final OpenFGAContainer OPENFGA = new OpenFGAContainer("openfga/openfga:v1.22.0");

    private static final String[] IDENTITIES = {
            "alice", "Alice", "bob", "finance", "FINANCE", "group:finance", "GROUP:Finance", "user:alice",
            "User:Bob", "group:user:alice", "user:group:finance", "public", "Public", "GROUP_EVERYONE",
            "DOMAIN\\Jane Doe", "jane doe", "zoë", "a#b@c", "100%", "secured", "user:a;group:b", "IŞIK", "ışık", "ſtrasse", "STRASSE", "Σ", "ς", "cn=bob,ou=users,dc=corp", ""};
    private static final String[] TYPES = {"user", "group", "public", null};
    private static final String[] ACCESS = {"read", "write", "admin", "deny", "none", null};

    @Test
    void openFgaAgreesWithStoredMetadataOnRandomDocumentsAndCallers() throws Exception {
        OpenFgaProperties properties = new OpenFgaProperties(OPENFGA.getHttpEndpoint(), null, null, "differential",
                true, null, Duration.ofSeconds(20), 50, 8);
        OpenFgaClient client = new OpenFgaClient(new ClientConfiguration().apiUrl(properties.apiUrl()));
        OpenFgaBootstrap.configure(client, properties);
        OpenFgaTupleWriter writer = new OpenFgaTupleWriter(client, properties);
        OpenFgaAuthorizationProvider openfga = new OpenFgaAuthorizationProvider(client, properties);
        MetadataAuthorizationProvider metadata = new MetadataAuthorizationProvider();

        Random random = new Random(45_187);
        List<ResourceRef> documents = new ArrayList<>();
        for (int d = 0; d < 250; d++) {
            String documentId = "doc-" + d;
            EffectiveAcl acl = EffectiveAcl.resolve(randomSecurity(random), randomAcl(random));
            writer.sync(documentId, acl);
            documents.add(new ResourceRef(documentId, indexedMetadata(acl)));
        }

        int compared = 0;
        for (int c = 0; c < 150; c++) {
            String principal = random.nextInt(5) == 0 ? null : pick(random);
            List<String> roles = new ArrayList<>();
            for (int r = random.nextInt(4); r > 0; r--) {
                roles.add(pick(random));
            }
            Caller caller = Caller.asserted(principal, roles);

            boolean[] expected = metadata.canRead(caller, documents);
            boolean[] actual = openfga.canRead(caller, documents);
            for (int d = 0; d < documents.size(); d++) {
                assertThat(actual[d]).as("caller %s %s on %s", principal, roles, documents.get(d).metadata())
                        .isEqualTo(expected[d]);
                compared++;
            }
        }
        assertThat(compared).isEqualTo(250 * 150);
    }

    /** What the store writers index for a document (pgvector also keeps the security object). */
    private static Map<String, Object> indexedMetadata(EffectiveAcl acl) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("security_allowed_read", acl.allowedRead());
        metadata.put("security_denied_read", acl.deniedRead());
        if (acl.security() != null) {
            metadata.put("security", acl.security());
        }
        return metadata;
    }

    private static String pick(Random random) {
        String value = IDENTITIES[random.nextInt(IDENTITIES.length)];
        return value.isEmpty() ? "x" : value;
    }

    private static Object randomSecurity(Random random) {
        int kind = random.nextInt(4);
        if (kind == 0) {
            return null;
        }
        if (kind == 1) {
            return SecurityConfig.createPublic();
        }
        List<PermissionRule> rules = new ArrayList<>();
        for (int i = random.nextInt(5); i > 0; i--) {
            String identity = IDENTITIES[random.nextInt(IDENTITIES.length)];
            rules.add(new PermissionRule(identity, TYPES[random.nextInt(TYPES.length)], identity,
                    ACCESS[random.nextInt(ACCESS.length)]));
        }
        return new SecurityConfig(random.nextBoolean(), rules);
    }

    private static String randomAcl(Random random) {
        if (random.nextInt(3) == 0) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (int i = random.nextInt(3); i > 0; i--) {
            tokens.add(random.nextInt(4) == 0 ? "read" : IDENTITIES[random.nextInt(IDENTITIES.length)]);
        }
        return String.join(",", tokens);
    }
}
