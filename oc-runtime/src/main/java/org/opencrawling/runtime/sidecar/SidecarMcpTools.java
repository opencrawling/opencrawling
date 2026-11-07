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
package org.opencrawling.runtime.sidecar;

import org.opencrawling.runtime.security.CallerResolver;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/** MCP tools for OIS sidecars: the metadata of migrated content, without reading the binaries. */
@Component
@ConditionalOnExpression("${" + SidecarProperties.PREFIX + ".enabled:false} and ${opencrawling.mcp.server.enabled:true}")
public class SidecarMcpTools {

    static final String IDENTITY_NOTE = " Ignored when the server verifies callers by bearer token: the token's identity is used instead.";

    private final SidecarService service;
    private CallerResolver callerResolver = CallerResolver.asserted();

    public SidecarMcpTools(SidecarService service) {
        this.service = service;
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @McpTool(description = "List the object stores (registered Apache Ozone or AWS S3 output connectors) whose OIS sidecars can be read.")
    public List<SidecarService.StoreInfo> listSidecarStores(
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles) {
        return service.listStores(SidecarController.caller(callerResolver, userPrincipal, userRoles));
    }

    @McpTool(description = "Read the OIS metadata sidecars (*.ois.json) of migrated content in an object store, without downloading the binaries: each document's id, source, file name, type, size, checksum and business metadata. Only sidecars the caller may read are returned, in key order, with totals over the page (count, bytes, by MIME type, by source type). Pass nextCursor back to get the next page.")
    public SidecarService.SidecarPage readOisSidecars(
            @McpToolParam(description = "Name of the store (see listSidecarStores)", required = true) String connector,
            @McpToolParam(description = "Key prefix, relative to where the store's connector writes, e.g. 'finance/2024/'", required = false) String prefix,
            @McpToolParam(description = "Most sidecars to return", required = false) Integer limit,
            @McpToolParam(description = "The previous page's nextCursor", required = false) String cursor,
            @McpToolParam(description = "The caller's user principal." + IDENTITY_NOTE, required = false) String userPrincipal,
            @McpToolParam(description = "Comma-separated groups/roles of the caller." + IDENTITY_NOTE, required = false) String userRoles) {
        return service.read(SidecarController.caller(callerResolver, userPrincipal, userRoles), connector, prefix, limit, cursor);
    }
}
