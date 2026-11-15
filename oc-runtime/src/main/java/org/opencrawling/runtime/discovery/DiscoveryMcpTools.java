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
package org.opencrawling.runtime.discovery;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tools for repository discovery: what repositories are registered, what is in them, and what a crawl would
 * take, as far as the caller may see it.
 */
@Component
@ConditionalOnExpression("${opencrawling.discovery.enabled:false} and ${opencrawling.mcp.server.enabled:true}")
public class DiscoveryMcpTools {

    static final String IDENTITY_NOTE = " Ignored when the server verifies callers by bearer token: the token's identity is used instead.";

    private final DiscoveryService service;
    private CallerResolver callerResolver = CallerResolver.asserted();

    public DiscoveryMcpTools(DiscoveryService service) {
        this.service = service;
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @McpTool(description = "List the registered repository connectors whose content can be explored and previewed before ingestion. Returns names and descriptions only.")
    public List<DiscoveryService.ConnectorInfo> listRepositoryConnectors(
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles) {
        return service.listConnectors(DiscoveryController.caller(callerResolver, userPrincipal, userRoles));
    }

    @McpTool(description = "List the folders and documents of a registered repository, breadth-first from a folder, without reading any content. Only items the caller may read are returned; folders the caller cannot read are not opened. Results are bounded and say when they were truncated.")
    public DiscoveryService.TreeView exploreRepositoryTree(
            @McpToolParam(description = "Name of the repository connector (see listRepositoryConnectors)", required = true) String connector,
            @McpToolParam(description = "Folder id or path to start from; the connector's configured root when omitted", required = false) String start,
            @McpToolParam(description = "How many levels below the start folder to list (1 = its children)", required = false) Integer depth,
            @McpToolParam(description = "Most items to return", required = false) Integer maxNodes,
            @McpToolParam(description = "Whether to list documents as well as folders (default true)", required = false) Boolean includeDocuments,
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles) {
        return service.explore(DiscoveryController.caller(callerResolver, userPrincipal, userRoles), connector, start,
                depth, maxNodes, includeDocuments);
    }

    @McpTool(description = "Preview what a crawl of a registered repository would ingest, without ingesting or reading content: how many matching documents the caller may read, and some of them. The scope is a folder id or path, or a read-only query in the repository's query language (for CMIS, a single SELECT).")
    public DiscoveryService.DryRunView queryRepositoryDryRun(
            @McpToolParam(description = "Name of the repository connector (see listRepositoryConnectors)", required = true) String connector,
            @McpToolParam(description = "Folder id or path, or a read-only query; the connector's configured scope when omitted", required = false) String scope,
            @McpToolParam(description = "How many matching documents to return as samples", required = false) Integer sampleSize,
            @McpToolParam(description = "Most documents to look at before giving a lower-bound count", required = false) Integer maxScan,
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles) {
        return service.dryRun(DiscoveryController.caller(callerResolver, userPrincipal, userRoles), connector, scope,
                sampleSize, maxScan);
    }
}
