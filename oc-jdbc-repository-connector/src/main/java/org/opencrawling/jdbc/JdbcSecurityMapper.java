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
package org.opencrawling.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

public class JdbcSecurityMapper {

    public record SecurityResult(
        SecurityConfig securityConfig,
        String aclString,
        List<String> identityUsers,
        List<String> identityGroups,
        String tenantId
    ) {}

    public static SecurityResult mapSecurity(
            Map<String, Object> row,
            Set<String> userColumns,
            Set<String> groupColumns,
            String tenantColumn,
            String defaultPermission) {

        List<PermissionRule> rules = new ArrayList<>();
        List<String> users = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        String perm = (defaultPermission != null && !defaultPermission.isBlank()) ? defaultPermission : "read";

        if (userColumns != null) {
            for (String col : userColumns) {
                Object val = getCaseInsensitive(row, col);
                if (val != null && !val.toString().isBlank()) {
                    String user = val.toString().trim();
                    users.add(user);
                    rules.add(new PermissionRule(user, "user", user, perm));
                }
            }
        }

        if (groupColumns != null) {
            for (String col : groupColumns) {
                Object val = getCaseInsensitive(row, col);
                if (val != null && !val.toString().isBlank()) {
                    String group = val.toString().trim();
                    groups.add(group);
                    rules.add(new PermissionRule(group, "group", group, perm));
                }
            }
        }

        String tenantId = null;
        if (tenantColumn != null && !tenantColumn.isBlank()) {
            Object val = getCaseInsensitive(row, tenantColumn);
            if (val != null) {
                tenantId = val.toString().trim();
            }
        }

        if (rules.isEmpty()) {
            return new SecurityResult(SecurityConfig.createPublic(), "public", List.of(), List.of(), tenantId);
        }

        SecurityConfig config = new SecurityConfig(false, rules);
        StringBuilder aclBuilder = new StringBuilder();
        for (String u : users) {
            if (!aclBuilder.isEmpty()) aclBuilder.append(";");
            aclBuilder.append("user:").append(u);
        }
        for (String g : groups) {
            if (!aclBuilder.isEmpty()) aclBuilder.append(";");
            aclBuilder.append("group:").append(g);
        }

        return new SecurityResult(config, aclBuilder.toString(), users, groups, tenantId);
    }

    private static Object getCaseInsensitive(Map<String, Object> map, String key) {
        if (map == null || key == null) return null;
        if (map.containsKey(key)) return map.get(key);
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
