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

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Objects;

/**
 * Who may see and run a job when {@code opencrawling.security.management.enabled} is on.
 *
 * <ul>
 *   <li>The {@code owner} (and the admins) may do everything: see, run, edit, delete, change this access.</li>
 *   <li>{@code allowedUsers} and {@code allowedGroups} may see the job, its runs, reports and traces, and start,
 *       stop or pause it. They may not edit or delete it.</li>
 *   <li>{@code viewerUsers} and {@code viewerGroups} may only see it, its runs, reports and traces.</li>
 *   <li>A job with a {@code tenantId} is visible only to callers of that tenant, whatever the lists say.</li>
 * </ul>
 * A job without access (created before authorization was on) is visible only to the admins, until one assigns it.
 */
@Schema(description = "Who may see and run the job (used when opencrawling.security.management.enabled is on)")
public record JobAccess(
        @Schema(description = "Principal of the job's owner; set from the caller's token when the job is created", example = "hr.lead")
        String owner,
        @Schema(description = "Tenant the job belongs to; only callers of this tenant can see it", example = "acme")
        String tenantId,
        @Schema(description = "Principals that may see and run the job")
        List<String> allowedUsers,
        @Schema(description = "Groups whose members may see and run the job", example = "[\"hr-team\"]")
        List<String> allowedGroups,
        @Schema(description = "Principals that may only see the job, its runs, reports and traces")
        List<String> viewerUsers,
        @Schema(description = "Groups whose members may only see the job, its runs, reports and traces", example = "[\"auditors\"]")
        List<String> viewerGroups
) {
    public JobAccess {
        owner = blankToNull(owner);
        tenantId = blankToNull(tenantId);
        allowedUsers = clean(allowedUsers);
        allowedGroups = clean(allowedGroups);
        viewerUsers = clean(viewerUsers);
        viewerGroups = clean(viewerGroups);
    }

    /** Owner, tenant and the users and groups that may run the job; no see-only viewers. */
    public JobAccess(String owner, String tenantId, List<String> allowedUsers, List<String> allowedGroups) {
        this(owner, tenantId, allowedUsers, allowedGroups, null, null);
    }

    public JobAccess withOwnerAndTenant(String newOwner, String newTenantId) {
        return new JobAccess(newOwner, newTenantId, allowedUsers, allowedGroups, viewerUsers, viewerGroups);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static List<String> clean(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isEmpty()).distinct().toList();
    }
}
