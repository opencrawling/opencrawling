/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.opencrawling.elasticsearch.ElasticsearchConstants.*;

@SuppressWarnings("unchecked")
class ElasticsearchDocumentMapperTest {

    private final ElasticsearchDocumentMapper mapper = new ElasticsearchDocumentMapper();

    @Test
    void reservedFieldsAreNotOverwrittenByMetadata() {
        Map<String, Object> source = mapper.toSource(chunk(Map.of(
                FIELD_ID, "evil", FIELD_TEXT, "evil", FIELD_URI, "evil",
                FIELD_SECURITY_READ_KEYS, List.of("public"), FIELD_EMBEDDINGS, "evil",
                "security", Map.of("x", 1), "category", "kb")));

        assertThat(source)
                .containsEntry(FIELD_ID, "doc_0")
                .containsEntry(FIELD_TEXT, "hello")
                .containsEntry(FIELD_URI, "file:///doc")
                .containsEntry("category", "kb")
                .doesNotContainKey("security");
        assertThat((List<String>) source.get(FIELD_SECURITY_READ_KEYS)).doesNotContain("public");
        assertThat(source.get(FIELD_EMBEDDINGS)).isInstanceOf(float[].class);
    }

    @Test
    void readKeysFollowTheIdentityPrefixRule() {
        EffectiveAcl acl = EffectiveAcl.resolve(new SecurityConfig(false, List.of(
                new PermissionRule("user:Alice", "user", "Alice", "read"),
                new PermissionRule("group:Finance", "group", "Finance", "read"),
                new PermissionRule("Ops", null, "Ops", "read"))), null);

        Map<String, Object> source = mapper.toSource(new ElasticsearchDocumentMapper.Chunk(
                "c", "d", "t", "u", null, null, acl, new float[]{1f}, null));

        assertThat((List<String>) source.get(FIELD_SECURITY_READ_KEYS))
                .contains("u:alice", "g:finance", "u:ops", "g:ops")
                .doesNotContain("g:alice", "u:finance");
    }

    @Test
    void blankOptionalFieldsAreOmitted() {
        Map<String, Object> source = mapper.toSource(new ElasticsearchDocumentMapper.Chunk(
                "c", " ", "t", "u", null, "", EffectiveAcl.resolve(null, "public"), new float[]{1f}, null));

        assertThat(source).doesNotContainKeys(FIELD_DOCUMENT_ID, FIELD_LAST_MODIFIED).containsEntry(FIELD_ACL, "");
    }

    @Test
    void collectionAclIsJoined() {
        assertThat(ElasticsearchDocumentMapper.flatAcl(List.of("a", "b"))).isEqualTo("a,b");
        assertThat(ElasticsearchDocumentMapper.flatAcl("x")).isEqualTo("x");
        assertThat(ElasticsearchDocumentMapper.flatAcl(null)).isEmpty();
    }

    @Test
    void readableByQueryMatchesTheCallersReadKeys() {
        Query query = ElasticsearchAclQueries.readableBy("user:Alice", List.of("Engineering", "group:Ops"));

        assertThat(query.terms().field()).isEqualTo(FIELD_SECURITY_READ_KEYS);
        assertThat(query.terms().terms().value()).extracting(v -> v.stringValue())
                .containsExactly("public", "u:alice", "g:engineering", "g:ops");
    }

    private static ElasticsearchDocumentMapper.Chunk chunk(Map<String, ?> metadata) {
        return new ElasticsearchDocumentMapper.Chunk("doc_0", "doc", "hello", "file:///doc", "alice",
                "2026-01-01T00:00:00Z", EffectiveAcl.resolve(null, "alice"), new float[]{1f, 0f}, metadata);
    }
}
