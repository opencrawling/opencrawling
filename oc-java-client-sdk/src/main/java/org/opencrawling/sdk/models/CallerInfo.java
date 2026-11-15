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

import java.util.List;

/**
 * The caller of a request as the runtime sees it ({@code GET /api/auth/me}). {@code admin} and {@code canCreateJobs}
 * are true for everyone when management authorization is off.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CallerInfo(boolean authenticated, String principal, List<String> groups, String tenant,
                         boolean managementAuthorization, boolean admin, boolean canCreateJobs) {
    public CallerInfo {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }
}
