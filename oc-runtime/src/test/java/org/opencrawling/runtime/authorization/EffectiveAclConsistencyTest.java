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
package org.opencrawling.runtime.authorization;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.runtime.mcp.McpAclEvaluator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Some stores keep the {@code security} object next to the flattened fields (pgvector), others keep only
 * the flattened {@code security_allowed_read}/{@code security_denied_read} fields (Qdrant, Milvus, OpenSearch,
 * Solr, Vespa). Whatever {@link EffectiveAcl} writes, both kinds of store must give every caller the same
 * answer.
 */
class EffectiveAclConsistencyTest {

    private static final String[] IDENTITIES = {"alice", "bob", "finance", "hr", "public", "Public", "GROUP_EVERYONE", "user:a;group:b", "IŞIK", "ışık", "ſtrasse", "STRASSE", "Σ", "ς", "cn=bob,ou=users,dc=corp", ""};
    private static final String[] TYPES = {"user", "group", "public", "PUBLIC", null};
    private static final String[] ACCESS = {"read", "write", "admin", "deny", "none", "READ", null};
    private static final String[] ROLES = {"alice", "bob", "finance", "hr", "public", "GROUP_EVERYONE", "ışık", "STRASSE", "ς", "cn=bob,ou=users,dc=corp"};

    @Test
    void storesWithAndWithoutTheSecurityObjectDecideTheSame() {
        Random random = new Random(4545);
        for (int run = 0; run < 20000; run++) {
            Object security = randomSecurity(random);
            String legacyAcl = randomAcl(random);
            String principal = random.nextInt(4) == 0 ? null : ROLES[random.nextInt(ROLES.length)];
            List<String> roles = new ArrayList<>();
            for (int i = random.nextInt(3); i > 0; i--) {
                roles.add(ROLES[random.nextInt(ROLES.length)]);
            }

            EffectiveAcl acl = EffectiveAcl.resolve(security, legacyAcl);

            Map<String, Object> flattened = new HashMap<>();
            if (legacyAcl != null) {
                flattened.put("acl", legacyAcl);
            }
            flattened.put("security_allowed_read", acl.allowedRead());
            flattened.put("security_denied_read", acl.deniedRead());
            Map<String, Object> withSecurity = new HashMap<>(flattened);
            if (acl.security() != null) {
                withSecurity.put("security", acl.security());
            }

            boolean flat = McpAclEvaluator.isAccessible(flattened, principal, roles);
            boolean full = McpAclEvaluator.isAccessible(withSecurity, principal, roles);
            assertThat(full).as("security=%s acl=%s principal=%s roles=%s -> %s", security, legacyAcl, principal,
                    roles, acl).isEqualTo(flat);
        }
    }

    private static Object randomSecurity(Random random) {
        int kind = random.nextInt(5);
        if (kind == 0) {
            return null;
        }
        if (kind == 1) {
            return SecurityConfig.createPublic();
        }
        List<PermissionRule> rules = new ArrayList<>();
        for (int i = random.nextInt(4); i > 0; i--) {
            String identity = IDENTITIES[random.nextInt(IDENTITIES.length)];
            rules.add(new PermissionRule(identity, TYPES[random.nextInt(TYPES.length)], identity,
                    ACCESS[random.nextInt(ACCESS.length)]));
        }
        SecurityConfig config = new SecurityConfig(random.nextBoolean(), rules);
        if (kind == 2) {
            return config;
        }
        // The Map shape a SecurityConfig takes after a JSON round trip through Kafka.
        List<Map<String, Object>> permissions = new ArrayList<>();
        for (PermissionRule rule : rules) {
            Map<String, Object> map = new HashMap<>();
            map.put("identity", rule.identity());
            map.put("identityType", rule.identityType());
            map.put("access", rule.access());
            permissions.add(map);
        }
        return Map.of("inheritanceEnabled", config.inheritanceEnabled(), "permissions", permissions);
    }

    private static String randomAcl(Random random) {
        if (random.nextInt(3) == 0) {
            return null;
        }
        List<String> tokens = new ArrayList<>();
        for (int i = random.nextInt(3); i > 0; i--) {
            tokens.add(random.nextBoolean() ? "read" : IDENTITIES[random.nextInt(IDENTITIES.length)]);
        }
        return String.join(",", tokens);
    }
}
