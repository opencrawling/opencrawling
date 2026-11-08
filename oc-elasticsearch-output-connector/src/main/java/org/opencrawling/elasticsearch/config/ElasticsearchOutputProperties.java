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

import co.elastic.clients.elasticsearch._types.mapping.DenseVectorIndexOptionsType;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * Settings of the Elasticsearch output, bound from {@code spring.opencrawling.output.elasticsearch.*}.
 *
 * @param uris          one or more node URLs, comma-separated
 * @param username      basic-auth user; ignored when {@code apiKey} is set or {@code password} is blank
 * @param password      basic-auth password
 * @param apiKey        base64 API key (Elastic Cloud / Serverless); wins over basic auth
 * @param indexName     the index chunks are written to
 * @param dimensions    the {@code dense_vector} dimensions; must match the embedding model
 * @param similarity    the vector similarity of the {@code dense_vector} field
 * @param indexType     the ANN index type ({@code int8_hnsw} quantizes vectors to cut memory by ~75%)
 * @param bulkBatchSize the most chunks sent in one bulk request
 * @param createIndex   whether to create the index with the expected mapping when it is missing
 * @param compatibleWith the REST API major to request ({@code compatible-with}); 8 works with 8.x and 9.x servers,
 *                      9 drops compatibility mode on 9.x-only clusters, 0 keeps the client default
 * @param ssl           TLS trust settings for {@code https} URIs
 */
@ConfigurationProperties(prefix = "spring.opencrawling.output.elasticsearch")
public record ElasticsearchOutputProperties(
        @DefaultValue("http://localhost:9200") String uris,
        @DefaultValue("elastic") String username,
        @DefaultValue("") String password,
        @DefaultValue("") String apiKey,
        @DefaultValue("enterprise_kb") String indexName,
        @DefaultValue("1024") int dimensions,
        @DefaultValue("COSINE") Similarity similarity,
        @DefaultValue("HNSW") IndexType indexType,
        @DefaultValue("500") int bulkBatchSize,
        @DefaultValue("true") boolean createIndex,
        @DefaultValue("8") int compatibleWith,
        @DefaultValue Ssl ssl
) {

    public ElasticsearchOutputProperties {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("spring.opencrawling.output.elasticsearch.dimensions must be positive");
        }
        if (bulkBatchSize <= 0) {
            throw new IllegalArgumentException("spring.opencrawling.output.elasticsearch.bulk-batch-size must be positive");
        }
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("spring.opencrawling.output.elasticsearch.index-name must not be blank");
        }
        if (similarity == null) {
            similarity = Similarity.COSINE;
        }
        if (indexType == null) {
            indexType = IndexType.HNSW;
        }
        if (ssl == null) {
            ssl = new Ssl("", "", "PKCS12", "");
        }
    }

    /** Defaults for programmatic use (tests, the lazy client of a hand-built connector). */
    public static ElasticsearchOutputProperties defaults() {
        return new ElasticsearchOutputProperties("http://localhost:9200", "elastic", "", "", "enterprise_kb",
                1024, Similarity.COSINE, IndexType.HNSW, 500, true, 8, null);
    }

    /** The configured node URLs. */
    public List<URI> hostUris() {
        List<URI> hosts = Arrays.stream((uris == null ? "" : uris).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(URI::create)
                .toList();
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("spring.opencrawling.output.elasticsearch.uris must name at least one node");
        }
        return hosts;
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public boolean hasBasicAuth() {
        return username != null && !username.isBlank() && password != null && !password.isEmpty();
    }

    /** {@code dense_vector} similarity. */
    public enum Similarity {
        COSINE(DenseVectorSimilarity.Cosine),
        DOT_PRODUCT(DenseVectorSimilarity.DotProduct),
        L2_NORM(DenseVectorSimilarity.L2Norm),
        MAX_INNER_PRODUCT(DenseVectorSimilarity.MaxInnerProduct);

        private final DenseVectorSimilarity esValue;

        Similarity(DenseVectorSimilarity esValue) {
            this.esValue = esValue;
        }

        public DenseVectorSimilarity toElasticsearch() {
            return esValue;
        }
    }

    /** {@code dense_vector} {@code index_options.type}. */
    public enum IndexType {
        HNSW(DenseVectorIndexOptionsType.Hnsw),
        INT8_HNSW(DenseVectorIndexOptionsType.Int8Hnsw),
        INT4_HNSW(DenseVectorIndexOptionsType.Int4Hnsw),
        BBQ_HNSW(DenseVectorIndexOptionsType.BbqHnsw),
        FLAT(DenseVectorIndexOptionsType.Flat),
        INT8_FLAT(DenseVectorIndexOptionsType.Int8Flat);

        private final DenseVectorIndexOptionsType esValue;

        IndexType(DenseVectorIndexOptionsType esValue) {
            this.esValue = esValue;
        }

        public DenseVectorIndexOptionsType toElasticsearch() {
            return esValue;
        }
    }

    /**
     * TLS trust for {@code https} nodes. With neither a trust store nor a fingerprint the JVM default trust applies.
     *
     * @param trustStorePath     a trust store holding the cluster's CA
     * @param trustStorePassword its password
     * @param trustStoreType     its type ({@code PKCS12}, {@code JKS})
     * @param caFingerprint      the SHA-256 fingerprint of the cluster CA (Elasticsearch 8 auto-generated certificates)
     */
    public record Ssl(
            @DefaultValue("") String trustStorePath,
            @DefaultValue("") String trustStorePassword,
            @DefaultValue("PKCS12") String trustStoreType,
            @DefaultValue("") String caFingerprint
    ) {
    }
}
