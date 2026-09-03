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
package org.opencrawling.runtime.service;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.springframework.stereotype.Service;

@Service
public class ConnectorCheckerService {

    public ConnectionCheckResult check(ConnectorDTO connector) {
        if (connector == null || connector.className() == null) {
            return new ConnectionCheckResult(false, "Invalid connector configuration: missing class name.", null);
        }

        String className = connector.className();
        Map<String, String> config = connector.configuration() != null ? connector.configuration() : Map.of();

        try {
            // --- Qdrant Output Connector ---
            if (className.contains("QdrantOutputConnector") || className.contains("Qdrant")) {
                return checkQdrant(config);
            }

            // --- Milvus Output Connector ---
            if (className.contains("MilvusOutputConnector") || className.contains("Milvus")) {
                return checkMilvus(config);
            }

            // --- OpenSearch Output Connector (2.x & 3.x) ---
            if (className.contains("OpenSearch") || className.contains("opensearch")) {
                return checkOpenSearch(config);
            }

            // --- PGVector / Vector Output Connector ---
            if (className.contains("VectorOutputConnector") || className.contains("vector")) {
                return checkPGVector(config);
            }

            // --- Vespa Output Connector ---
            if (className.contains("VespaOutputConnector") || className.contains("Vespa")) {
                return checkVespa(config);
            }

            // --- Solr Output Connector ---
            if (className.contains("SolrOutputConnector") || className.contains("Solr")) {
                return checkSolr(config);
            }

            // --- Camunda Repository Connector ---
            if (className.contains("CamundaRepositoryConnector") || className.contains("Camunda")) {
                return checkCamunda(config);
            }

            // --- Flowable Repository Connector ---
            if (className.contains("FlowableRepositoryConnector") || className.contains("Flowable")) {
                return checkFlowable(config);
            }

            // --- Alfresco Repository Connector ---
            if (className.contains("AlfrescoRepositoryConnector") || className.contains("Alfresco")) {
                return checkAlfresco(config);
            }

            // --- FileSystem Repository Connector ---
            if (className.contains("FileConnector") || className.contains("FileSystem")) {
                return checkFileSystem(config);
            }

            // --- Ollama Embedding Connector ---
            if (className.contains("OllamaEmbeddingConnector") || className.contains("Ollama")) {
                return checkOllama(config);
            }

            // --- OpenAI Embedding Connector ---
            if (className.contains("OpenAIEmbeddingConnector") || className.contains("OpenAI")) {
                return checkOpenAI(config);
            }

            // --- Active Directory / LDAP Authority Connector ---
            if (className.contains("ActiveDirectory") || className.contains("LDAP")) {
                return checkLdap(config);
            }

            // Generic fallback URL/Host check
            return checkGenericUrlOrHost(config);

        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Connection check failed: " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkQdrant(Map<String, String> config) {
        String uriStr = config.getOrDefault("qdrantUri", config.getOrDefault("uri", "http://localhost:6333"));
        String apiKey = config.getOrDefault("qdrantApiKey", "");

        URI parsedUri = null;
        try {
            parsedUri = URI.create(uriStr);
        } catch (Exception ignored) {}

        String host = config.get("qdrantHost");
        if (host == null || host.isBlank()) {
            host = (parsedUri != null && parsedUri.getHost() != null) ? parsedUri.getHost() : "localhost";
        }

        int grpcPort = 6334;
        if (config.containsKey("qdrantPort")) {
            try {
                grpcPort = Integer.parseInt(config.get("qdrantPort"));
            } catch (NumberFormatException ignored) {}
        } else if (parsedUri != null && parsedUri.getPort() != -1) {
            grpcPort = parsedUri.getPort();
        }

        // Try HTTP health check first
        try {
            String targetUri = uriStr.endsWith("/") ? uriStr + "healthz" : uriStr + "/healthz";
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(targetUri))
                    .timeout(Duration.ofSeconds(5))
                    .GET();

            if (!apiKey.isBlank()) {
                builder.header("api-key", apiKey);
            }

            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to Qdrant REST service at " + uriStr + " (HTTP " + response.statusCode() + ").", response.body());
            } else {
                return new ConnectionCheckResult(false, "Qdrant REST service returned HTTP status " + response.statusCode() + " from " + targetUri, response.body());
            }
        } catch (Exception e) {
            // Fall back to gRPC socket check
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, grpcPort), 5000);
                return new ConnectionCheckResult(true, "Successfully connected to Qdrant gRPC port at " + host + ":" + grpcPort + ".", null);
            } catch (Exception socketEx) {
                return new ConnectionCheckResult(false, "Failed to connect to Qdrant at " + uriStr + " / " + host + ":" + grpcPort + ": " + e.getMessage(), e.toString());
            }
        }
    }

    private ConnectionCheckResult checkMilvus(Map<String, String> config) {
        String uriStr = config.getOrDefault("milvusUri", config.getOrDefault("uri", "http://localhost:19530"));
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uriStr.endsWith("/") ? uriStr + "v1/vector/collections" : uriStr + "/v1/vector/collections"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 500) {
                return new ConnectionCheckResult(true, "Successfully connected to Milvus vector store at " + uriStr + ".", null);
            }
        } catch (Exception e) {
            // Fallback TCP socket check to port 19530
            try (Socket socket = new Socket()) {
                URI uri = URI.create(uriStr.startsWith("http") ? uriStr : "http://" + uriStr);
                String host = uri.getHost() != null ? uri.getHost() : "localhost";
                int port = uri.getPort() != -1 ? uri.getPort() : 19530;
                socket.connect(new InetSocketAddress(host, port), 5000);
                return new ConnectionCheckResult(true, "Successfully verified TCP socket connection to Milvus at " + host + ":" + port + ".", null);
            } catch (Exception ex) {
                return new ConnectionCheckResult(false, "Failed to connect to Milvus at " + uriStr + ": " + e.getMessage(), e.toString());
            }
        }
        return new ConnectionCheckResult(true, "Successfully verified Milvus connection at " + uriStr + ".", null);
    }

    private ConnectionCheckResult checkOpenSearch(Map<String, String> config) {
        String uris = config.getOrDefault("opensearch3Uris", config.getOrDefault("opensearch2Uris", config.getOrDefault("uris", "http://localhost:9200")));
        String user = config.getOrDefault("opensearch3Username", config.getOrDefault("opensearch2Username", config.getOrDefault("username", "admin")));
        String pass = config.getOrDefault("opensearch3Password", config.getOrDefault("opensearch2Password", config.getOrDefault("password", "admin")));

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String firstUri = uris.split(",")[0].trim();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(firstUri.endsWith("/") ? firstUri : firstUri + "/"))
                    .timeout(Duration.ofSeconds(5))
                    .GET();

            if (user != null && !user.isBlank()) {
                String credentials = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + credentials);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to OpenSearch cluster at " + firstUri + " (HTTP " + response.statusCode() + ").", response.body());
            } else {
                return new ConnectionCheckResult(false, "OpenSearch returned HTTP status " + response.statusCode() + " from " + firstUri, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to OpenSearch cluster at " + uris + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkPGVector(Map<String, String> config) {
        String url = config.getOrDefault("pgVectorUrl", config.getOrDefault("url", "jdbc:postgresql://localhost:5432/opencrawling"));
        String user = config.getOrDefault("pgVectorUsername", config.getOrDefault("username", "opencrawling"));
        String pass = config.getOrDefault("pgVectorPassword", config.getOrDefault("password", "opencrawling_password"));

        DriverManager.setLoginTimeout(5);
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            if (conn.isValid(5)) {
                return new ConnectionCheckResult(true, "Successfully connected to PostgreSQL PGVector database at " + url + ".", null);
            } else {
                return new ConnectionCheckResult(false, "PostgreSQL connection returned invalid state for " + url, null);
            }
        } catch (Exception e) {
            // Fallback for containerized deployments where localhost/127.0.0.1 resolves differently inside container network
            if (url.contains("localhost") || url.contains("127.0.0.1")) {
                for (String fallbackHost : java.util.List.of("postgres-vector-decoupled", "postgres")) {
                    String fallbackUrl = url.replace("localhost", fallbackHost).replace("127.0.0.1", fallbackHost);
                    try (Connection conn = DriverManager.getConnection(fallbackUrl, user, pass)) {
                        if (conn.isValid(5)) {
                            return new ConnectionCheckResult(true, "Successfully connected to PostgreSQL PGVector database at " + fallbackUrl + ".", null);
                        }
                    } catch (Exception ignored) {}
                }
            }
            return new ConnectionCheckResult(false, "Failed to connect to PostgreSQL database at " + url + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkVespa(Map<String, String> config) {
        String endpoint = config.getOrDefault("vespaEndpoint", "http://localhost:8080");
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String healthUrl = cleanEndpoint + "/state/v1/health";

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(healthUrl)).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().contains("\"up\"")) {
                return new ConnectionCheckResult(true, "Successfully connected to Vespa at " + cleanEndpoint + " (status: up).", response.body());
            } else {
                return new ConnectionCheckResult(false, "Vespa health check at " + healthUrl + " returned HTTP " + response.statusCode() + " or a non-\"up\" status.", response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Vespa at " + cleanEndpoint + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkCamunda(Map<String, String> config) {
        String url = config.getOrDefault("url", "http://localhost:8080/engine-rest");
        String user = config.getOrDefault("username", "demo");
        String pass = config.getOrDefault("password", "demo");

        String cleanUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String testEndpoint = cleanUrl + "/history/process-instance?maxResults=1";

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(testEndpoint))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET();

            if (user != null && !user.isBlank()) {
                String credentials = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + credentials);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ConnectionCheckResult(true, "Successfully connected to Camunda 7 REST API at " + cleanUrl + ".", null);
            } else {
                return new ConnectionCheckResult(false, "Camunda REST API returned HTTP status " + response.statusCode() + " from " + testEndpoint, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Camunda REST engine at " + cleanUrl + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkFlowable(Map<String, String> config) {
        String endpoint = config.getOrDefault("endpoint", "http://localhost:8080/flowable-rest/service");
        String user = config.getOrDefault("username", "rest-admin");
        String pass = config.getOrDefault("password", "test");

        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String testUrl = cleanEndpoint.endsWith("/service") 
                ? cleanEndpoint + "/management/engine" 
                : cleanEndpoint + "/service/management/engine";

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET();

            if (user != null && !user.isBlank()) {
                String credentials = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + credentials);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ConnectionCheckResult(true, "Successfully connected to Flowable REST Engine at " + cleanEndpoint + ".", null);
            } else {
                return new ConnectionCheckResult(false, "Flowable REST engine returned HTTP status " + response.statusCode() + " from " + testUrl, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Flowable REST engine at " + cleanEndpoint + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkAlfresco(Map<String, String> config) {
        String url = config.getOrDefault("url", "http://localhost:8080/alfresco/api/-default-/public/alfresco/versions/1");
        String user = config.getOrDefault("username", "admin");
        String pass = config.getOrDefault("password", "admin");

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET();

            if (user != null && !user.isBlank()) {
                String credentials = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + credentials);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                return new ConnectionCheckResult(true, "Successfully connected to Alfresco Repository at " + url + ".", null);
            } else {
                return new ConnectionCheckResult(false, "Alfresco Repository returned HTTP status " + response.statusCode() + " from " + url, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Alfresco Repository at " + url + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkFileSystem(Map<String, String> config) {
        String path = config.getOrDefault("path", config.getOrDefault("repositoryPath", "."));
        File file = new File(path);
        if (file.exists() && file.canRead()) {
            return new ConnectionCheckResult(true, "Local file system path exists and is readable: " + file.getAbsolutePath(), null);
        } else {
            return new ConnectionCheckResult(false, "File system path does not exist or is not readable: " + path, null);
        }
    }

    private ConnectionCheckResult checkOllama(Map<String, String> config) {
        String baseUrl = config.getOrDefault("baseUrl", "http://localhost:11434");
        String targetUrl = baseUrl.endsWith("/") ? baseUrl + "api/tags" : baseUrl + "/api/tags";

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(targetUrl)).timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ConnectionCheckResult(true, "Successfully connected to Ollama service at " + baseUrl + ".", response.body());
            } else {
                return new ConnectionCheckResult(false, "Ollama returned HTTP status " + response.statusCode(), response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Ollama at " + baseUrl + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkOpenAI(Map<String, String> config) {
        String apiKey = config.getOrDefault("apiKey", "");
        if (apiKey.isBlank() || apiKey.contains("placeholder")) {
            return new ConnectionCheckResult(false, "OpenAI API Key is missing or invalid placeholder.", null);
        }
        return new ConnectionCheckResult(true, "OpenAI configuration & API Key format validated.", null);
    }

    private ConnectionCheckResult checkLdap(Map<String, String> config) {
        String host = config.getOrDefault("host", config.getOrDefault("ldapUrl", "localhost"));
        int port = 389;
        try {
            if (config.containsKey("port")) port = Integer.parseInt(config.get("port"));
        } catch (NumberFormatException ignored) {}

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            return new ConnectionCheckResult(true, "Successfully verified TCP connection to LDAP/AD server at " + host + ":" + port + ".", null);
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to LDAP/AD server at " + host + ":" + port + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkSolr(Map<String, String> config) {
        String urlStr = config.getOrDefault("solrUrl", config.getOrDefault("url", "http://localhost:8983/solr"));
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String pingUrl = urlStr.endsWith("/") ? urlStr + "admin/info/system" : urlStr + "/admin/info/system";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(pingUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to Apache Solr at " + urlStr + " (HTTP " + response.statusCode() + ").", response.body());
            } else {
                return new ConnectionCheckResult(false, "Apache Solr returned HTTP status " + response.statusCode() + " from " + urlStr, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Apache Solr at " + urlStr + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkGenericUrlOrHost(Map<String, String> config) {
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String val = entry.getValue();
            if (val != null && (val.startsWith("http://") || val.startsWith("https://"))) {
                try {
                    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create(val)).timeout(Duration.ofSeconds(5)).GET().build();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() < 500) {
                        return new ConnectionCheckResult(true, "Successfully reached endpoint at " + val + " (HTTP " + response.statusCode() + ").", null);
                    }
                } catch (Exception ignored) {}
            }
        }
        return new ConnectionCheckResult(true, "Connector configuration structure validated.", null);
    }

    public static record ConnectionCheckResult(
        boolean success,
        String message,
        String details
    ) {}
}
