/*
 * Copyright © ${year} the original author or authors (michael@michaelcizmar.com)
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
package org.opencrawling.vespa;

import ai.vespa.feed.client.DocumentId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class VespaDocumentMapperTest {

    private final VespaDocumentMapper mapper = new VespaDocumentMapper();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void mapsSecurityConfigPermissionsIntoAllowAndDenyFields() throws Exception {
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("user1", "user", "User One", "read"),
                new PermissionRule("group1", "group", "Group One", "write"),
                new PermissionRule("baduser", "user", "Bad User", "deny")
        ));

        VespaDocumentMapper.VespaDocument document = mapper.toDocument("opencrawling", "opencrawling_chunk",
                "doc-1_chunk-0", "hello world", "file:///doc-1.txt", "acl-1", "2026-01-01T00:00:00Z",
                security, new float[]{0.1f, 0.2f}, Map.of());

        JsonNode fields = json.readTree(document.json()).get("fields");
        assertThat(fields.get(VespaFields.FIELD_SECURITY_INHERITANCE).asBoolean()).isTrue();
        assertThat(toList(fields.get(VespaFields.FIELD_SECURITY_ALLOWED_READ))).containsExactlyInAnyOrder("user:user1", "group:group1");
        assertThat(toList(fields.get(VespaFields.FIELD_SECURITY_DENIED_READ))).containsExactly("user:baduser");
        assertThat(fields.get(VespaFields.FIELD_TEXT).asText()).isEqualTo("hello world");
        assertThat(fields.get(VespaFields.FIELD_CHUNK_ID).asText()).isEqualTo("doc-1_chunk-0");
    }

    @Test
    void mapsRawMapShapedSecurityMetadataFromKafka() throws Exception {
        Map<String, Object> security = Map.of(
                "inheritanceEnabled", false,
                "permissions", List.of(
                        Map.of("identity", "hr-group", "access", "read"),
                        Map.of("identity", "external-user", "access", "deny")
                ));

        VespaDocumentMapper.VespaDocument document = mapper.toDocument("opencrawling", "opencrawling_chunk",
                "doc-2_chunk-0", "restricted text", "file:///doc-2.txt", "acl-2", "2026-01-01T00:00:00Z",
                security, new float[]{0.3f}, Map.of());

        JsonNode fields = json.readTree(document.json()).get("fields");
        assertThat(fields.get(VespaFields.FIELD_SECURITY_INHERITANCE).asBoolean()).isFalse();
        assertThat(toList(fields.get(VespaFields.FIELD_SECURITY_ALLOWED_READ))).containsExactly("hr-group");
        assertThat(toList(fields.get(VespaFields.FIELD_SECURITY_DENIED_READ))).containsExactly("external-user");
    }

    @Test
    void documentIdIsDeterministicForTheSameChunkId() {
        DocumentId first = mapper.toDocument("opencrawling", "opencrawling_chunk", "doc-3_chunk-0",
                "text", "uri", "acl", "now", null, new float[]{0.1f}, Map.of()).id();
        DocumentId second = mapper.toDocument("opencrawling", "opencrawling_chunk", "doc-3_chunk-0",
                "different text", "other-uri", "acl2", "later", null, new float[]{0.9f}, Map.of()).id();

        assertThat(first).isEqualTo(second);
        assertThat(first.userSpecific()).isEqualTo("doc-3_chunk-0");
    }

    @Test
    void excludesRawSecurityKeyFromMetadataJsonButKeepsOtherDynamicFields() throws Exception {
        Map<String, Object> extraMetadata = Map.of(
                "security", "should-not-leak",
                "title", List.of("My Document")
        );

        VespaDocumentMapper.VespaDocument document = mapper.toDocument("opencrawling", "opencrawling_chunk",
                "doc-4_chunk-0", "text", "uri", "acl", "now", null, new float[]{0.1f}, extraMetadata);

        JsonNode fields = json.readTree(document.json()).get("fields");
        JsonNode metadata = json.readTree(fields.get(VespaFields.FIELD_METADATA_JSON).asText());
        assertThat(metadata.has("security")).isFalse();
        assertThat(metadata.get("title").get(0).asText()).isEqualTo("My Document");
    }

    @Test
    void resolvesWellKnownEmbeddingDimensionsToDedicatedDocumentTypes() {
        assertThat(VespaDocumentMapper.resolveDocumentType(384, "opencrawling_chunk")).isEqualTo(VespaFields.DOCUMENT_TYPE_384);
        assertThat(VespaDocumentMapper.resolveDocumentType(768, "opencrawling_chunk")).isEqualTo(VespaFields.DOCUMENT_TYPE_768);
        assertThat(VespaDocumentMapper.resolveDocumentType(1024, "opencrawling_chunk")).isEqualTo(VespaFields.DOCUMENT_TYPE_1024);
    }

    @Test
    void fallsBackToDefaultDocumentTypeForUnknownEmbeddingDimensions() {
        assertThat(VespaDocumentMapper.resolveDocumentType(1536, "opencrawling_chunk")).isEqualTo("opencrawling_chunk");
        assertThat(VespaDocumentMapper.resolveDocumentType(2, "opencrawling_chunk")).isEqualTo("opencrawling_chunk");
    }

    private static List<String> toList(JsonNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
