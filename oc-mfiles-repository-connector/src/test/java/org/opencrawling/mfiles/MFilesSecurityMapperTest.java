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
package org.opencrawling.mfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed mapping of M-Files permissions to OIS security. */
class MFilesSecurityMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MFilesSecurityMapper mapper(Map<String, String> extra) {
        Map<String, String> config = new HashMap<>(Map.of("vaultGuid", MockMFiles.VAULT));
        config.putAll(extra);
        Map<String, String> names = Map.of("user:12", "sarah.connor", "group:5", "legal-department");
        return new MFilesSecurityMapper(MFilesProperties.fromConfiguration(config),
                (id, group) -> Optional.ofNullable(names.get((group ? "group:" : "user:") + id)));
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static List<String> identities(MFilesSecurityMapper.Mapped mapped, String access) {
        return mapped.security().permissions().stream().filter(r -> r.access().equals(access)).map(PermissionRule::identity).toList();
    }

    @Test
    void componentsAreIntersectedAndDenyWins() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(
                Optional.of(json(MockMFiles.fixture("permissions-48192.json"))));
        assertEquals(List.of("mfiles:user:12", "mfiles:group:5"), identities(mapped, "read"));
        assertEquals(List.of("mfiles:user:9"), identities(mapped, "deny"));
        assertEquals(List.of("mfiles:user:12"), mapped.users());
        assertEquals(List.of("mfiles:group:5"), mapped.groups());
        assertFalse(mapped.security().inheritanceEnabled());
        // the pseudo-user (IsPsudoUser, M-Files' spelling) is not mistaken for user 1001, the inactive automatic component
        // does not deny user 12, and the NamedACL definition (group 8) is not part of the effective permissions
        assertTrue(mapped.security().permissions().stream().noneMatch(r -> r.identity().equals("mfiles:user:1001")));
        assertTrue(mapped.security().permissions().stream().noneMatch(r -> r.identity().equals("mfiles:group:8")));
    }

    /** The payload a real M-Files server returned (object 100, built-in named ACL "Full control for all internal users"). */
    @Test
    void realServerPayloadGrantsAllInternalUsers() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(
                Optional.of(json(MockMFiles.fixture("permissions-real-server.json"))));
        assertEquals(List.of("mfiles:group:1"), identities(mapped, "read"));
        assertTrue(identities(mapped, "deny").isEmpty());
        assertEquals("mfiles:group:1", mapped.acl());
        assertEquals("public", mapper(Map.of("publicGroupIds", "1")).fromPermissions(
                Optional.of(json(MockMFiles.fixture("permissions-real-server.json")))).acl());
    }

    @Test
    void denyInOneComponentOverridesAllowInAnother() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(Optional.of(json("""
                {"A":[{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Allow"}],
                 "B":[{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Deny"}]}""")));
        assertTrue(identities(mapped, "read").isEmpty());
        assertEquals(List.of("mfiles:user:12"), identities(mapped, "deny"));
    }

    @Test
    void unreadablePermissionsGiveNobodyNotPublic() throws Exception {
        MFilesSecurityMapper m = mapper(Map.of());
        assertTrue(m.fromPermissions(Optional.empty()).security().permissions().isEmpty());
        // pseudo-users (metadata-driven) cannot be resolved, under M-Files' spelling and the regular one
        assertTrue(m.fromPermissions(Optional.of(json("""
                [{"UserOrGroupID":3,"IsPsudoUser":true,"HasConcreteUserOrGruopID":false,"ReadPermission":1}]""")))
                .security().permissions().isEmpty());
        assertTrue(m.fromPermissions(Optional.of(json("""
                [{"UserOrGroupID":3,"IsPseudoUser":true,"ReadPermission":"Allow"}]"""))).security().permissions().isEmpty());
    }

    @Test
    void numericCodesGrantOnlyTheVerifiedAllowValue() throws Exception {
        MFilesSecurityMapper m = mapper(Map.of());
        assertEquals(List.of("mfiles:user:12"), identities(m.fromPermissions(Optional.of(json("""
                [{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":1}]"""))), "read"));
        // any other code may be a deny: it restricts, it never grants
        MFilesSecurityMapper.Mapped mapped = m.fromPermissions(Optional.of(json("""
                [{"UserOrGroupID":5,"IsGroup":true,"ReadPermission":1},
                 {"UserOrGroupID":9,"IsGroup":false,"ReadPermission":2},
                 {"UserOrGroupID":7,"IsGroup":true,"ReadPermission":0}]""")));
        assertEquals(List.of("mfiles:group:5"), identities(mapped, "read"));
        assertEquals(List.of("mfiles:user:9", "mfiles:group:7"), identities(mapped, "deny"));
    }

    @Test
    void inactiveComponentsAndTheNamedAclDefinitionAreIgnored() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(Optional.of(json("""
                {"AccessControlList":{
                   "CustomComponent":{"IsActive":true,"AccessControlEntries":[{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":1}]},
                   "AutomaticComponents":[{"IsActive":false,"AccessControlEntries":[{"UserOrGroupID":14,"IsGroup":false,"ReadPermission":1}]}]},
                 "NamedACL":{"AccessControlList":{"CustomComponent":{"IsActive":true,
                   "AccessControlEntries":[{"UserOrGroupID":8,"IsGroup":true,"ReadPermission":1}]}}}}""")));
        assertEquals(List.of("mfiles:user:12"), identities(mapped, "read"));
    }

    @Test
    void accessFlagsReadBitIsHonoured() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromPermissions(Optional.of(json("""
                {"AccessControlList":{"UserOrGroupAccessList":[
                  {"UserOrGroupID":12,"IsGroup":false,"AccessFlags":3},
                  {"UserOrGroupID":5,"IsGroup":true,"AccessFlags":2}]}}""")));
        assertEquals(List.of("mfiles:user:12"), identities(mapped, "read"));
    }

    @Test
    void fallbackPrincipalsApplyWhenNothingIsReadable() {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("fallbackPrincipals", "group:records-managers,user:auditor"))
                .fromPermissions(Optional.empty());
        assertEquals(List.of("records-managers", "auditor"), identities(mapped, "read"));
        assertEquals("auditor,records-managers", mapped.acl());
        assertEquals(List.of("auditor"), mapped.users());
    }

    @Test
    void publicGroupMapsToPublic() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("publicGroupIds", "1"))
                .fromPermissions(Optional.of(json(MockMFiles.fixture("permissions-everyone.json"))));
        assertEquals("public", mapped.acl());
        assertEquals(List.of("public"), identities(mapped, "read"));
    }

    @Test
    void nameFormatResolvesKnownPrincipalsAndKeepsTokensForTheRest() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("principalFormat", "name"))
                .fromPermissions(Optional.of(json("""
                        [{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Allow"},
                         {"UserOrGroupID":5,"IsGroup":true,"ReadPermission":"Allow"},
                         {"UserOrGroupID":77,"IsGroup":true,"ReadPermission":"Allow"}]""")));
        assertEquals(List.of("sarah.connor", "legal-department", "mfiles:group:77"), identities(mapped, "read"));
    }

    @Test
    void qualifiedPrincipalsCarryTheVault() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of("qualifyPrincipals", "true"))
                .fromPermissions(Optional.of(json("""
                        [{"UserOrGroupID":12,"IsGroup":false,"ReadPermission":"Allow"}]""")));
        assertEquals(List.of(MockMFiles.VAULT_KEY + "/mfiles:user:12"), identities(mapped, "read"));
    }

    @Test
    void vafTokensKeepTheirKind() throws Exception {
        MFilesSecurityMapper.Mapped mapped = mapper(Map.of()).fromTokens(
                json("[\"mfiles:user:12\",\"mfiles:group:3\",\"mfiles:role:3\",\"DOMAIN\\\\Finance\"]"),
                json("[\"mfiles:user:9\"]"));
        assertEquals(List.of("mfiles:user:12", "mfiles:group:3", "mfiles:role:3", "DOMAIN\\Finance"), identities(mapped, "read"));
        assertEquals(List.of("mfiles:user:9"), identities(mapped, "deny"));
        assertEquals(List.of("mfiles:group:3", "mfiles:role:3", "DOMAIN\\Finance"), mapped.groups());
        assertTrue(mapper(Map.of()).fromTokens(json("[]"), null).security().permissions().isEmpty());
    }
}
