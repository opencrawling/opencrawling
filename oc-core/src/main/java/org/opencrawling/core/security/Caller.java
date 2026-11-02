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
package org.opencrawling.core.security;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The identity a retrieval request is evaluated for.
 *
 * <p>Today the MCP tools take the principal and groups from the tool arguments, so {@code authenticated} is
 * {@code false}: the identity is asserted by the client, not verified. Once an OAuth2/OIDC resource server is in
 * place (#187) the same record is filled from the validated token and {@code authenticated} becomes {@code true}.
 *
 * @param principal     the caller's user identity, or {@code null} when anonymous
 * @param groups        the groups or roles the caller belongs to, never {@code null}; blank entries are
 *                      dropped so that they can never match a blank ACL entry
 * @param tenant        the caller's tenant, or {@code null} when the deployment is single-tenant
 * @param authenticated whether the identity was verified by the server
 * @param attributes    further claims (for example an on-behalf-of actor), never {@code null}
 */
public record Caller(
        String principal,
        List<String> groups,
        String tenant,
        boolean authenticated,
        Map<String, Object> attributes
) {

    public Caller {
        groups = groups == null ? List.of() : groups.stream().filter(Objects::nonNull).filter(group -> !group.isBlank()).toList();
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /**
     * A caller whose identity was asserted by the client (tool arguments) rather than verified.
     */
    public static Caller asserted(String principal, List<String> groups) {
        return new Caller(principal, groups, null, false, Map.of());
    }
}
