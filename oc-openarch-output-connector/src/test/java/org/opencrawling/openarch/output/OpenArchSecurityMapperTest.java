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
package org.opencrawling.openarch.output;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.openarch.output.security.OpenArchSecurityMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class OpenArchSecurityMapperTest {

    @Test
    public void testOisToOpenArchAclMapping() {
        List<PermissionRule> rules = List.of(
                new PermissionRule("user:alice", "user", "Alice", "READ"),
                new PermissionRule("user:bob", "user", "Bob", "WRITE"),
                new PermissionRule("group:engineering", "group", "Engineering Team", "ADMIN"),
                new PermissionRule("user:mallory", "user", "Mallory", "deny")
        );
        SecurityConfig config = new SecurityConfig(true, rules);

        List<Map<String, Object>> openArchAcl = OpenArchSecurityMapper.toOpenArchAcl(config);
        assertEquals(4, openArchAcl.size());

        // Alice (READ -> Read, granted: true)
        Map<String, Object> aliceAce = openArchAcl.get(0);
        assertEquals("Read", aliceAce.get("permission"));
        assertEquals(true, aliceAce.get("granted"));
        @SuppressWarnings("unchecked")
        Map<String, Object> aliceUser = (Map<String, Object>) aliceAce.get("user");
        assertEquals("alice", aliceUser.get("username"));

        // Bob (WRITE -> Write, granted: true)
        Map<String, Object> bobAce = openArchAcl.get(1);
        assertEquals("Write", bobAce.get("permission"));
        assertEquals(true, bobAce.get("granted"));

        // Engineering group (ADMIN -> ManageSecurity, granted: true)
        Map<String, Object> engAce = openArchAcl.get(2);
        assertEquals("ManageSecurity", engAce.get("permission"));
        assertEquals(true, engAce.get("granted"));
        @SuppressWarnings("unchecked")
        Map<String, Object> engGroup = (Map<String, Object>) engAce.get("group");
        assertEquals("engineering", engGroup.get("groupname"));

        // Mallory (deny -> granted: false)
        Map<String, Object> malloryAce = openArchAcl.get(3);
        assertEquals(false, malloryAce.get("granted"));
    }

    @Test
    public void testOpenArchAclToOisMapping() {
        List<Map<String, Object>> aces = List.of(
                Map.of("permission", "Read", "granted", true, "user", Map.of("username", "alice")),
                Map.of("permission", "ManageSecurity", "granted", true, "group", Map.of("groupname", "admins")),
                Map.of("permission", "Read", "granted", false, "user", Map.of("username", "intruder"))
        );

        SecurityConfig config = OpenArchSecurityMapper.fromOpenArchAcl(aces, true);
        assertNotNull(config);
        assertEquals(3, config.permissions().size());

        assertEquals("alice", config.permissions().get(0).identity());
        assertEquals("read", config.permissions().get(0).access());

        assertEquals("group:admins", config.permissions().get(1).identity());
        assertEquals("admin", config.permissions().get(1).access());

        assertEquals("intruder", config.permissions().get(2).identity());
        assertEquals("deny", config.permissions().get(2).access());
    }
}
