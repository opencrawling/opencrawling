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
package org.opencrawling.doxis.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.client.DoxisClient;
import org.opencrawling.doxis.client.schema.DoxisSchema;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DoxisAclMapperTest {

    private DoxisAclMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        DoxisClient client = mock(DoxisClient.class);
        when(client.listUsers()).thenReturn(Fixtures.list("users.json"));
        when(client.listGroups()).thenReturn(Fixtures.list("groups.json"));
        mapper = new DoxisAclMapper(new DoxisSchema(client));
    }

    private static Map<String, Object> ace(String principal, String permission, String variant) {
        return Map.of("organizationalElementId", principal, "permission", permission, "authorizationVariant", variant);
    }

    @Test
    void mapsReadWriteAndDenyToDoxisPermissions() throws Exception {
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("elena.weber", "user", "Elena Weber", "write"),
                new PermissionRule("group:legal-counsel", "group", "Legal", "read"),
                new PermissionRule("Contractors", "group", "Contractors", "deny")));

        List<Map<String, Object>> aces = mapper.aces(security);

        assertEquals(List.of(
                ace("user-elena", "VIEW_DOCUMENT_CONTENTS", "GRANT"),
                ace("user-elena", "UPDATE_DOCUMENT", "GRANT"),
                ace("user-elena", "VERSION_DOCUMENT", "GRANT"),
                ace("group-legal", "VIEW_DOCUMENT_CONTENTS", "GRANT"),
                ace("group-contractors", "VIEW_DOCUMENT_CONTENTS", "DENY")), aces);
    }

    @Test
    void publicMapsToEverybodyAndUnknownIdentitiesAreSkipped() throws Exception {
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("public", "public", "Public Access", "read"),
                new PermissionRule("S-1-5-21-unknown", "user", "Unknown", "read")));

        assertEquals(List.of(ace("00000004-0006-9000-0000-000000000001", "VIEW_DOCUMENT_CONTENTS", "GRANT")),
                mapper.aces(security));
    }
}
