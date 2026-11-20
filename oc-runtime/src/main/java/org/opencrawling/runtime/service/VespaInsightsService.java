/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.opencrawling.vespa.VespaFields;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Backs the admin UI's Vespa Model Insights panel: real health/document-count/query calls against a live
 * Vespa instance, plus an optional UI-triggered schema deploy that coexists with (does not replace) an
 * operator's own CI/CD or {@code vespa} CLI deploy. Mirrors {@link ConnectorCheckerService}'s style - plain
 * {@link HttpClient} calls, no Spring context required to test.
 */
@Service
public class VespaInsightsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_DOCUMENT_TYPE = "opencrawling_chunk";
    private static final List<String> SCHEMA_RESOURCE_NAMES = List.of(
            "opencrawling_chunk.sd",
            "opencrawling_chunk_384.sd",
            "opencrawling_chunk_768.sd",
            "opencrawling_chunk_1024.sd"
    );

    private final EmbeddingModel embeddingModel;

    public VespaInsightsService(@Autowired(required = false) @Qualifier("ollamaEmbeddingModel") EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    public VespaHealthResult checkHealth(String endpoint) {
        String cleanEndpoint = trimTrailingSlash(endpoint);
        try {
            validateEndpoint(cleanEndpoint);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(cleanEndpoint + "/state/v1/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean up = response.statusCode() == 200 && response.body().contains("\"up\"");
            return new VespaHealthResult(up, up
                    ? "Vespa is healthy at " + cleanEndpoint + "."
                    : "Vespa at " + cleanEndpoint + " returned HTTP " + response.statusCode() + " or a non-\"up\" status.");
        } catch (Exception e) {
            return new VespaHealthResult(false, "Failed to reach Vespa at " + cleanEndpoint + ": " + e.getMessage());
        }
    }

    public List<DocumentTypeCount> getDocumentCounts(String endpoint) {
        String cleanEndpoint = trimTrailingSlash(endpoint);
        List<DocumentTypeCount> results = new ArrayList<>();
        results.add(countFor(cleanEndpoint, DEFAULT_DOCUMENT_TYPE, "Configurable (default)"));
        results.add(countFor(cleanEndpoint, VespaFields.DOCUMENT_TYPE_384, "384"));
        results.add(countFor(cleanEndpoint, VespaFields.DOCUMENT_TYPE_768, "768"));
        results.add(countFor(cleanEndpoint, VespaFields.DOCUMENT_TYPE_1024, "1024"));
        return results;
    }

    private DocumentTypeCount countFor(String endpoint, String documentType, String dimensionLabel) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("yql", "select * from " + documentType + " where true");
            body.put("hits", 0);
            JsonNode response = postSearch(endpoint, body);
            if (response.has("root")) {
                long count = response.path("root").path("fields").path("totalCount").asLong(0);
                return new DocumentTypeCount(documentType, dimensionLabel, count, true);
            }
            return new DocumentTypeCount(documentType, dimensionLabel, 0L, false);
        } catch (Exception e) {
            return new DocumentTypeCount(documentType, dimensionLabel, 0L, false);
        }
    }

    public VespaQueryResult runQuery(String endpoint, String documentType, String queryText, String rankProfile) {
        String cleanEndpoint = trimTrailingSlash(endpoint);
        String effectiveDocumentType = (documentType == null || documentType.isBlank()) ? DEFAULT_DOCUMENT_TYPE : documentType;
        String effectiveRankProfile = (rankProfile == null || rankProfile.isBlank()) ? "default" : rankProfile.toLowerCase();

        if ("default".equals(effectiveRankProfile)) {
            return runBm25Query(cleanEndpoint, effectiveDocumentType, queryText, false, null);
        }

        if (embeddingModel == null) {
            return runBm25Query(cleanEndpoint, effectiveDocumentType, queryText, true,
                    "No embedding model is configured; showing BM25-only results instead of " + effectiveRankProfile + ".");
        }

        try {
            float[] vector = embeddingModel.embed(queryText);
            String queryInputName = resolveQueryInputName(effectiveDocumentType);
            return runVectorQuery(cleanEndpoint, effectiveDocumentType, queryText, effectiveRankProfile, queryInputName, vector);
        } catch (Exception e) {
            return runBm25Query(cleanEndpoint, effectiveDocumentType, queryText, true,
                    "Failed to compute a query embedding (" + e.getMessage() + "); showing BM25-only results instead.");
        }
    }

    private static String resolveQueryInputName(String documentType) {
        if (VespaFields.DOCUMENT_TYPE_384.equals(documentType)) {
            return VespaFields.QUERY_INPUT_384;
        }
        if (VespaFields.DOCUMENT_TYPE_768.equals(documentType)) {
            return VespaFields.QUERY_INPUT_768;
        }
        if (VespaFields.DOCUMENT_TYPE_1024.equals(documentType)) {
            return VespaFields.QUERY_INPUT_1024;
        }
        return VespaFields.QUERY_INPUT_DEFAULT;
    }

    private VespaQueryResult runBm25Query(String endpoint, String documentType, String queryText, boolean degraded, String degradedMessage) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("yql", "select * from " + documentType + " where userQuery()");
            body.put("query", queryText);
            body.put("ranking", "default");
            body.put("hits", 10);
            return toQueryResult(postSearch(endpoint, body), degraded, degradedMessage);
        } catch (Exception e) {
            String message = degradedMessage != null
                    ? degradedMessage + " Query also failed: " + e.getMessage()
                    : "Query failed: " + e.getMessage();
            return new VespaQueryResult(List.of(), 0, true, message);
        }
    }

    private VespaQueryResult runVectorQuery(String endpoint, String documentType, String queryText, String rankProfile,
                                             String queryInputName, float[] vector) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            String yql;
            if ("hybrid".equals(rankProfile)) {
                yql = "select * from " + documentType + " where ({targetHits:10}nearestNeighbor(embedding, " + queryInputName + ")) or userQuery()";
                body.put("query", queryText);
            } else {
                yql = "select * from " + documentType + " where ({targetHits:10}nearestNeighbor(embedding, " + queryInputName + "))";
            }
            body.put("yql", yql);
            ArrayNode vectorValues = body.putArray("input.query(" + queryInputName + ")");
            for (float value : vector) {
                vectorValues.add(value);
            }
            body.put("ranking", rankProfile);
            body.put("hits", 10);
            return toQueryResult(postSearch(endpoint, body), false, null);
        } catch (Exception e) {
            return new VespaQueryResult(List.of(), 0, true, "Vector query failed: " + e.getMessage());
        }
    }

    private static VespaQueryResult toQueryResult(JsonNode response, boolean degraded, String degradedMessage) {
        JsonNode root = response.path("root");
        int totalCount = root.path("fields").path("totalCount").asInt(0);
        List<VespaQueryHit> hits = new ArrayList<>();
        for (JsonNode child : root.path("children")) {
            JsonNode fields = child.path("fields");
            hits.add(new VespaQueryHit(
                    fields.path("chunk_id").asText(""),
                    truncate(fields.path("text").asText("")),
                    fields.path("uri").asText(""),
                    child.path("relevance").asDouble(0)
            ));
        }
        return new VespaQueryResult(hits, totalCount, degraded, degradedMessage);
    }

    private static String truncate(String text) {
        return text.length() <= 240 ? text : text.substring(0, 240) + "...";
    }

    private JsonNode postSearch(String endpoint, ObjectNode body) throws Exception {
        validateEndpoint(endpoint);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/search/"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = MAPPER.readTree(response.body());
        JsonNode errors = json.path("root").path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("Vespa query failed: " + errors.get(0).path("message").asText(errors.toString()));
        }
        return json;
    }

    /**
     * Deploys the schema/services.xml bundled with oc-vespa-output-connector - a convenience for dev/quick-start,
     * fully optional. Operators who manage their own Vespa application package via CI/CD or the {@code vespa}
     * CLI never need to call this; both paths are supported side by side.
     */
    public VespaDeployResult deployBundledSchema(String configEndpoint) {
        try {
            return postDeploy(configEndpoint, buildBundledPackageZip(), "application/zip");
        } catch (Exception e) {
            return new VespaDeployResult(false, "Failed to prepare the bundled schema package: " + e.getMessage(), null);
        }
    }

    /** Pure pass-through: forwards an operator-supplied application package to Vespa unmodified. */
    public VespaDeployResult deployCustomSchema(String configEndpoint, byte[] packageBytes, String contentType) {
        String effectiveContentType = (contentType == null || contentType.isBlank()) ? "application/zip" : contentType;
        if (!effectiveContentType.contains("zip") && !effectiveContentType.contains("gzip")) {
            return new VespaDeployResult(false,
                    "Unsupported content type '" + effectiveContentType + "'; expected application/zip or application/x-gzip.", null);
        }
        return postDeploy(configEndpoint, packageBytes, effectiveContentType);
    }

    private byte[] buildBundledPackageZip() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            addClasspathZipEntry(zip, "vespa-app/services.xml", "services.xml");
            for (String schemaFile : SCHEMA_RESOURCE_NAMES) {
                addClasspathZipEntry(zip, "vespa-app/schemas/" + schemaFile, "schemas/" + schemaFile);
            }
        }
        return buffer.toByteArray();
    }

    private void addClasspathZipEntry(ZipOutputStream zip, String classpathResource, String entryName) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Bundled Vespa schema resource not found on classpath: " + classpathResource);
            }
            zip.putNextEntry(new ZipEntry(entryName));
            in.transferTo(zip);
            zip.closeEntry();
        }
    }

    private VespaDeployResult postDeploy(String configEndpoint, byte[] packageBytes, String contentType) {
        String cleanEndpoint = trimTrailingSlash(configEndpoint);
        try {
            validateEndpoint(cleanEndpoint);
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(cleanEndpoint + "/application/v2/tenant/default/prepareandactivate"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(packageBytes))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            boolean activated = response.statusCode() == 200 && response.body().contains("\"activated\":true");
            String message = activated
                    ? "Application package deployed and activated successfully."
                    : "Vespa deploy returned HTTP " + response.statusCode() + ".";
            return new VespaDeployResult(activated, message, response.body());
        } catch (Exception e) {
            return new VespaDeployResult(false, "Failed to deploy application package to " + cleanEndpoint + ": " + e.getMessage(), null);
        }
    }

    private static String trimTrailingSlash(String url) {
        return (url != null && url.endsWith("/")) ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * This panel's whole purpose is reaching an operator-configured Vespa instance, which in real
     * deployments is almost always on a private network - so unlike a typical SSRF fix, blocking
     * private/internal addresses would break the feature outright and isn't attempted here. What's
     * always safe to reject regardless of network topology is a non-HTTP(S) scheme, which blocks the
     * classic protocol-smuggling SSRF vectors (file://, gopher://, etc.).
     */
    private static void validateEndpoint(String endpoint) {
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed endpoint URL: " + endpoint);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Endpoint must be an http or https URL, got: " + endpoint);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Endpoint is missing a host: " + endpoint);
        }
    }

    public record VespaHealthResult(boolean up, String message) {}

    public record DocumentTypeCount(String documentType, String dimensionLabel, long count, boolean available) {}

    public record VespaQueryHit(String chunkId, String text, String uri, double relevance) {}

    public record VespaQueryResult(List<VespaQueryHit> hits, int totalCount, boolean degraded, String message) {}

    public record VespaDeployResult(boolean success, String message, String rawResponse) {}
}
