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
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDriveSecurityMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsUsersGroupsDomainsAndInheritanceAndBlocksAnyoneByDefault() throws Exception {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of());
        GoogleDriveSecurityMapper mapper = new GoogleDriveSecurityMapper(props);

        List<JsonNode> perms = List.of(
                MAPPER.readTree("""
                        {"id":"p1","type":"user","role":"owner","emailAddress":"Alice@Example.com","displayName":"Alice"}
                        """),
                MAPPER.readTree("""
                        {"id":"p2","type":"group","role":"reader","emailAddress":"Engineering@Example.com",
                         "displayName":"Engineering","permissionDetails":[{"inherited":true}]}
                        """),
                MAPPER.readTree("""
                        {"id":"p3","type":"domain","role":"commenter","domain":"Example.com","displayName":"Example Corp"}
                        """),
                MAPPER.readTree("""
                        {"id":"p4","type":"anyone","role":"reader"}
                        """),
                MAPPER.readTree("""
                        {"id":"p5","type":"user","role":"reader","emailAddress":"deleted@example.com","deleted":true}
                        """),
                MAPPER.readTree("""
                        {"id":"p6","type":"user","role":"reader","emailAddress":"expired@example.com",
                         "expirationTime":"2020-01-01T00:00:00Z"}
                        """)
        );

        GoogleDriveSecurityMapper.Mapped mapped = mapper.map(perms, Instant.parse("2026-11-21T12:00:00Z"));

        assertThat(mapped.security().inheritanceEnabled()).isTrue();
        assertThat(mapped.security().permissions()).containsExactlyInAnyOrder(
                new PermissionRule("alice@example.com", "user", "Alice", "write"),
                new PermissionRule("engineering@example.com", "group", "Engineering", "read"),
                new PermissionRule("domain:example.com", "group", "Example Corp", "read")
        );
        assertThat(mapped.users()).containsExactly("alice@example.com");
        assertThat(mapped.groups()).containsExactlyInAnyOrder("engineering@example.com", "domain:example.com");
        assertThat(mapped.domains()).containsExactly("example.com");
        assertThat(mapped.acl()).doesNotContain("public")
                .contains("alice@example.com")
                .contains("engineering@example.com")
                .contains("domain:example.com");
    }

    @Test
    void allowAnyonePublicMapsAnyonePermissionToPublicRead() throws Exception {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of("allowAnyonePublic", "true"));
        GoogleDriveSecurityMapper mapper = new GoogleDriveSecurityMapper(props);

        JsonNode anyone = MAPPER.readTree("""
                {"id":"p1","type":"anyone","role":"reader"}
                """);

        GoogleDriveSecurityMapper.Mapped mapped = mapper.map(List.of(anyone));
        assertThat(mapped.acl()).isEqualTo("public");
        assertThat(mapped.security().permissions())
                .containsExactly(new PermissionRule("public", "public", "Public Access", "read"));
    }

    @Test
    void emptyPermissionsFailClosedAndNeverDefaultToPublic() {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of());
        GoogleDriveSecurityMapper mapper = new GoogleDriveSecurityMapper(props);

        GoogleDriveSecurityMapper.Mapped mapped = mapper.map(List.of());
        assertThat(mapped.security().permissions()).isEmpty();
        assertThat(mapped.acl()).isEmpty();
    }

    @Test
    void deduplicatesHighestAccessAndProducesOrderIndependentFingerprint() throws Exception {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of());
        GoogleDriveSecurityMapper mapper = new GoogleDriveSecurityMapper(props);

        JsonNode readAlice = MAPPER.readTree("""
                {"id":"p1","type":"user","role":"reader","emailAddress":"alice@example.com"}
                """);
        JsonNode writeAlice = MAPPER.readTree("""
                {"id":"p2","type":"user","role":"writer","emailAddress":"ALICE@example.com"}
                """);
        JsonNode bob = MAPPER.readTree("""
                {"id":"p3","type":"user","role":"reader","emailAddress":"bob@example.com"}
                """);

        GoogleDriveSecurityMapper.Mapped m1 = mapper.map(List.of(readAlice, writeAlice, bob));
        GoogleDriveSecurityMapper.Mapped m2 = mapper.map(List.of(bob, writeAlice, readAlice));

        assertThat(m1.security().permissions()).hasSize(2);
        assertThat(m1.security().permissions()).anySatisfy(r -> {
            assertThat(r.identity()).isEqualTo("alice@example.com");
            assertThat(r.access()).isEqualTo("write");
        });
        assertThat(m1.fingerprint()).isEqualTo(m2.fingerprint());
    }
}
