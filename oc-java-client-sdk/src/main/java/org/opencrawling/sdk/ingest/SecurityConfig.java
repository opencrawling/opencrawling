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

import java.util.List;

/**
 * Zero-Trust Access Control List (ACL) configuration attached to an {@link OisDocument}.
 *
 * @param inheritanceEnabled whether repository parent ACL inheritance is enabled
 * @param evaluationPolicy   evaluation policy (defaults to {@code FAIL_CLOSED})
 * @param permissions        ordered list of allow/deny {@link PermissionRule} entries
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityConfig(boolean inheritanceEnabled, String evaluationPolicy, List<PermissionRule> permissions) {

    public static final String POLICY_FAIL_CLOSED = "FAIL_CLOSED";

    public SecurityConfig {
        evaluationPolicy = (evaluationPolicy == null || evaluationPolicy.isBlank()) ? POLICY_FAIL_CLOSED : evaluationPolicy;
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
    }

    /** Fail-closed security configuration with the given permission rules and inheritance disabled. */
    public static SecurityConfig failClosed(List<PermissionRule> permissions) {
        return new SecurityConfig(false, POLICY_FAIL_CLOSED, permissions);
    }

    /** Fail-closed security configuration with no permissions (denies all non-admin readers by default). */
    public static SecurityConfig none() {
        return new SecurityConfig(false, POLICY_FAIL_CLOSED, List.of());
    }

    /** Fail-closed security configuration with explicit inheritance flag and permission rules. */
    public static SecurityConfig of(boolean inheritanceEnabled, List<PermissionRule> permissions) {
        return new SecurityConfig(inheritanceEnabled, POLICY_FAIL_CLOSED, permissions);
    }
}
