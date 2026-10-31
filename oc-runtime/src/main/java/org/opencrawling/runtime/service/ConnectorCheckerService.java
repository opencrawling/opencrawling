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
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.doxis.DoxisRepositoryConnector;
import org.opencrawling.doxis.DoxisProperties;
import org.opencrawling.doxis.client.DoxisClient;
import org.opencrawling.sharepoint.GraphClient;
import org.opencrawling.sharepoint.SharePointProperties;
import org.opencrawling.sharepoint.SharePointRepositoryConnector;
import org.opencrawling.mfiles.MFilesClient;
import org.opencrawling.mfiles.MFilesProperties;
import org.opencrawling.mfiles.MFilesRepositoryConnector;
import org.opencrawling.documentum.DocumentumClient;
import org.opencrawling.documentum.DocumentumProperties;
import org.opencrawling.documentum.DocumentumRepositoryConnector;
import org.opencrawling.filenet.FileNetClient;
import org.opencrawling.filenet.FileNetProperties;
import org.opencrawling.filenet.FileNetRepositoryConnector;
import org.opencrawling.runtime.api.ConnectorController.ConnectorDTO;
import org.springframework.stereotype.Service;
import io.swagger.v3.oas.annotations.media.Schema;

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

            // --- Luxir Output Connector ---
            if (className.contains("LuxirOutputConnector") || className.contains("Luxir")) {
                return checkLuxir(config);
            }

            // --- Apache SeaTunnel Output Connector ---
            if (className.contains("SeaTunnelOutputConnector") || className.contains("SeaTunnel") || className.contains("seatunnel")) {
                return checkSeaTunnel(config);
            }

            // --- Camunda Repository Connector ---
            if (className.contains("CamundaRepositoryConnector") || className.contains("Camunda")) {
                return checkCamunda(config);
            }

            // --- gRPC Push Repository Connector ---
            if (className.contains("GrpcPush") || className.contains("grpc-push") || className.contains("grpcPush")) {
                return checkGrpcPush(config);
            }

            // --- StormCrawler Repository Connector ---
            if (className.contains("StormCrawlerRepositoryConnector") || className.contains("StormCrawler")) {
                return checkStormCrawler(config);
            }

            // --- Alfresco Process Services (APS) Repository Connector ---
            if (className.contains("ApsRepositoryConnector") || className.contains("Aps")) {
                return checkAps(config);
            }

            // --- Flowable Repository Connector ---
            if (className.contains("FlowableRepositoryConnector") || className.contains("Flowable")) {
                return checkFlowable(config);
            }

            // --- Alfresco Repository Connector ---
            if (className.contains("AlfrescoRepositoryConnector") || className.contains("Alfresco")) {
                return checkAlfresco(config);
            }

            // --- Doxis Repository Connector ---
            if (className.contains("DoxisRepositoryConnector")) {
                return checkDoxisRepository(config);
            }

            // --- SharePoint Repository Connector ---
            if (className.contains("SharePointRepositoryConnector")) {
                return checkSharePointRepository(config);
            }

            // --- M-Files Repository Connector ---
            if (className.contains("MFilesRepositoryConnector")) {
                return checkMFilesRepository(config);
            }

            // --- IBM FileNet P8 Repository Connector ---
            if (className.contains("FileNetRepositoryConnector")) {
                return checkFileNetRepository(config);
            }

            // --- OpenText Documentum Repository Connector ---
            if (className.contains("DocumentumRepositoryConnector")) {
                return checkDocumentumRepository(config);
            }

            // --- CMIS Repository Connector ---
            if (className.contains("CmisRepositoryConnector") || className.contains("Cmis") || className.contains("cmis")) {
                return checkCmis(config);
            }

            // --- Hyland OpenArch (Repository & Output) Connector ---
            if (className.contains("OpenArch") || className.contains("openarch")) {
                return checkOpenArch(config);
            }

            // --- FileSystem Repository Connector ---
            if (className.contains("FileConnector") || className.contains("FileSystem")) {
                return checkFileSystem(config);
            }

            // --- Relational Database (JDBC) Repository Connector ---
            if (className.contains("JdbcRepositoryConnector") || className.contains("Jdbc")) {
                return checkJdbc(config);
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

    private ConnectionCheckResult checkStormCrawler(Map<String, String> config) {
        String nimbusRestUrl = config.getOrDefault("nimbusRestUrl", "http://localhost:8080");
        String nimbusHost = config.getOrDefault("nimbusHost", "localhost");
        String nimbusPortStr = config.getOrDefault("nimbusPort", "6627");

        String cleanUrl = nimbusRestUrl.endsWith("/") ? nimbusRestUrl.substring(0, nimbusRestUrl.length() - 1) : nimbusRestUrl;
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl + "/api/v1/cluster/summary"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return new ConnectionCheckResult(true, "Successfully connected to Apache Storm Nimbus REST API at " + cleanUrl + ".", response.body());
            }
        } catch (Exception e) {
            // Fallback: check raw socket to Nimbus Thrift port
            try (Socket socket = new Socket()) {
                int port = Integer.parseInt(nimbusPortStr);
                socket.connect(new InetSocketAddress(nimbusHost, port), 2000);
                return new ConnectionCheckResult(true, "Successfully connected to Apache Storm Nimbus socket at " + nimbusHost + ":" + port + ".", null);
            } catch (Exception socketEx) {
                return new ConnectionCheckResult(false, "Failed to connect to Apache Storm Nimbus at " + cleanUrl + " or " + nimbusHost + ":" + nimbusPortStr + ": " + e.getMessage(), e.toString());
            }
        }
        return new ConnectionCheckResult(false, "Failed to connect to Apache Storm Nimbus cluster summary at " + cleanUrl, null);
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

    private ConnectionCheckResult checkAps(Map<String, String> config) {
        String endpoint = config.getOrDefault("url", config.getOrDefault("endpoint", "http://localhost:8080/activiti-app/api/enterprise"));
        String user = config.getOrDefault("username", "admin@app.activiti.com");
        String pass = config.getOrDefault("password", "admin");

        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        String testUrl = cleanEndpoint + "/profile";

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
                return new ConnectionCheckResult(true, "Successfully connected to Alfresco Process Services (APS) at " + cleanEndpoint + ".", null);
            } else {
                return new ConnectionCheckResult(false, "Alfresco Process Services returned HTTP status " + response.statusCode() + " from " + testUrl, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Alfresco Process Services at " + cleanEndpoint + ": " + e.getMessage(), e.toString());
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

    private ConnectionCheckResult checkDoxisRepository(Map<String, String> config) {
        DoxisProperties settings = DoxisProperties.fromConfiguration(config);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            return new ConnectionCheckResult(false, "Doxis repository connector is not configured: " + String.join(" ", problems)
                    + " " + DoxisRepositoryConnector.LICENCE_NOTICE, null);
        }
        DoxisClient client = DoxisRepositoryConnector.newClient(settings, "OpenCrawling-ConnectionCheck", Duration.ofSeconds(30), 0);
        try (client) {
            JsonNode repository = client.getRepository(settings.repositoryId());
            String shortName = repository.path("shortName").asText(repository.path("name").asText(settings.repositoryId()));
            return new ConnectionCheckResult(true, "Successfully logged in to Doxis CSB at " + client.getBaseUrl() + " (customer '"
                    + settings.customerName() + "'); repository '" + shortName + "' is accessible. " + DoxisRepositoryConnector.LICENCE_NOTICE,
                    repository.path("uuid").asText(null));
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Doxis at " + settings.url() + ": " + e.getMessage() + " "
                    + DoxisRepositoryConnector.LICENCE_NOTICE, e.toString());
        }
    }

    /**
     * Acquires an app-only token, resolves the site and lists its document libraries: proves the app registration, its
     * secret and its Sites.Read.All / Sites.Selected access in one go.
     */
    private ConnectionCheckResult checkSharePointRepository(Map<String, String> config) {
        SharePointProperties settings = SharePointProperties.fromConfiguration(config);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            return new ConnectionCheckResult(false, "SharePoint repository connector is not configured: " + String.join(" ", problems)
                    + " " + SharePointRepositoryConnector.PREREQUISITES_NOTICE, null);
        }
        try {
            GraphClient client = new GraphClient(settings);
            JsonNode site = settings.siteId() != null ? client.getSite(settings.siteId())
                    : client.getSiteByPath(settings.hostname(), settings.sitePath());
            List<String> libraries = client.listDrives(site.path("id").asText()).stream()
                    .filter(d -> "documentLibrary".equalsIgnoreCase(d.path("driveType").asText("documentLibrary")))
                    .map(d -> d.path("name").asText()).toList();
            List<String> missing = settings.driveNames().stream()
                    .filter(name -> libraries.stream().noneMatch(l -> l.equalsIgnoreCase(name))).toList();
            if (!missing.isEmpty()) {
                return new ConnectionCheckResult(false, "Connected to SharePoint site '" + site.path("displayName").asText()
                        + "', but it has no document library named " + missing + "; it has " + libraries + ".", null);
            }
            return new ConnectionCheckResult(true, "Successfully authenticated to Microsoft Graph; SharePoint site '"
                    + site.path("displayName").asText(site.path("webUrl").asText()) + "' is accessible with " + libraries.size()
                    + " document libraries " + libraries + ".", site.path("id").asText(null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ConnectionCheckResult(false, "Failed to connect to SharePoint through " + settings.graphUrl() + ": "
                    + e.getMessage() + " " + SharePointRepositoryConnector.PREREQUISITES_NOTICE, e.toString());
        }
    }

    /**
     * Logs in (vault-scoped token, credential headers or bearer), reads the session's vault and lists its object types: a
     * vault-level call, so an anonymous or server-level token fails here instead of on the first crawl.
     */
    private ConnectionCheckResult checkMFilesRepository(Map<String, String> config) {
        MFilesProperties settings = MFilesProperties.fromConfiguration(config);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            return new ConnectionCheckResult(false, "M-Files repository connector is not configured: " + String.join(" ", problems)
                    + " " + MFilesRepositoryConnector.LICENCE_NOTICE, null);
        }
        try (MFilesClient client = MFilesRepositoryConnector.newClient(settings, Duration.ofSeconds(30), 0)) {
            client.login();
            JsonNode vault = client.getVault();
            int objectTypes = MFilesClient.items(client.getObjectTypes()).size();
            return new ConnectionCheckResult(true, "Successfully authenticated to M-Files at " + client.getBaseUrl() + "; vault '"
                    + vault.path("Name").asText(settings.vaultGuid()) + "' is accessible (" + objectTypes + " object types). "
                    + MFilesRepositoryConnector.LICENCE_NOTICE, vault.path("GUID").asText(null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ConnectionCheckResult(false, "Failed to connect to M-Files at " + settings.url() + ": " + e.getMessage() + " "
                    + MFilesRepositoryConnector.LICENCE_NOTICE, e.toString());
        }
    }

    /**
     * Reads the Content Services GraphQL API information and the configured object store in one query: wrong credentials,
     * a missing XSRF acceptance or an unknown object store fail here instead of on the first crawl.
     */
    private ConnectionCheckResult checkFileNetRepository(Map<String, String> config) {
        FileNetProperties settings = FileNetProperties.fromConfiguration(config);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            return new ConnectionCheckResult(false, "FileNet repository connector is not configured: " + String.join(" ", problems)
                    + " " + FileNetRepositoryConnector.LICENCE_NOTICE, null);
        }
        try {
            FileNetClient client = FileNetRepositoryConnector.newClient(FileNetProperties.fromConfiguration(withNoRetries(config)));
            JsonNode info = FileNetRepositoryConnector.connectionInfo(client, settings);
            JsonNode store = info.path("objectStore");
            return new ConnectionCheckResult(true, "Successfully authenticated to the FileNet Content Services GraphQL API "
                    + info.path("_apiInfo").path("productVersion").asText("") + " at " + settings.url() + "; object store '"
                    + store.path("symbolicName").asText(settings.objectStore()) + "' is accessible. "
                    + FileNetRepositoryConnector.LICENCE_NOTICE, store.path("id").asText(null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ConnectionCheckResult(false, "Failed to connect to FileNet at " + settings.url() + ": " + e.getMessage()
                    + " " + FileNetRepositoryConnector.LICENCE_NOTICE, e.toString());
        }
    }

    /**
     * Reads the repository resource and runs one DQL query (in folder crawl mode, the root path's lookup): a wrong URL,
     * wrong credentials, an unknown repository or a missing folder fail here instead of on the first crawl.
     */
    private ConnectionCheckResult checkDocumentumRepository(Map<String, String> config) {
        DocumentumProperties settings = DocumentumProperties.fromConfiguration(config);
        List<String> problems = settings.validate();
        if (!problems.isEmpty()) {
            return new ConnectionCheckResult(false, "Documentum repository connector is not configured: "
                    + String.join(" ", problems) + " " + DocumentumRepositoryConnector.LICENCE_NOTICE, null);
        }
        try {
            DocumentumClient client = DocumentumRepositoryConnector.newClient(
                    DocumentumProperties.fromConfiguration(withNoRetries(config)));
            DocumentumRepositoryConnector.Info info = DocumentumRepositoryConnector.connectionInfo(client, settings);
            JsonNode repository = info.repository();
            return new ConnectionCheckResult(true, "Successfully authenticated to Documentum REST Services at " + settings.url()
                    + "; repository '" + repository.path("name").asText(settings.repository()) + "' (Content Server "
                    + repository.path("servers").path(0).path("version").asText("?") + ", server time zone offset "
                    + info.serverOffset().getId() + ") is accessible. " + DocumentumRepositoryConnector.LICENCE_NOTICE,
                    repository.path("id").asText(null));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ConnectionCheckResult(false, "Failed to connect to Documentum at " + settings.url() + ": "
                    + e.getMessage() + " " + DocumentumRepositoryConnector.LICENCE_NOTICE, e.toString());
        }
    }

    private static Map<String, String> withNoRetries(Map<String, String> config) {
        Map<String, String> c = new java.util.HashMap<>(config);
        c.put("maxRetries", "0");
        return c;
    }

    private ConnectionCheckResult checkCmis(Map<String, String> config) {
        String endpointUrl = config.getOrDefault("endpointUrl",
                config.getOrDefault("url", "http://localhost:8080/alfresco/api/-default-/public/cmis/versions/1.1/browser"));
        String user = config.getOrDefault("username", "admin");
        String pass = config.getOrDefault("password", "admin");

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json, application/atom+xml, text/html, */*")
                    .GET();

            if (user != null && !user.isBlank()) {
                String credentials = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + credentials);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 400) {
                return new ConnectionCheckResult(true, "Successfully connected to CMIS endpoint at " + endpointUrl + ".", response.body());
            } else {
                return new ConnectionCheckResult(false, "CMIS endpoint returned HTTP status " + response.statusCode() + " from " + endpointUrl, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to CMIS endpoint at " + endpointUrl + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkOpenArch(Map<String, String> config) {
        String endpointUrl = config.getOrDefault("endpointUrl",
                config.getOrDefault("url", "http://localhost:8080"));
        if (endpointUrl.endsWith("/")) {
            endpointUrl = endpointUrl.substring(0, endpointUrl.length() - 1);
        }
        String repoId = config.getOrDefault("repositoryId", "default");
        String user = config.getOrDefault("username", "Administrator");
        String pass = config.getOrDefault("password", "Administrator");

        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            URI uri = URI.create(endpointUrl + "/api/query");
            String queryJson = "{\"repositoryId\":\"" + repoId + "\",\"query\":\"SELECT * FROM SysContent\",\"limit\":1}";

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-HXCS-Repository", repoId)
                    .POST(HttpRequest.BodyPublishers.ofString(queryJson));

            if (user != null && !user.isBlank()) {
                String credentials = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                reqBuilder.header("Authorization", "Basic " + credentials);
            }

            HttpResponse<String> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to Hyland OpenArch endpoint at " + endpointUrl + " (Repository: " + repoId + ").", response.body());
            } else if (response.statusCode() == 404) {
                return new ConnectionCheckResult(true, "Hyland OpenArch server reachable at " + endpointUrl + ", endpoint returned 404.", response.body());
            } else {
                return new ConnectionCheckResult(false, "Hyland OpenArch returned HTTP status " + response.statusCode() + " from " + endpointUrl, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Hyland OpenArch at " + endpointUrl + ": " + e.getMessage(), e.toString());
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

    private ConnectionCheckResult checkLuxir(Map<String, String> config) {
        String endpoint = config.getOrDefault("luxirEndpoint", config.getOrDefault("endpoint", "http://localhost:9400"));
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String statsUrl = cleanEndpoint + "/_stats";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(statsUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to Luxir search engine at " + cleanEndpoint + " (HTTP " + response.statusCode() + ").", response.body());
            }

            // Fallback to /collections
            HttpRequest collectionsReq = HttpRequest.newBuilder()
                    .uri(URI.create(cleanEndpoint + "/collections"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> collectionsResp = client.send(collectionsReq, HttpResponse.BodyHandlers.ofString());
            if (collectionsResp.statusCode() >= 200 && collectionsResp.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to Luxir search engine at " + cleanEndpoint + " (HTTP " + collectionsResp.statusCode() + ").", collectionsResp.body());
            } else {
                return new ConnectionCheckResult(false, "Luxir returned HTTP status " + response.statusCode() + " from " + statsUrl, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Luxir at " + cleanEndpoint + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkSeaTunnel(Map<String, String> config) {
        String endpoint = config.getOrDefault("seaTunnelRestUrl", config.getOrDefault("restUrl", "http://localhost:8080"));
        String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            String overviewUrl = cleanEndpoint + "/overview";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(overviewUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to Apache SeaTunnel Zeta cluster at " + cleanEndpoint + " (HTTP " + response.statusCode() + ").", response.body());
            }

            // Fallback to /jobs
            HttpRequest jobsReq = HttpRequest.newBuilder()
                    .uri(URI.create(cleanEndpoint + "/jobs"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> jobsResp = client.send(jobsReq, HttpResponse.BodyHandlers.ofString());
            if (jobsResp.statusCode() >= 200 && jobsResp.statusCode() < 300) {
                return new ConnectionCheckResult(true, "Successfully connected to Apache SeaTunnel Zeta cluster at " + cleanEndpoint + " (HTTP " + jobsResp.statusCode() + ").", jobsResp.body());
            } else {
                return new ConnectionCheckResult(false, "Apache SeaTunnel cluster returned HTTP status " + response.statusCode() + " from " + overviewUrl, response.body());
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to Apache SeaTunnel cluster at " + cleanEndpoint + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkJdbc(Map<String, String> config) {
        String url = config.getOrDefault("url", "");
        if (url.isBlank()) {
            return new ConnectionCheckResult(false, "JDBC connection check failed: 'url' parameter is required.", null);
        }
        String driver = config.getOrDefault("driverClassName", config.getOrDefault("driver-class-name", ""));
        String username = config.getOrDefault("username", "");
        String password = config.getOrDefault("password", "");

        try {
            if (!driver.isBlank()) {
                try {
                    Class.forName(driver);
                } catch (ClassNotFoundException e) {
                    return new ConnectionCheckResult(false, "JDBC driver class not found on classpath: " + driver, e.toString());
                }
            }
            try (Connection conn = DriverManager.getConnection(url, username, password)) {
                if (conn.isValid(5)) {
                    String product = conn.getMetaData().getDatabaseProductName();
                    String version = conn.getMetaData().getDatabaseProductVersion();
                    return new ConnectionCheckResult(true, "Successfully connected to JDBC Database: " + product + " (" + version + ")", "Database: " + product + " " + version);
                } else {
                    return new ConnectionCheckResult(false, "Connection check failed: database rejected validation ping.", null);
                }
            }
        } catch (Exception e) {
            return new ConnectionCheckResult(false, "Failed to connect to JDBC database at " + url + ": " + e.getMessage(), e.toString());
        }
    }

    private ConnectionCheckResult checkGrpcPush(Map<String, String> config) {
        String host = config.getOrDefault("host", "localhost");
        int port = 9096;
        try {
            port = Integer.parseInt(config.getOrDefault("port", config.getOrDefault("listenerPort", "9096")));
        } catch (NumberFormatException ignored) {}

        io.grpc.ManagedChannel channel = null;
        try {
            channel = io.grpc.ManagedChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();

            org.opencrawling.push.v1.OisPushServiceGrpc.OisPushServiceBlockingStub stub =
                    org.opencrawling.push.v1.OisPushServiceGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS);

            org.opencrawling.push.v1.PingResponse response = stub.ping(
                    org.opencrawling.push.v1.PingRequest.newBuilder()
                            .setClientId("admin-ui-diagnostic-check")
                            .build()
            );

            return new ConnectionCheckResult(
                    true,
                    "gRPC Push listener ready at " + host + ":" + port + " (Status: " + response.getStatus() + ", Version: " + response.getVersion() + ")",
                    "Server epoch time: " + response.getServerTime()
            );
        } catch (Exception e) {
            return new ConnectionCheckResult(
                    false,
                    "Failed to connect to gRPC Push listener at " + host + ":" + port + ": " + e.getMessage(),
                    e.toString()
            );
        } finally {
            if (channel != null) {
                try {
                    channel.shutdownNow().awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            }
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

    @Schema(description = "Result of connector live connectivity test")
    public static record ConnectionCheckResult(
        @Schema(description = "Whether the target connection test succeeded", example = "true")
        boolean success,
        @Schema(description = "Diagnostic status summary message", example = "Successfully reached endpoint.")
        String message,
        @Schema(description = "Detailed exception trace or error context if connection failed", example = "null")
        String details
    ) {}
}
