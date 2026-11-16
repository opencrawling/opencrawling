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
package org.opencrawling.runtime.api.v1;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.BinarySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.tags.Tag;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Documentation the Platform API's OpenAPI description gets in one place, so it is consistent and cannot drift:
 * tag descriptions, the meaning of the standard parameters, a request example for every operation that takes a body,
 * and a ready-to-run curl sample for every operation ({@code x-codeSamples}, shown by Redoc and the admin UI's API
 * console). {@code PlatformApiV1DocumentationContractTest} fails when an operation is not covered.
 */
final class ApiV1Documentation {

    private ApiV1Documentation() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final Map<String, String> TAGS = Map.ofEntries(
            Map.entry("Platform", "The entry point: what this installation offers, its version and health, and who you are to it."),
            Map.entry("Jobs", "Pipeline jobs: their definitions and state, and starting, stopping and pausing their runs."),
            Map.entry("Job runs", "Runs in progress in this runtime. A finished run becomes an execution, with its report."),
            Map.entry("Executions", "Finished runs: reports, document ledgers, report files, and SQL analytics over the audit lake."),
            Map.entry("Connectors", "Configured repository, output, transformation and authority connectors, with live connectivity checks."),
            Map.entry("Settings", "Runtime-wide settings: crawling and extraction defaults, and the internal transport. Admins only."),
            Map.entry("Operations", "Operating the runtime: status, throughput, logs and metrics, and per-job diagnosis, traces and errors."),
            Map.entry("Tools", "ManifoldCF migration, Vespa administration and the narrativization template copilot."),
            Map.entry("Dynamic jobs", "On-demand ingestion of a folder or document of a registered connector by a verified caller."),
            Map.entry("Discovery", "Browse a source within the caller's access before crawling it, and dry-run a crawl."),
            Map.entry("Sidecars", "OIS sidecars written by migrations to Ozone and S3, read back with their document's metadata."));

    /** What the standard parameters mean, wherever they appear. */
    static final Map<String, String> PARAMETERS = Map.ofEntries(
            Map.entry("id", "The job's id, as returned when it was created"),
            Map.entry("jobId", "The job's id (for dynamic jobs, the dynamic job's id)"),
            Map.entry("executionId", "The execution id of a run, for example exec-1a2b3c4d"),
            Map.entry("name", "The connector's name; percent-encode spaces"),
            Map.entry("connector", "The name of a registered connector"),
            Map.entry("If-Match", "Apply only if the resource still has this ETag (412 otherwise); from a previous GET"),
            Map.entry("If-None-Match", "Answer 304 Not Modified if the resource still has this ETag"),
            Map.entry(ApiV1.IDEMPOTENCY_KEY, "A unique key per intended action (for example a UUID): retries with the same key and body get the first answer"),
            Map.entry(ApiV1.CORRELATION_ID, "Your own id for this request or workflow; recorded in the run's provenance and ledger"),
            Map.entry("limit", "Page size, 1 to 500 (default 50)"),
            Map.entry("cursor", "The page.nextCursor of the previous page; leave out for the first page"),
            Map.entry("sort", "Comma-separated sort fields, '-' for descending"),
            Map.entry("status", "Only items with this status"),
            Map.entry("configEndpoint", "Vespa config server endpoint, for example http://vespa:19071"),
            Map.entry("file", "The zipped Vespa application package"));

    /** A request example for every operation that takes a body. */
    /** Package-visible so the tests can run every example against its endpoint. */
    static final Map<String, String> EXAMPLES = Map.ofEntries(
            Map.entry("createJob", """
                    {"name":"HR contracts","repositoryConnector":"FileSystem_Local","outputConnector":"PGVector_Output",
                     "transformationConnector":"Ollama_Embedding_Default","path":"/data/hr","pipelineMode":"rag",
                     "access":{"allowedGroups":["hr-team"],"viewerGroups":["auditors"]}}"""),
            Map.entry("replaceJob", """
                    {"name":"HR contracts (2026)","repositoryConnector":"FileSystem_Local","outputConnector":"PGVector_Output",
                     "path":"/data/hr/2026","pipelineMode":"rag"}"""),
            Map.entry("patchJob", """
                    {"name":"HR contracts (archive)","pipelineMode":null}"""),
            Map.entry("createConnector", """
                    {"name":"HR_Share","description":"HR file share","type":"repository",
                     "className":"org.opencrawling.filesystem.FileSystemRepositoryConnector","maxConnections":10,
                     "configuration":{"rootPath":"/mnt/hr"}}"""),
            Map.entry("replaceConnector", """
                    {"name":"HR_Share","description":"HR file share (read-only mount)","type":"repository",
                     "className":"org.opencrawling.filesystem.FileSystemRepositoryConnector","maxConnections":5,
                     "configuration":{"rootPath":"/mnt/hr/read-only"}}"""),
            Map.entry("checkConnectorDefinition", """
                    {"name":"Alfresco_Staging","type":"repository","className":"org.opencrawling.alfresco.AlfrescoRepositoryConnector",
                     "configuration":{"baseUrl":"https://alfresco.example.com","username":"crawler","password":"s3cret"}}"""),
            Map.entry("queryExecutions", """
                    {"sql":"SELECT status, count(*) AS documents FROM read_parquet('mode=RAG/**/*.parquet') GROUP BY status"}"""),
            Map.entry("replaceSystemSettings", """
                    {"embeddingProvider":"Ollama","ollamaBaseUrl":"http://ollama:11434","ollamaModel":"mxbai-embed-large",
                     "vectorDimensions":1024,"chunkerType":"TokenTextSplitter","chunkSize":800,"chunkOverlap":100,
                     "claimCheckStore":"ozone","ozoneClientType":"NATIVE","ozoneS3Endpoint":"http://s3g:9878","ozoneOmHost":"om",
                     "ozoneOmPort":9862,"ozoneVolume":"s3v","ozoneBucket":"claims","tikaVersion":"3","tikaEngine":"tika",
                     "tikaForkEnabled":true,"tikaTimeoutMs":30000,"tikaJvmHeap":"-Xmx512m","tikaMaxFilesPerProcess":100000,
                     "tikaFallbackToEmbedded":true,"tikaExtractEmbedded":true,"tikaWriteLimit":-1,"tikaNumClients":2}"""),
            Map.entry("replaceTransportSettings", """
                    {"mode":"AUTO","grpcEnabled":true,"grpcPort":9095,"maxMessageSizeMb":32,"fallbackToRest":true,
                     "keepAliveTimeMs":30000,"connectionTimeoutMs":5000,"tlsEnabled":false,"certChainPath":"","privateKeyPath":""}"""),
            Map.entry("testGrpcTransport", """
                    {"host":"oc-mcp-server","port":9095}"""),
            Map.entry("planManifoldCfMigration", """
                    {"mcfUrl":"http://manifoldcf:8345/mcf-api-service/json","mcfUsername":"admin","mcfPassword":"admin",
                     "defaultEmbeddingDimensions":384}"""),
            Map.entry("applyManifoldCfMigration", """
                    {"mcfUrl":"http://manifoldcf:8345/mcf-api-service/json","mcfUsername":"admin","mcfPassword":"admin",
                     "selectedConnections":["Alfresco"],"selectedJobs":["Nightly Alfresco crawl"]}"""),
            Map.entry("queryVespa", """
                    {"endpoint":"http://vespa:8080","documentType":"opencrawling","queryText":"termination clause","rankProfile":"default"}"""),
            Map.entry("deployVespaBundledSchema", """
                    {"configEndpoint":"http://vespa:19071"}"""),
            Map.entry("generateNarrativizationTemplate", """
                    {"connectorType":"alfresco","fields":[{"name":"cm:title","type":"string","description":"Document title"},
                     {"name":"cm:modified","type":"date","description":"Last modification"}]}"""),
            Map.entry("startDynamicJob", """
                    {"connector":"Alfresco_Content_Services","start":"/Sites/legal/documentLibrary","name":"Legal on demand"}"""),
            Map.entry("ingestDynamicDocument", """
                    {"connector":"Alfresco_Content_Services","document":"workspace://SpacesStore/7f3c...","action":"UPSERT"}"""),
            Map.entry("rejectDynamicJob", """
                    {"reason":"Outside the legal team's scope"}"""),
            Map.entry("dryRunDiscovery", """
                    {"scope":"/Sites/legal/documentLibrary","sampleSize":5,"maxScan":10000}"""));

    /** Example values for path variables and required query parameters in the code samples. */
    private static final Map<String, String> SAMPLE_VALUES = Map.of(
            "id", "1763038591234", "executionId", "exec-1a2b3c4d", "name", "FileSystem_Local",
            "connector", "Alfresco_Content_Services", "endpoint", "http://vespa:8080");

    private static final Pattern VARIABLE = Pattern.compile("\\{([A-Za-z]+)}");

    static void document(OpenAPI openApi) {
        List<Tag> tags = new ArrayList<>();
        TAGS.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(tag -> tags.add(new Tag().name(tag.getKey()).description(tag.getValue())));
        openApi.setTags(tags);
        if (openApi.getPaths() == null) {
            return;
        }
        openApi.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            describeParameters(operation);
            addExample(operation);
            if ("exportExecution".equals(operation.getOperationId())) {
                binaryResponse(operation);
            }
            operation.addExtension("x-codeSamples", List.of(Map.of("lang", "Shell", "label", "curl",
                    "source", curl(method, path, operation))));
        }));
    }

    private static void describeParameters(Operation operation) {
        if (operation.getParameters() == null) {
            return;
        }
        for (Parameter parameter : operation.getParameters()) {
            if (parameter.getDescription() == null || parameter.getDescription().isBlank()) {
                parameter.setDescription(PARAMETERS.get(parameter.getName()));
            }
        }
    }

    private static void addExample(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return;
        }
        String example = EXAMPLES.get(operation.getOperationId());
        operation.getRequestBody().getContent().forEach((type, media) -> {
            if (type.startsWith("multipart/")) {
                media.setExample(Map.of("configEndpoint", "http://vespa:19071", "file", "(application package .zip)"));
            } else if (example != null && media.getExample() == null) {
                media.setExample(JSON.readValue(example, Object.class));
            }
        });
    }

    private static void binaryResponse(Operation operation) {
        ApiResponse ok = operation.getResponses().get("200");
        if (ok == null) {
            return;
        }
        Content content = new Content();
        for (String type : List.of("application/json", "text/csv", "application/vnd.apache.parquet", "application/avro")) {
            content.addMediaType(type, new MediaType().schema(new BinarySchema()));
        }
        ok.setContent(content);
    }

    /** Text for inside single quotes in a POSIX shell: each ' becomes '\'' (close, escaped quote, reopen). */
    static String shellQuoted(String text) {
        return text.replace("'", "'\\''");
    }

    /** A curl command that runs the operation against $OC_URL, with $OC_TOKEN when the API needs one. */
    static String curl(PathItem.HttpMethod method, String path, Operation operation) {
        Matcher matcher = VARIABLE.matcher(path);
        StringBuilder url = new StringBuilder();
        while (matcher.find()) {
            String variable = matcher.group(1);
            String value = "jobId".equals(variable)
                    ? (path.startsWith(ApiV1.BASE + "/dynamic-jobs") ? "dj-1a2b3c4d" : SAMPLE_VALUES.get("id"))
                    : SAMPLE_VALUES.getOrDefault(variable, variable);
            matcher.appendReplacement(url, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(url);
        List<String> query = new ArrayList<>();
        Map<String, String> headers = new LinkedHashMap<>();
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if ("query".equals(parameter.getIn()) && Boolean.TRUE.equals(parameter.getRequired())) {
                    query.add(parameter.getName() + "=" + SAMPLE_VALUES.getOrDefault(parameter.getName(), "value"));
                }
                if ("header".equals(parameter.getIn())) {
                    switch (parameter.getName()) {
                        case "If-Match" -> headers.put("If-Match", "\\\"<etag from GET>\\\"");
                        case ApiV1.IDEMPOTENCY_KEY -> headers.put(ApiV1.IDEMPOTENCY_KEY, "$(uuidgen)");
                        default -> { }
                    }
                }
            }
        }
        if ("exportExecution".equals(operation.getOperationId())) {
            query.add("format=csv");
        }
        StringBuilder curl = new StringBuilder("curl -sS");
        if (method != PathItem.HttpMethod.GET) {
            curl.append(" -X ").append(method.name());
        }
        curl.append(" \"$OC_URL").append(url).append(query.isEmpty() ? "" : "?" + String.join("&", query)).append("\"");
        curl.append(" \\\n  -H \"Authorization: Bearer $OC_TOKEN\"");
        headers.forEach((name, value) -> curl.append(" \\\n  -H \"").append(name).append(": ").append(value).append("\""));
        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            Content content = operation.getRequestBody().getContent();
            if (content.keySet().stream().anyMatch(type -> type.startsWith("multipart/"))) {
                curl.append(" \\\n  -F configEndpoint=http://vespa:19071 -F file=@application.zip");
            } else {
                String type = content.containsKey(ApiV1.MERGE_PATCH_JSON) ? ApiV1.MERGE_PATCH_JSON : "application/json";
                String example = EXAMPLES.getOrDefault(operation.getOperationId(), "{}").replaceAll("\\s*\\n\\s*", "");
                curl.append(" \\\n  -H \"Content-Type: ").append(type).append("\"")
                        .append(" \\\n  -d '").append(shellQuoted(example)).append("'");
            }
        }
        if ("exportExecution".equals(operation.getOperationId())) {
            curl.append(" \\\n  -o report.csv");
        }
        return curl.toString();
    }
}
