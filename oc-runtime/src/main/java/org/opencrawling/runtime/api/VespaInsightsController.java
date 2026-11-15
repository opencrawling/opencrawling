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
package org.opencrawling.runtime.api;

import java.io.IOException;
import java.util.List;

import org.opencrawling.runtime.service.VespaInsightsService;
import org.opencrawling.runtime.service.VespaInsightsService.DocumentTypeCount;
import org.opencrawling.runtime.service.VespaInsightsService.VespaDeployResult;
import org.opencrawling.runtime.service.VespaInsightsService.VespaHealthResult;
import org.opencrawling.runtime.service.VespaInsightsService.VespaQueryResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Vespa Insights", description = "Vespa cluster health, document counts, search query testing, and schema deployments")
@RestController
@RequestMapping("/api/vespa")
public class VespaInsightsController {

    private final VespaInsightsService insightsService;

    @Autowired
    public VespaInsightsController(VespaInsightsService insightsService) {
        this.insightsService = insightsService;
    }

    @Operation(summary = "Check Vespa cluster health", description = "Checks whether the Vespa container endpoint is reachable and healthy.")
    @ApiResponse(responseCode = "200", description = "Vespa cluster health status")
    @GetMapping("/health")
    public VespaHealthResult getHealth(
            @Parameter(description = "Vespa container endpoint URL", example = "http://localhost:8080") @RequestParam String endpoint) {
        return insightsService.checkHealth(endpoint);
    }

    @Operation(summary = "Get document counts per type", description = "Retrieves indexed document counts grouped by Vespa document type.")
    @ApiResponse(responseCode = "200", description = "List of document counts per document type")
    @GetMapping("/document-counts")
    public List<DocumentTypeCount> getDocumentCounts(
            @Parameter(description = "Vespa container endpoint URL", example = "http://localhost:8080") @RequestParam String endpoint) {
        return insightsService.getDocumentCounts(endpoint);
    }

    @Operation(summary = "Run test search query against Vespa", description = "Executes a test search query with a specified rank profile against Vespa.")
    @ApiResponse(responseCode = "200", description = "Query results with matching hits")
    @PostMapping("/query")
    public VespaQueryResult runQuery(@RequestBody VespaQueryRequest request) {
        return insightsService.runQuery(request.endpoint(), request.documentType(), request.queryText(), request.rankProfile());
    }

    @Operation(summary = "Deploy bundled schema to Vespa", description = "Deploys OpenCrawling's built-in application package schema to the Vespa config server.")
    @ApiResponse(responseCode = "200", description = "Deployment result with status and session ID")
    @PostMapping("/deploy/bundled")
    public VespaDeployResult deployBundledSchema(@RequestBody DeployBundledRequest request) {
        return insightsService.deployBundledSchema(request.configEndpoint());
    }

    @Operation(summary = "Deploy custom schema to Vespa", description = "Uploads and deploys a custom Vespa application package zip file.")
    @ApiResponse(responseCode = "200", description = "Deployment result with status and session ID")
    @PostMapping(value = "/deploy/custom", consumes = "multipart/form-data")
    public VespaDeployResult deployCustomSchema(
            @Parameter(description = "Vespa configuration server endpoint URL", example = "http://localhost:19071") @RequestParam("configEndpoint") String configEndpoint,
            @Parameter(description = "Vespa application package zip file") @RequestParam("file") MultipartFile file) throws IOException {
        return insightsService.deployCustomSchema(configEndpoint, file.getBytes(), file.getContentType());
    }

    @Schema(description = "Vespa test query request")
    public record VespaQueryRequest(
            @Schema(description = "Vespa container endpoint URL", example = "http://localhost:8080") String endpoint,
            @Schema(description = "Vespa document type name", example = "opencrawling") String documentType,
            @Schema(description = "Free-text or YQL query string", example = "enterprise documentation") String queryText,
            @Schema(description = "Rank profile to use for scoring", example = "default") String rankProfile) {}

    @Schema(description = "Deploy bundled schema request")
    public record DeployBundledRequest(
            @Schema(description = "Vespa configuration server endpoint URL", example = "http://localhost:19071") String configEndpoint) {}
}
