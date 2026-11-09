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
package org.opencrawling.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptionsType;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorProperty;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import org.junit.jupiter.api.Test;
import org.opencrawling.elasticsearch.ElasticsearchConstants;

import java.io.IOException;
import java.net.URI;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class ElasticsearchConfigTest {

    @Test
    void hostUrisSplitsCommaSeparatedNodes() {
        ElasticsearchOutputProperties props = props(" http://es1:9200 , https://es2:9243,", "", "", "");

        assertThat(props.hostUris()).containsExactly(URI.create("http://es1:9200"), URI.create("https://es2:9243"));
    }

    @Test
    void hostUrisRejectsEmptyList() {
        assertThatThrownBy(() -> props(" , ", "", "", "").hostUris()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void apiKeyWinsOverBasicAuthAndBlankPasswordMeansNoAuth() {
        assertThat(props("http://x:9200", "elastic", "secret", "a2V5").hasApiKey()).isTrue();
        assertThat(props("http://x:9200", "elastic", "secret", "").hasBasicAuth()).isTrue();
        assertThat(props("http://x:9200", "elastic", "", "").hasBasicAuth()).isFalse();
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new ElasticsearchOutputProperties("http://x", "", "", "", "kb", 0,
                null, null, 1, true, 8, null)).hasMessageContaining("dimensions");
        assertThatThrownBy(() -> new ElasticsearchOutputProperties("http://x", "", "", "", "kb", 4,
                null, null, 0, true, 8, null)).hasMessageContaining("bulk-batch-size");
        assertThatThrownBy(() -> new ElasticsearchOutputProperties("http://x", "", "", "", " ", 4,
                null, null, 1, true, 8, null)).hasMessageContaining("index-name");
    }

    @Test
    void nullEnumsAndSslFallBackToDefaults() {
        ElasticsearchOutputProperties props = new ElasticsearchOutputProperties("http://x", "", "", "", "kb", 4,
                null, null, 1, true, 8, null);

        assertThat(props.similarity()).isEqualTo(ElasticsearchOutputProperties.Similarity.COSINE);
        assertThat(props.indexType()).isEqualTo(ElasticsearchOutputProperties.IndexType.HNSW);
        assertThat(props.ssl().trustStoreType()).isEqualTo("PKCS12");
        assertThat(ElasticsearchClientFactory.sslContext(props.ssl())).isNull();
    }

    @Test
    void compatibleMediaTypeRequestsTheConfiguredMajor() {
        assertThat(ElasticsearchClientFactory.compatibleMediaType(8))
                .isEqualTo("application/vnd.elasticsearch+json; compatible-with=8");
        assertThat(ElasticsearchClientFactory.compatibleMediaType(0)).isNull();
        assertThat(ElasticsearchClientFactory.compatibleMediaType(null)).isNull();
    }

    @Test
    void sslContextFromMissingTrustStoreFailsClearly() {
        ElasticsearchOutputProperties.Ssl ssl = new ElasticsearchOutputProperties.Ssl("does/not/exist.p12", "pw", "PKCS12", "");

        assertThatThrownBy(() -> ElasticsearchClientFactory.sslContext(ssl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does/not/exist.p12");
    }

    @Test
    void mappingDeclaresDenseVectorAndKeywordAclFields() {
        ElasticsearchOutputProperties props = new ElasticsearchOutputProperties("http://x", "", "", "", "kb", 768,
                ElasticsearchOutputProperties.Similarity.DOT_PRODUCT, ElasticsearchOutputProperties.IndexType.INT8_HNSW,
                100, true, 8, null);

        TypeMapping mapping = ElasticsearchIndexInitializer.mapping(props);

        assertThat(mapping.dateDetection()).isFalse();
        DenseVectorProperty vector = mapping.properties().get(ElasticsearchConstants.FIELD_EMBEDDINGS).denseVector();
        assertThat(vector.dims()).isEqualTo(768);
        assertThat(vector.index()).isTrue();
        assertThat(vector.similarity()).isEqualTo(DenseVectorSimilarity.DotProduct);
        assertThat(vector.indexOptions().type()).isEqualTo(DenseVectorIndexOptionsType.Int8Hnsw);
        for (String field : new String[]{ElasticsearchConstants.FIELD_ID, ElasticsearchConstants.FIELD_DOCUMENT_ID,
                ElasticsearchConstants.FIELD_URI, ElasticsearchConstants.FIELD_ACL,
                ElasticsearchConstants.FIELD_SECURITY_ALLOWED_READ, ElasticsearchConstants.FIELD_SECURITY_DENIED_READ,
                ElasticsearchConstants.FIELD_SECURITY_READ_KEYS}) {
            assertThat(mapping.properties().get(field).isKeyword()).as(field).isTrue();
        }
        assertThat(mapping.properties().get(ElasticsearchConstants.FIELD_TEXT).isText()).isTrue();
        assertThat(mapping.properties().get(ElasticsearchConstants.FIELD_LAST_MODIFIED).date().ignoreMalformed()).isTrue();
    }

    @Test
    void ensureIndexSkipsCreationWhenIndexExists() throws IOException {
        ElasticsearchIndicesClient indices = indicesClient(true);
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.indices()).thenReturn(indices);

        new ElasticsearchIndexInitializer(client, props("http://x", "", "", "")).ensureIndex();

        verify(indices, never()).create(any(Function.class));
    }

    @Test
    void ensureIndexCreatesMissingIndex() throws IOException {
        ElasticsearchIndicesClient indices = indicesClient(false);
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.indices()).thenReturn(indices);

        new ElasticsearchIndexInitializer(client, props("http://x", "", "", "")).ensureIndex();

        verify(indices).create(any(Function.class));
    }

    @Test
    void ensureIndexToleratesConcurrentCreation() throws IOException {
        ElasticsearchIndicesClient indices = indicesClient(false);
        when(indices.create(any(Function.class))).thenThrow(esError("resource_already_exists_exception"));
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.indices()).thenReturn(indices);

        new ElasticsearchIndexInitializer(client, props("http://x", "", "", "")).ensureIndex();
    }

    @Test
    void ensureIndexRetriesThenGivesUp() throws IOException {
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(indices.exists(any(Function.class))).thenThrow(new IOException("connection refused"));
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.indices()).thenReturn(indices);

        assertThatThrownBy(() -> new ElasticsearchIndexInitializer(client, props("http://x", "", "", "")).ensureIndex(3, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after 3 attempts");
        verify(indices, times(3)).exists(any(Function.class));
    }

    @Test
    void ensureIndexPropagatesOtherCreateErrors() throws IOException {
        ElasticsearchIndicesClient indices = indicesClient(false);
        when(indices.create(any(Function.class))).thenThrow(esError("illegal_argument_exception"));
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.indices()).thenReturn(indices);

        assertThatThrownBy(() -> new ElasticsearchIndexInitializer(client, props("http://x", "", "", "")).ensureIndex())
                .isInstanceOf(ElasticsearchException.class);
    }

    private static ElasticsearchIndicesClient indicesClient(boolean exists) throws IOException {
        ElasticsearchIndicesClient indices = mock(ElasticsearchIndicesClient.class);
        when(indices.exists(any(Function.class))).thenReturn(new BooleanResponse(exists));
        return indices;
    }

    private static ElasticsearchException esError(String type) {
        return new ElasticsearchException("indices.create",
                ErrorResponse.of(r -> r.status(400).error(e -> e.type(type).reason(type))));
    }

    private static ElasticsearchOutputProperties props(String uris, String user, String password, String apiKey) {
        return new ElasticsearchOutputProperties(uris, user, password, apiKey, "kb", 4,
                ElasticsearchOutputProperties.Similarity.COSINE, ElasticsearchOutputProperties.IndexType.HNSW,
                500, true, 8, null);
    }
}
