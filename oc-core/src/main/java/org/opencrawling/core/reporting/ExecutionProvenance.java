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
package org.opencrawling.core.reporting;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Who started a job execution, and how.
 *
 * <p>Identity fields are only filled from a verified identity (a validated token): an execution started without one
 * records its {@link #triggerSource()} and leaves {@link #triggeredBy()} empty with {@link #callerVerified()}
 * {@code false}. An identity a client merely asserts is never recorded as fact.
 *
 * @param triggeredBy    the party that started the execution: the acting agent of a delegated token, otherwise the
 *                       token's principal; {@code null} when the caller is unknown
 * @param onBehalfOf     the user an agent acted for (the subject of a delegated token); {@code null} otherwise
 * @param callerGroups   the verified caller's groups; empty when unknown
 * @param callerTenant   the verified caller's tenant; {@code null} when unknown
 * @param callerVerified {@code true} only when the identity fields come from a validated token, or name a BPM
 *                       event source ({@code bpm:<name>}) authenticated by its signature or token
 * @param triggerSource  how the execution started: {@link #SOURCE_API}, {@link #SOURCE_DYNAMIC_API},
 *                       {@link #SOURCE_MCP}, {@link #SOURCE_BPM}, {@link #SOURCE_OIS}, {@link #SOURCE_GRPC_PUSH}, {@link #SOURCE_STARTUP};
 *                       {@code null} when unknown
 * @param correlationId  a caller-supplied id that ties the execution to the caller's own request or workflow
 */
public record ExecutionProvenance(
        String triggeredBy,
        String onBehalfOf,
        List<String> callerGroups,
        String callerTenant,
        boolean callerVerified,
        String triggerSource,
        String correlationId
) {

    /** Started through the job API ({@code POST /api/jobs/{id}/start}). */
    public static final String SOURCE_API = "api";
    /** A job the caller described itself, through {@code POST /api/jobs/dynamic}. */
    public static final String SOURCE_DYNAMIC_API = "dynamic-api";
    /** A job the caller described itself, through an MCP tool. */
    public static final String SOURCE_MCP = "mcp";
    /** A document-transition event from a BPM engine ({@code POST /api/events/bpm/document-transition}). */
    public static final String SOURCE_BPM = "bpm";
    /** A document pushed to the OIS ingest endpoint. */
    public static final String SOURCE_OIS = "ois";
    /** A document pushed over the gRPC push transport. */
    public static final String SOURCE_GRPC_PUSH = "grpc-push";
    /** The crawl-on-startup run of a standalone runtime. */
    public static final String SOURCE_STARTUP = "startup";

    /** Correlation ids are kept only when they look like an id: no spaces, no control characters, bounded. */
    private static final Pattern SAFE_CORRELATION_ID = Pattern.compile("^[A-Za-z0-9._:/@-]{1,128}$");

    private static final ExecutionProvenance UNKNOWN = new ExecutionProvenance(null, null, List.of(), null, false, null, null);

    public ExecutionProvenance {
        callerGroups = callerGroups == null ? List.of()
                : callerGroups.stream().filter(Objects::nonNull).filter(group -> !group.isBlank()).toList();
        if (!callerVerified && (triggeredBy != null || onBehalfOf != null || !callerGroups.isEmpty() || callerTenant != null)) {
            throw new IllegalArgumentException("Only a verified caller's identity can be recorded.");
        }
        correlationId = safeCorrelationId(correlationId);
    }

    /** Nothing is known about how the execution started. */
    public static ExecutionProvenance unknown() {
        return UNKNOWN;
    }

    /** An execution started by an unidentified caller, through {@code triggerSource}. */
    public static ExecutionProvenance anonymous(String triggerSource, String correlationId) {
        return new ExecutionProvenance(null, null, List.of(), null, false, triggerSource, correlationId);
    }

    /**
     * An execution started by a verified caller.
     *
     * @param principal the token's principal (for a delegated token, the user the agent acts for)
     * @param actor     the acting party of a delegated token, or {@code null}
     */
    public static ExecutionProvenance verified(String principal, String actor, List<String> groups, String tenant,
                                               String triggerSource, String correlationId) {
        boolean delegated = actor != null && !actor.isBlank();
        return new ExecutionProvenance(delegated ? actor : principal, delegated ? principal : null, groups, tenant, true,
                triggerSource, correlationId);
    }

    /** The given correlation id, or {@code null} when it is blank or not a plain id. */
    public static String safeCorrelationId(String correlationId) {
        if (correlationId == null) {
            return null;
        }
        String trimmed = correlationId.trim();
        return SAFE_CORRELATION_ID.matcher(trimmed).matches() ? trimmed : null;
    }
}
