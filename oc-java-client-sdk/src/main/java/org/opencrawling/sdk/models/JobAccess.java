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
package org.opencrawling.sdk.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Who may see and run a job when management authorization is on.
 *
 * <ul>
 *   <li>{@code owner} (and the admins) may do everything. On create the runtime makes the caller the owner and puts the
 *       job in the caller's tenant; only an admin may name another.</li>
 *   <li>{@code allowedUsers} and {@code allowedGroups} may see the job and start, stop or pause it.</li>
 *   <li>{@code viewerUsers} and {@code viewerGroups} may only see it, its runs, reports and traces.</li>
 *   <li>A job with a {@code tenantId} is visible only to callers of that tenant.</li>
 * </ul>
 * Sending a job without access on update keeps the access it has.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobAccess(
        String owner,
        String tenantId,
        List<String> allowedUsers,
        List<String> allowedGroups,
        List<String> viewerUsers,
        List<String> viewerGroups
) {
    public JobAccess {
        allowedUsers = allowedUsers == null ? List.of() : List.copyOf(allowedUsers);
        allowedGroups = allowedGroups == null ? List.of() : List.copyOf(allowedGroups);
        viewerUsers = viewerUsers == null ? List.of() : List.copyOf(viewerUsers);
        viewerGroups = viewerGroups == null ? List.of() : List.copyOf(viewerGroups);
    }

    /** Groups whose members may run the job, and groups that may only see it. */
    public static JobAccess forGroups(List<String> allowedGroups, List<String> viewerGroups) {
        return new JobAccess(null, null, null, allowedGroups, null, viewerGroups);
    }
}
