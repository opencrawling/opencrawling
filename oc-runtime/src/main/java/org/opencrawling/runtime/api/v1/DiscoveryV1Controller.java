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
import org.opencrawling.runtime.discovery.DiscoveryController;
import org.opencrawling.runtime.discovery.DiscoveryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Discovery: browse what a registered repository connector can reach, as the caller is allowed to see it, before
 * crawling. Enabled with {@code opencrawling.discovery.enabled}; needs a verified caller.
 */
@RestController
@RequestMapping(ApiV1.BASE + "/discovery/connectors")
@ConditionalOnProperty(name = "opencrawling.discovery.enabled", havingValue = "true")
@Tag(name = "Discovery", description = "Browse a source before crawling it, within the caller's access")
public class DiscoveryV1Controller {

    private final DiscoveryController discovery;

    public DiscoveryV1Controller(DiscoveryController discovery) {
        this.discovery = discovery;
    }

    @Operation(operationId = "listDiscoveryConnectors", summary = "Connectors that can be browsed",
            description = "The registered connectors discovery may browse for the caller (opencrawling.discovery.connectors).")
    @ApiResponse(responseCode = "200", description = "The connectors")
    @GetMapping
    public List<DiscoveryService.ConnectorInfo> connectors() {
        return discovery.connectors(null, null);
    }

    @Operation(operationId = "getDiscoveryTree", summary = "Browse a connector's tree",
            description = "Folders (and optionally documents) the caller may read, to a depth and node limit.")
    @ApiResponse(responseCode = "200", description = "The tree")
    @GetMapping("/{connector}/tree")
    public DiscoveryService.TreeView tree(@PathVariable String connector,
                                          @Parameter(description = "Folder id or path; the connector's root when absent") @RequestParam(required = false) String start,
                                          @Parameter(description = "Levels to descend") @RequestParam(required = false) Integer depth,
                                          @Parameter(description = "Maximum nodes") @RequestParam(required = false) Integer maxNodes,
                                          @Parameter(description = "Include documents, not only folders") @RequestParam(required = false) Boolean includeDocuments) {
        return discovery.tree(connector, start, depth, maxNodes, includeDocuments, null, null);
    }

    @Operation(operationId = "dryRunDiscovery", summary = "Dry-run a crawl of part of a connector",
            description = "Counts and samples what a crawl of a scope would ingest, without ingesting anything.")
    @ApiResponse(responseCode = "200", description = "The dry run's result")
    @PostMapping("/{connector}:dry-run")
    public DiscoveryService.DryRunView dryRun(@PathVariable String connector,
                                              @RequestBody(required = false) DiscoveryController.DryRunBody body) {
        return discovery.dryRun(connector, body, null, null);
    }
}
