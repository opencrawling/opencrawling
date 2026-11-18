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
package org.opencrawling.sdk.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One Access Control List (ACL) permission rule in an Open Ingestion Standard (OIS) {@link SecurityConfig}.
 *
 * @param identity     principal identifier (user principal, group name, or role name)
 * @param identityType {@code user}, {@code group}, or {@code role}
 * @param access       {@code read}, {@code write}, {@code admin}, or {@code deny}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermissionRule(String identity, String identityType, String access) {

    public PermissionRule {
        if (identity == null || identity.isBlank()) {
            throw new IllegalArgumentException("identity is required");
        }
        if (access == null || access.isBlank()) {
            throw new IllegalArgumentException("access is required");
        }
        identity = identity.trim();
        identityType = (identityType == null || identityType.isBlank()) ? "user" : identityType.trim();
        access = access.trim();
    }

    public static PermissionRule allowUser(String identity) {
        return new PermissionRule(identity, "user", "read");
    }

    public static PermissionRule allowGroup(String identity) {
        return new PermissionRule(identity, "group", "read");
    }

    public static PermissionRule allowRole(String identity) {
        return new PermissionRule(identity, "role", "read");
    }

    public static PermissionRule denyUser(String identity) {
        return new PermissionRule(identity, "user", "deny");
    }

    public static PermissionRule denyGroup(String identity) {
        return new PermissionRule(identity, "group", "deny");
    }

    public static PermissionRule denyRole(String identity) {
        return new PermissionRule(identity, "role", "deny");
    }
}
