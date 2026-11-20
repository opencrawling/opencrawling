/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.opencrawling.runtime.api.McfMigrationController;
import org.opencrawling.runtime.api.VespaInsightsController;
import org.opencrawling.runtime.api.copilot.NarrativizationCopilotController;
import org.opencrawling.runtime.api.copilot.SchemaContextRequest;
import org.opencrawling.runtime.api.copilot.TemplateCopilotResponse;
import org.opencrawling.runtime.service.VespaInsightsService.DocumentTypeCount;
import org.opencrawling.runtime.service.VespaInsightsService.VespaDeployResult;
import org.opencrawling.runtime.service.VespaInsightsService.VespaHealthResult;
import org.opencrawling.runtime.service.VespaInsightsService.VespaQueryResult;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Tools around the pipeline: ManifoldCF migration, the Vespa integration and the narrativization copilot. */
@RestController
@RequestMapping(ApiV1.BASE)
@Tag(name = "Tools", description = "ManifoldCF migration, Vespa administration and the narrativization copilot")
public class ToolsV1Controller {

    private final McfMigrationController manifoldcf;
    private final VespaInsightsController vespa;
    private final NarrativizationCopilotController copilot;

    public ToolsV1Controller(McfMigrationController manifoldcf, VespaInsightsController vespa,
                             NarrativizationCopilotController copilot) {
        this.manifoldcf = manifoldcf;
        this.vespa = vespa;
        this.copilot = copilot;
    }

    @Operation(operationId = "planManifoldCfMigration", summary = "Plan a ManifoldCF migration",
            description = "Reads a ManifoldCF instance's connections and jobs and reports what migrates and how. Changes nothing. Admins only.")
    @ApiResponse(responseCode = "200", description = "The plan")
    @ApiResponse(responseCode = "502", description = "ManifoldCF could not be reached or answered with an error")
    @PostMapping("/migrations/manifoldcf:plan")
    public McfMigrationController.MigrationResponse planManifoldCf(@RequestBody McfMigrationController.MigrationRequest request) {
        requireMcfUrl(request);
        return manifoldcf.plan(request);
    }

    @Operation(operationId = "applyManifoldCfMigration", summary = "Apply a ManifoldCF migration",
            description = "Creates the supported connections and jobs (or the selected ones) in OpenCrawling. Admins only.")
    @ApiResponse(responseCode = "200", description = "The outcome per connection and job")
    @ApiResponse(responseCode = "502", description = "ManifoldCF could not be reached or answered with an error")
    @PostMapping("/migrations/manifoldcf:apply")
    public McfMigrationController.MigrationResponse applyManifoldCf(@RequestBody McfMigrationController.MigrationRequest request) {
        requireMcfUrl(request);
        return manifoldcf.apply(request);
    }

    @Operation(operationId = "getVespaHealth", summary = "Vespa health",
            description = "Whether the Vespa container at the endpoint answers, with its message. Admins only.")
    @ApiResponse(responseCode = "200", description = "The health")
    @GetMapping("/integrations/vespa/health")
    public VespaHealthResult vespaHealth(@Parameter(description = "Vespa container endpoint") @RequestParam String endpoint) {
        return vespa.getHealth(endpoint);
    }

    @Operation(operationId = "getVespaDocumentCounts", summary = "Vespa document counts", description = "Documents per type. Admins only.")
    @ApiResponse(responseCode = "200", description = "The counts")
    @GetMapping("/integrations/vespa/document-counts")
    public List<DocumentTypeCount> vespaDocumentCounts(@Parameter(description = "Vespa container endpoint") @RequestParam String endpoint) {
        return vespa.getDocumentCounts(endpoint);
    }

    @Operation(operationId = "queryVespa", summary = "Run a Vespa test query",
            description = "Runs a text or YQL query against a Vespa document type with a rank profile, to check what an index returns. Admins only.")
    @ApiResponse(responseCode = "200", description = "The hits")
    @PostMapping("/integrations/vespa:query")
    public VespaQueryResult queryVespa(@RequestBody VespaInsightsController.VespaQueryRequest request) {
        return vespa.runQuery(request);
    }

    @Operation(operationId = "deployVespaBundledSchema", summary = "Deploy the bundled Vespa application package",
            description = "Deploys the application package that ships with OpenCrawling (its document types and rank profiles) to the Vespa config server. Admins only.")
    @ApiResponse(responseCode = "200", description = "The deployment's result")
    @PostMapping("/integrations/vespa:deploy-bundled")
    public VespaDeployResult deployVespaBundled(@RequestBody VespaInsightsController.DeployBundledRequest request) {
        return vespa.deployBundledSchema(request);
    }

    @Operation(operationId = "deployVespaCustomSchema", summary = "Deploy a custom Vespa application package",
            description = "Upload a zipped application package. Admins only.")
    @ApiResponse(responseCode = "200", description = "The deployment's result")
    @PostMapping(value = "/integrations/vespa:deploy-custom", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VespaDeployResult deployVespaCustom(@RequestParam("configEndpoint") String configEndpoint,
                                               @RequestParam("file") MultipartFile file) throws IOException {
        return vespa.deployCustomSchema(configEndpoint, file);
    }

    @Operation(operationId = "generateNarrativizationTemplate", summary = "Generate a narrativization template",
            description = "Suggests a template that turns a connector's metadata fields into text. Admins and job creators.")
    @ApiResponse(responseCode = "200", description = "The suggested template")
    @PostMapping("/narrativization:generate-template")
    public TemplateCopilotResponse generateTemplate(@RequestBody SchemaContextRequest request) {
        ResponseEntity<TemplateCopilotResponse> result = copilot.generateTemplate(request);
        JobsV1Controller.requireOk(result, "Template", "");
        return result.getBody();
    }

    /** A missing URL is the caller's mistake (400), not a ManifoldCF failure (502). */
    private static void requireMcfUrl(McfMigrationController.MigrationRequest request) {
        if (request == null || request.mcfUrl() == null || request.mcfUrl().isBlank()) {
            throw ApiProblem.invalid("mcfUrl", "is required");
        }
    }
}
