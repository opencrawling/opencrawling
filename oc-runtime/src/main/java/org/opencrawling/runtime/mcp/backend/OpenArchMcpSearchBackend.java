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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.openarch.output.client.OpenArchClient;
import org.opencrawling.openarch.output.security.OpenArchSecurityMapper;
import org.opencrawling.runtime.mcp.McpAclEvaluator;
import org.opencrawling.runtime.mcp.McpVectorSearchBackend;
import org.opencrawling.runtime.mcp.McpVectorServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "opencrawling.mcp.server.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "spring.opencrawling.output.type", havingValue = "openarch")
public class OpenArchMcpSearchBackend implements McpVectorSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(OpenArchMcpSearchBackend.class);

    private final OpenArchClient client;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.opencrawling.output.openarch.repository-id:default}")
    private String repositoryId;

    @Value("${spring.opencrawling.output.openarch.embeddings-type:mxbai-embed-large}")
    private String embeddingsType;

    @Value("${spring.opencrawling.output.openarch.dimensions:1024}")
    private int defaultDimensions;

    @Autowired
    public OpenArchMcpSearchBackend(
            @Value("${spring.opencrawling.output.openarch.endpoint-url:http://localhost:8080}") String endpointUrl,
            @Value("${spring.opencrawling.output.openarch.username:Administrator}") String username,
            @Value("${spring.opencrawling.output.openarch.password:Administrator}") String password,
            @Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel ollamaEmbeddingModel,
            @Autowired(required = false) @Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        this.client = new OpenArchClient(endpointUrl, username, password);
        this.embeddingModel = (ollamaEmbeddingModel != null) ? ollamaEmbeddingModel : openAiEmbeddingModel;
        log.info("Initialized OpenArchMcpSearchBackend pointing to {} (Repository: {})", endpointUrl, repositoryId);
    }

    public OpenArchMcpSearchBackend(OpenArchClient client, EmbeddingModel embeddingModel) {
        this.client = client;
        this.embeddingModel = embeddingModel;
        this.repositoryId = "default";
        this.embeddingsType = "mxbai-embed-large";
        this.defaultDimensions = 1024;
    }

    @Override
    public String backendType() {
        return "openarch";
    }

    @Override
    public List<McpVectorServer.DocumentSearchResult> secureVectorSearch(
            String query,
            String userPrincipal,
            List<String> userRoles,
            int maxResults,
            double minScore,
            Integer dimensions) {

        log.info("Executing OpenArch MCP secure vector search for principal '{}' with roles {} (Query: '{}')",
                userPrincipal, userRoles, query);

        List<Double> vector = Collections.emptyList();
        if (embeddingModel != null) {
            float[] floats = embeddingModel.embed(query);
            vector = new ArrayList<>(floats.length);
            for (float f : floats) {
                vector.add((double) f);
            }
        }

        List<McpVectorServer.DocumentSearchResult> results = new ArrayList<>();
        Map<String, JsonNode> docCache = new HashMap<>();
        try {
            JsonNode searchRes = client.queryEmbedding(
                    repositoryId,
                    embeddingsType,
                    vector,
                    query,
                    null,
                    maxResults,
                    0
            );

            if (searchRes != null && searchRes.has("embeddings")) {
                JsonNode embeddingsNode = searchRes.get("embeddings");
                if (embeddingsNode.isArray()) {
                    for (JsonNode emb : embeddingsNode) {
                        String id = emb.hasNonNull("id") ? emb.get("id").asText() : "";
                        String docId = emb.hasNonNull("docId") ? emb.get("docId").asText() : id;
                        String text = emb.hasNonNull("text") ? emb.get("text").asText() : "";
                        double score = emb.hasNonNull("score") ? emb.get("score").asDouble() : 1.0;

                        if (score < minScore) {
                            continue;
                        }

                        Map<String, Object> metadata = extractMetadata(emb);
                        metadata.put("docId", docId);
                        metadata.put("embeddingsType", embeddingsType);
                        if (emb.hasNonNull("blobref")) {
                            metadata.put("blobref", emb.get("blobref").asText());
                        }

                        // If embedding chunk doesn't carry security/acl/sys_acl, check parent document in OpenArch
                        if (!metadata.containsKey("security") && !metadata.containsKey("acl")
                                && !metadata.containsKey("sys_acl") && docId != null && !docId.isBlank()) {
                            JsonNode docNode = docCache.computeIfAbsent(docId, dId -> {
                                try {
                                    return client.getDocument(repositoryId, dId);
                                } catch (Exception e) {
                                    log.debug("Could not retrieve document {} for OpenArch ACL check: {}", dId, e.getMessage());
                                    return null;
                                }
                            });
                            if (docNode != null) {
                                Map<String, Object> docMeta = extractMetadata(docNode);
                                if (docMeta.containsKey("security")) {
                                    metadata.put("security", docMeta.get("security"));
                                }
                                if (docMeta.containsKey("acl")) {
                                    metadata.put("acl", docMeta.get("acl"));
                                }
                                if (docMeta.containsKey("sys_acl")) {
                                    metadata.put("sys_acl", docMeta.get("sys_acl"));
                                }
                            }
                        }

                        if (!McpAclEvaluator.isAccessible(metadata, userPrincipal, userRoles)) {
                            continue;
                        }

                        String realAcl = resolveRealAcl(metadata);

                        results.add(new McpVectorServer.DocumentSearchResult(
                                id,
                                "openarch://" + repositoryId + "/" + docId,
                                text,
                                metadata,
                                realAcl,
                                score
                        ));

                        if (results.size() >= maxResults) {
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("OpenArch MCP search failed: {}", e.getMessage(), e);
        }
        return results;
    }

    @Override
    public McpVectorServer.DocumentDetailsResult getDocumentContent(
            String documentUri,
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions) {

        log.info("Executing OpenArch MCP getDocumentContent for URI '{}' by principal '{}'", documentUri, userPrincipal);
        String docId = documentUri.replaceFirst("openarch://[^/]+/", "");

        try {
            JsonNode docNode = client.getDocument(repositoryId, docId);
            if (docNode != null) {
                Map<String, Object> meta = extractMetadata(docNode);
                if (!McpAclEvaluator.isAccessible(meta, userPrincipal, userRoles)) {
                    log.warn("Access denied for principal '{}' with roles {} to OpenArch document '{}'",
                            userPrincipal, userRoles, documentUri);
                    return null;
                }

                String title = docNode.hasNonNull("sys_title") ? docNode.get("sys_title").asText()
                        : (docNode.hasNonNull("sys_name") ? docNode.get("sys_name").asText() : docId);
                String description = docNode.hasNonNull("sys_description") ? docNode.get("sys_description").asText() : "";
                String realAcl = resolveRealAcl(meta);

                return new McpVectorServer.DocumentDetailsResult(
                        docId,
                        documentUri,
                        title + "\n\n" + description,
                        meta,
                        realAcl
                );
            }
        } catch (Exception e) {
            log.error("Failed to retrieve OpenArch document content for {}: {}", docId, e.getMessage(), e);
        }
        return null;
    }

    @Override
    public List<Map<String, Object>> listAccessibleSources(
            String userPrincipal,
            List<String> userRoles,
            Integer dimensions) {

        List<Map<String, Object>> sources = new ArrayList<>();
        try {
            JsonNode res = client.query(repositoryId, "SELECT * FROM SysFile", 50, 0, null);
            if (res != null && res.has("documents")) {
                for (JsonNode doc : res.get("documents")) {
                    Map<String, Object> meta = extractMetadata(doc);
                    if (!McpAclEvaluator.isAccessible(meta, userPrincipal, userRoles)) {
                        continue;
                    }
                    String id = doc.hasNonNull("id") ? doc.get("id").asText() : "";
                    String name = doc.hasNonNull("sys_name") ? doc.get("sys_name").asText() : id;
                    String realAcl = resolveRealAcl(meta);
                    Map<String, Object> sourceMap = new HashMap<>();
                    sourceMap.put("id", id);
                    sourceMap.put("name", name);
                    sourceMap.put("uri", "openarch://" + repositoryId + "/" + id);
                    sourceMap.put("acl", realAcl);
                    sources.add(sourceMap);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to list accessible OpenArch sources: {}", e.getMessage());
        }
        return sources;
    }

    private Map<String, Object> extractMetadata(JsonNode node) {
        Map<String, Object> metadata = new HashMap<>();
        if (node == null || !node.isObject()) {
            return metadata;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode val = field.getValue();
            if (val.isTextual()) {
                metadata.put(field.getKey(), val.asText());
            } else if (val.isNumber()) {
                metadata.put(field.getKey(), val.numberValue());
            } else if (val.isBoolean()) {
                metadata.put(field.getKey(), val.asBoolean());
            } else if (val.isArray() || val.isObject()) {
                try {
                    metadata.put(field.getKey(), objectMapper.convertValue(val, Object.class));
                } catch (Exception ignored) {
                    metadata.put(field.getKey(), val.toString());
                }
            }
        }
        resolveOpenArchSecurity(metadata);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private void resolveOpenArchSecurity(Map<String, Object> metadata) {
        if (metadata == null) return;

        Object sysAclObj = metadata.get("sys_acl");
        if (sysAclObj != null && !metadata.containsKey("security")) {
            List<Map<String, Object>> aces = new ArrayList<>();
            if (sysAclObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m) {
                        aces.add((Map<String, Object>) m);
                    }
                }
            }
            if (!aces.isEmpty()) {
                SecurityConfig sc = OpenArchSecurityMapper.fromOpenArchAcl(aces, true);
                metadata.put("security", sc);
                List<String> allowed = sc.permissions().stream()
                        .filter(p -> !"deny".equalsIgnoreCase(p.access()))
                        .map(PermissionRule::identity)
                        .toList();
                if (!allowed.isEmpty() && !metadata.containsKey("acl")) {
                    metadata.put("acl", String.join(",", allowed));
                }
            }
        } else if (metadata.get("security") instanceof SecurityConfig sc) {
            List<String> allowed = sc.permissions().stream()
                    .filter(p -> !"deny".equalsIgnoreCase(p.access()))
                    .map(PermissionRule::identity)
                    .toList();
            if (!allowed.isEmpty() && !metadata.containsKey("acl")) {
                metadata.put("acl", String.join(",", allowed));
            }
        }
    }

    private String resolveRealAcl(Map<String, Object> metadata) {
        if (metadata == null) {
            return "public";
        }
        if (metadata.containsKey("acl") && metadata.get("acl") != null) {
            return String.valueOf(metadata.get("acl"));
        }
        if (metadata.containsKey("acl_ss") && metadata.get("acl_ss") != null) {
            return String.valueOf(metadata.get("acl_ss"));
        }
        if (metadata.get("security") instanceof SecurityConfig sc) {
            List<String> allowed = sc.permissions().stream()
                    .filter(p -> !"deny".equalsIgnoreCase(p.access()))
                    .map(PermissionRule::identity)
                    .toList();
            if (!allowed.isEmpty()) {
                return String.join(",", allowed);
            }
        }
        return "public";
    }
}
