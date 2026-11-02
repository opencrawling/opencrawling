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
package org.opencrawling.runtime.mcp.backend;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.opencrawling.core.security.ResourceRef;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.opencrawling.solr.SolrConstants;
import org.opencrawling.solr.config.SolrOutputProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "solr")
public class SolrMcpSearchBackend extends GatedMcpSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(SolrMcpSearchBackend.class);

    private final SolrClient solrClient;
    private final SolrOutputProperties properties;
    private final EmbeddingModel embeddingModel;

    @Autowired
    public SolrMcpSearchBackend(
            SolrClient solrClient,
            SolrOutputProperties properties,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.solrClient = solrClient;
        this.properties = properties;
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized SolrMcpSearchBackend for collection '{}'.", properties.collection());
    }

    @Override
    public String backendType() {
        return "solr";
    }

    @Override
    public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions
    ) {
        log.info("Solr backend secure search. Query: '{}', Principal: '{}'", query, userPrincipal);
        int limit = maxResults > 0 ? maxResults : 5;

        float[] vector = null;
        if (embeddingModel != null && query != null && !query.isBlank()) {
            try {
                vector = embeddingModel.embed(query);
            } catch (Exception e) {
                log.warn("Failed to generate embedding for Solr search: {}", e.getMessage());
            }
        }

        if (vector == null) {
            int dims = dimensions != null ? dimensions : properties.dimensions();
            vector = new float[dims];
            vector[0] = 1.0f;
        }

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");

        int topK = Math.max(limit * 3, 20);
        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", "{!knn f=" + SolrConstants.FIELD_EMBEDDINGS + " topK=" + topK + "}" + sb.toString());
        params.set("rows", topK);
        params.set("fl", "*,score");

        try {
            QueryResponse response = solrClient.query(properties.collection(), params);
            SolrDocumentList docs = response.getResults();

            List<McpVectorServer.DocumentSearchResult> results = new ArrayList<>();
            if (docs != null) {
                List<SolrDocument> candidates = docs.stream()
                        .filter(doc -> scoreOf(doc) >= minScore)
                        .toList();
                for (SolrDocument doc : readable(userPrincipal, userRoles, candidates,
                        doc -> ResourceRef.fromMetadata(doc.getFieldValueMap()))) {
                    Map<String, Object> fieldMap = doc.getFieldValueMap();
                    double score = scoreOf(doc);
                    String id = String.valueOf(doc.getFieldValue(SolrConstants.FIELD_ID));
                    String uri = doc.getFieldValue(SolrConstants.FIELD_URI) != null ? String.valueOf(doc.getFieldValue(SolrConstants.FIELD_URI)) : "";
                    String text = doc.getFieldValue(SolrConstants.FIELD_TEXT) != null ? String.valueOf(doc.getFieldValue(SolrConstants.FIELD_TEXT)) : "";
                    String acl = doc.getFieldValue(SolrConstants.FIELD_ACL) != null ? String.valueOf(doc.getFieldValue(SolrConstants.FIELD_ACL)) : "public";
                    results.add(new McpVectorServer.DocumentSearchResult(id, uri, text, fieldMap, acl, score));
                    if (results.size() >= limit) break;
                }
            }
            return results;
        } catch (Exception e) {
            log.error("Failed to perform secure vector search in Solr: {}", e.getMessage(), e);
            throw new RuntimeException("Error executing secure vector search in Solr", e);
        }
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Solr backend get document content. URI: '{}', Principal: '{}'", documentUri, userPrincipal);

        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", "uri:\"" + ClientUtils.escapeQueryChars(documentUri) + "\"");
        params.set("rows", 20);

        try {
            QueryResponse response = solrClient.query(properties.collection(), params);
            SolrDocumentList docs = response.getResults();

            if (docs != null) {
                for (SolrDocument doc : readable(userPrincipal, userRoles, docs,
                        doc -> ResourceRef.fromMetadata(doc.getFieldValueMap()))) {
                    Map<String, Object> fieldMap = doc.getFieldValueMap();
                    String id = String.valueOf(doc.getFieldValue(SolrConstants.FIELD_ID));
                    String uri = doc.getFieldValue(SolrConstants.FIELD_URI) != null ? String.valueOf(doc.getFieldValue(SolrConstants.FIELD_URI)) : "";
                    String text = doc.getFieldValue(SolrConstants.FIELD_TEXT) != null ? String.valueOf(doc.getFieldValue(SolrConstants.FIELD_TEXT)) : "";
                    String acl = doc.getFieldValue(SolrConstants.FIELD_ACL) != null ? String.valueOf(doc.getFieldValue(SolrConstants.FIELD_ACL)) : "public";
                    return new McpVectorServer.DocumentDetailsResult(id, uri, text, fieldMap, acl);
                }
            }
            throw new NoSuchElementException("Document not found or access denied.");
        } catch (NoSuchElementException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to retrieve document details in Solr: {}", e.getMessage(), e);
            throw new RuntimeException("Error retrieving document details from Solr", e);
        }
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions
    ) {
        log.info("Solr backend list accessible sources. Principal: '{}'", userPrincipal);

        ModifiableSolrParams params = new ModifiableSolrParams();
        params.set("q", "*:*");
        params.set("rows", 200);

        try {
            QueryResponse response = solrClient.query(properties.collection(), params);
            SolrDocumentList docs = response.getResults();

            List<Map<String, Object>> summaries = new ArrayList<>();
            if (docs != null) {
                for (SolrDocument doc : readable(userPrincipal, userRoles, docs,
                        doc -> ResourceRef.fromMetadata(doc.getFieldValueMap()))) {
                    Map<String, Object> summary = new HashMap<>();
                    summary.put("id", doc.getFieldValue(SolrConstants.FIELD_ID));
                    summary.put("uri", doc.getFieldValue(SolrConstants.FIELD_URI));
                    summary.put("acl", doc.getFieldValue(SolrConstants.FIELD_ACL));
                    summary.put("security", doc.getFieldValue("security"));
                    summary.put("lastModified", doc.getFieldValue(SolrConstants.FIELD_LAST_MODIFIED));
                    summaries.add(summary);
                }
            }
            return summaries.stream().distinct().toList();
        } catch (Exception e) {
            log.error("Failed to list accessible sources in Solr: {}", e.getMessage(), e);
            throw new RuntimeException("Error listing accessible sources from Solr", e);
        }
    }

    private static double scoreOf(SolrDocument doc) {
        return doc.getFieldValue("score") instanceof Number n ? n.doubleValue() : 0.0;
    }
}
