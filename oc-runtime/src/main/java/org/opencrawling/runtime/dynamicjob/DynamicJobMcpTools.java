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
package org.opencrawling.runtime.dynamicjob;

import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.security.CallerResolver;
import org.opencrawling.runtime.security.VerifiedCallerRequiredException;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tools for dynamic jobs. The caller must be verified by bearer token
 * ({@code opencrawling.security.identity.mode=jwt}); an asserted identity is refused.
 */
@Component
@ConditionalOnExpression("${" + DynamicJobProperties.PREFIX + ".enabled:false} and ${opencrawling.mcp.server.enabled:true}")
public class DynamicJobMcpTools {

    private final DynamicJobService service;
    private CallerResolver callerResolver = CallerResolver.asserted();

    public DynamicJobMcpTools(DynamicJobService service) {
        this.service = service;
    }

    @Autowired(required = false)
    public void setCallerResolver(CallerResolver callerResolver) {
        if (callerResolver != null) {
            this.callerResolver = callerResolver;
        }
    }

    @McpTool(description = "Start a job that ingests a folder of a registered repository connector (see listRepositoryConnectors and exploreRepositoryTree). Only connectors an administrator allowed for dynamic jobs can be used, and only folders in the connector's scope that you may read. Documents keep their source permissions. Returns the job id and a status URL; with validateOnly, checks the request and starts nothing.")
    public Object triggerDynamicJob(
            @McpToolParam(description = "Name of the registered repository connector", required = true) String connector,
            @McpToolParam(description = "Folder id or path to crawl; the connector's configured root when omitted", required = false) String start,
            @McpToolParam(description = "A label for the job", required = false) String name,
            @McpToolParam(description = "Only check the request; start nothing", required = false) Boolean validateOnly,
            @McpToolParam(description = "Repeating a call with the same key returns the job the first call started instead of starting another", required = false) String idempotencyKey,
            @McpToolParam(description = "Your own id for this request, recorded in the job's ledger", required = false) String correlationId) {
        Caller caller = caller();
        DynamicJobService.JobRequest request = new DynamicJobService.JobRequest(connector, start, name);
        if (Boolean.TRUE.equals(validateOnly)) {
            return service.validate(caller, request);
        }
        return service.start(caller, request, idempotencyKey, ExecutionProvenance.SOURCE_MCP, correlationId);
    }

    @McpTool(description = "Get the status of a dynamic job you started: running, completed, failed or stopped, and how many documents it handled once it has ended.")
    public DynamicJobService.JobStatus getDynamicJobStatus(
            @McpToolParam(description = "The job id returned by triggerDynamicJob", required = true) String jobId) {
        return service.status(caller(), jobId);
    }

    @McpTool(description = "List the dynamic jobs you started that are still remembered, newest first.")
    public List<DynamicJobService.JobStatus> listDynamicJobs() {
        return service.list(caller());
    }

    @McpTool(description = "Stop a running dynamic job you started. It ends at the latest before its next document.")
    public DynamicJobService.JobStatus stopDynamicJob(
            @McpToolParam(description = "The job id returned by triggerDynamicJob", required = true) String jobId) {
        return service.stop(caller(), jobId);
    }

    /** The token's caller; an asserted identity (no token) is refused by the service. */
    private Caller caller() {
        try {
            return callerResolver.resolve(null, null);
        } catch (VerifiedCallerRequiredException e) {
            throw new DynamicJobException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }
}
