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
package org.opencrawling.qdrant;

import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantPointMapperTest {

    private final QdrantPointMapper mapper = new QdrantPointMapper();

    @Test
    void mapsSecurityConfigPermissionsIntoAllowAndDenyPayloadFields() {
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("user1", "user", "User One", "read"),
                new PermissionRule("group1", "group", "Group One", "write"),
                new PermissionRule("baduser", "user", "Bad User", "deny")
        ));

        PointStruct point = mapper.toPoint("doc-1_chunk-0", "hello world", "file:///doc-1.txt", "acl-1",
                "2026-01-01T00:00:00Z", security, new float[]{0.1f, 0.2f}, Map.of());

        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload.get(QdrantFields.FIELD_SECURITY_INHERITANCE).getBoolValue()).isTrue();
        assertThat(valuesOf(payload.get(QdrantFields.FIELD_SECURITY_ALLOWED_READ))).containsExactlyInAnyOrder("user:user1", "group:group1");
        assertThat(valuesOf(payload.get(QdrantFields.FIELD_SECURITY_DENIED_READ))).containsExactly("user:baduser");
        assertThat(payload.get(QdrantFields.FIELD_TEXT).getStringValue()).isEqualTo("hello world");
        assertThat(payload.get(QdrantFields.FIELD_CHUNK_ID).getStringValue()).isEqualTo("doc-1_chunk-0");
    }

    @Test
    void mapsRawMapShapedSecurityMetadataFromKafka() {
        Map<String, Object> security = Map.of(
                "inheritanceEnabled", false,
                "permissions", List.of(
                        Map.of("identity", "hr-group", "access", "read"),
                        Map.of("identity", "external-user", "access", "deny")
                ));

        PointStruct point = mapper.toPoint("doc-2_chunk-0", "restricted text", "file:///doc-2.txt", "acl-2",
                "2026-01-01T00:00:00Z", security, new float[]{0.3f}, Map.of());

        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload.get(QdrantFields.FIELD_SECURITY_INHERITANCE).getBoolValue()).isFalse();
        assertThat(valuesOf(payload.get(QdrantFields.FIELD_SECURITY_ALLOWED_READ))).containsExactly("hr-group");
        assertThat(valuesOf(payload.get(QdrantFields.FIELD_SECURITY_DENIED_READ))).containsExactly("external-user");
    }

    @Test
    void adminGrantsReadAndPublicTypedRulesBecomePublic() {
        SecurityConfig security = new SecurityConfig(false, List.of(
                new PermissionRule("site-managers", "group", "Site Managers", "admin"),
                new PermissionRule("GROUP_EVERYONE", "public", "Everyone", "read")
        ));

        PointStruct point = mapper.toPoint("doc-3_chunk-0", "text", "file:///doc-3.txt", "secured",
                "now", security, new float[]{0.1f}, Map.of());

        assertThat(valuesOf(point.getPayloadMap().get(QdrantFields.FIELD_SECURITY_ALLOWED_READ)))
                .containsExactly("group:site-managers", "public");
    }

    @Test
    void legacyAclAloneIsIndexedAsAllowedReaders() {
        PointStruct point = mapper.toPoint("doc-4_chunk-0", "text", "file:///doc-4.txt", "finance,read",
                "now", null, new float[]{0.1f}, Map.of());

        assertThat(valuesOf(point.getPayloadMap().get(QdrantFields.FIELD_SECURITY_ALLOWED_READ)))
                .containsExactly("finance");
    }

    @Test
    void restrictiveAclOverridesTheDefaultPublicPlaceholder() {
        PointStruct point = mapper.toPoint("doc-5_chunk-0", "text", "file:///doc-5.txt", "finance",
                "now", SecurityConfig.createPublic(), new float[]{0.1f}, Map.of());

        assertThat(valuesOf(point.getPayloadMap().get(QdrantFields.FIELD_SECURITY_ALLOWED_READ)))
                .containsExactly("finance");
    }

    @Test
    void mapsDocumentIdToPayload() {
        PointStruct pointWithArg = mapper.toPoint("doc-parent-10", "doc-parent-10_chunk-0", "text", "file:///doc.txt", "public",
                "now", null, new float[]{0.1f}, Map.of());
        assertThat(pointWithArg.getPayloadMap().get(QdrantFields.FIELD_DOCUMENT_ID).getStringValue()).isEqualTo("doc-parent-10");

        PointStruct pointWithMeta = mapper.toPoint("doc-parent-20_chunk-0", "text", "file:///doc.txt", "public",
                "now", null, new float[]{0.1f}, Map.of("documentId", "doc-parent-20"));
        assertThat(pointWithMeta.getPayloadMap().get(QdrantFields.FIELD_DOCUMENT_ID).getStringValue()).isEqualTo("doc-parent-20");
    }

    @Test
    void pointIdIsDeterministicForTheSameChunkId() {
        PointStruct first = mapper.toPoint("doc-3_chunk-0", "text", "uri", "acl", "now", null, new float[]{0.1f}, Map.of());
        PointStruct second = mapper.toPoint("doc-3_chunk-0", "different text", "other-uri", "acl2", "later", null, new float[]{0.9f}, Map.of());

        assertThat(first.getId()).isEqualTo(second.getId());
        assertThat(first.getId().getUuid()).isEqualTo(UUID.nameUUIDFromBytes("doc-3_chunk-0".getBytes()).toString());
    }

    @Test
    void excludesRawSecurityKeyFromExtraMetadataButKeepsOtherDynamicFields() {
        Map<String, Object> extraMetadata = Map.of(
                "security", "should-not-leak",
                "title", List.of("My Document")
        );

        PointStruct point = mapper.toPoint("doc-4_chunk-0", "text", "uri", "acl", "now", null, new float[]{0.1f}, extraMetadata);

        Map<String, Value> payload = point.getPayloadMap();
        assertThat(payload).doesNotContainKey("security");
        assertThat(valuesOf(payload.get("title"))).containsExactly("My Document");
    }

    private static List<String> valuesOf(Value listValue) {
        return listValue.getListValue().getValuesList().stream().map(Value::getStringValue).toList();
    }
}
