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
package org.opencrawling.opensearch2.config;

import jakarta.json.stream.JsonParser;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.core5.http.HttpHost;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opencrawling.opensearch2.OpenSearch2Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "opensearch2")
public class OpenSearch2StoreConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenSearch2StoreConfig.class);

    @Value("${spring.opencrawling.output.opensearch2.uris:http://localhost:9200}")
    private String uris;

    @Value("${spring.opencrawling.output.opensearch2.username:admin}")
    private String username;

    @Value("${spring.opencrawling.output.opensearch2.password:admin}")
    private String password;

    @Value("${spring.opencrawling.output.opensearch2.index-name:enterprise_kb}")
    private String indexName;

    @Value("${spring.opencrawling.output.opensearch2.dimensions:1024}")
    private int dimensions;

    @Bean
    public OpenSearchClient openSearchClient() {
        log.info("Initializing OpenSearchClient (2.x) connected to: {}", uris);
        try {
            final HttpHost host = HttpHost.create(uris);
            
            final BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(username, password.toCharArray()));

            final OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
                    .builder(host)
                    .setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider))
                    .setMapper(new JacksonJsonpMapper())
                    .build();

            OpenSearchClient client = new OpenSearchClient(transport);
            initializeIndex(client);
            return client;
        } catch (Exception e) {
            log.error("Failed to initialize OpenSearchClient bean", e);
            throw new RuntimeException("OpenSearch client bean initialization error", e);
        }
    }

    private void initializeIndex(OpenSearchClient client) {
        int maxRetries = 10;
        int delaySeconds = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                boolean exists = client.indices().exists(e -> e.index(indexName)).value();
                if (!exists) {
                    log.info("Index '{}' does not exist. Creating it with k-NN HNSW settings...", indexName);

                    String settingsJson = "{"
                            + "  \"index\": {"
                            + "    \"knn\": true"
                            + "  }"
                            + "}";

                    String mappingsJson = "{"
                            + "  \"properties\": {"
                            + "    \"" + OpenSearch2Constants.FIELD_ID + "\": { \"type\": \"keyword\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_TEXT + "\": { \"type\": \"text\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_URI + "\": { \"type\": \"keyword\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_ACL + "\": { \"type\": \"keyword\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_LAST_MODIFIED + "\": { \"type\": \"date\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_SECURITY_INHERITANCE + "\": { \"type\": \"boolean\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_SECURITY_ALLOWED_READ + "\": { \"type\": \"keyword\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_SECURITY_DENIED_READ + "\": { \"type\": \"keyword\" },"
                            + "    \"" + OpenSearch2Constants.FIELD_EMBEDDINGS + "\": {"
                            + "      \"type\": \"knn_vector\","
                            + "      \"dimension\": " + dimensions + ","
                            + "      \"method\": {"
                            + "        \"name\": \"hnsw\","
                            + "        \"space_type\": \"cosinesimil\","
                            + "        \"engine\": \"nmslib\","
                            + "        \"parameters\": {"
                            + "          \"ef_construction\": 128,"
                            + "          \"m\": 24"
                            + "        }"
                            + "      }"
                            + "    }"
                            + "  }"
                            + "}";

                    JacksonJsonpMapper mapper = new JacksonJsonpMapper();
                    JsonParser settingsParser = mapper.jsonProvider().createParser(new java.io.StringReader(settingsJson));
                    JsonParser mappingsParser = mapper.jsonProvider().createParser(new java.io.StringReader(mappingsJson));

                    IndexSettings settings = IndexSettings._DESERIALIZER.deserialize(settingsParser, mapper);
                    TypeMapping mapping = TypeMapping._DESERIALIZER.deserialize(mappingsParser, mapper);

                    client.indices().create(c -> c
                            .index(indexName)
                            .settings(settings)
                            .mappings(mapping)
                    );
                    log.info("Successfully created OpenSearch index '{}'.", indexName);
                } else {
                    log.info("OpenSearch index '{}' already exists.", indexName);
                }
                return; // Success
            } catch (Exception e) {
                log.warn("Attempt {}/{} to connect and initialize OpenSearch index '{}' failed. Retrying in {} seconds... Error: {}", 
                         attempt, maxRetries, indexName, delaySeconds, e.getMessage());
                if (attempt == maxRetries) {
                    log.error("Failed to initialize OpenSearch index '{}' after {} attempts", indexName, maxRetries, e);
                    throw new RuntimeException("OpenSearch index initialization error", e);
                }
                try {
                    Thread.sleep(delaySeconds * 1000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
            }
        }
    }
}
