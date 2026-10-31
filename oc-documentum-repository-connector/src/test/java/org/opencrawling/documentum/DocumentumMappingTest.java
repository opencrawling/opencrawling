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
package org.opencrawling.documentum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.documentum.DocumentumSecurityMapper.Acl;
import org.opencrawling.documentum.DocumentumSecurityMapper.Entry;
import org.opencrawling.documentum.DocumentumSecurityMapper.Mapped;
import org.opencrawling.documentum.DocumentumSecurityMapper.Principal;
import org.springframework.mock.env.MockEnvironment;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings, DQL statements, attribute values and the permission mapping, without a server. */
class DocumentumMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Function<String, Principal> PRINCIPALS = name -> switch (name) {
        case "Rui Costa" -> new Principal(false, "rcosta");
        case "Mallory Grey" -> new Principal(false, "mallory");
        case "legal" -> new Principal(true, "legal");
        default -> null;
    };

    private static DocumentumProperties settings(Map<String, String> extra) {
        Map<String, String> c = new HashMap<>(Map.of("repositoryName", "corp", "username", "u", "password", "p"));
        c.putAll(extra);
        return DocumentumProperties.fromConfiguration(c);
    }

    private static Acl acl(Entry... entries) {
        return new Acl("450277b680000103", "dmadmin", "test", 1, List.of(entries));
    }

    private static Entry e(String accessor, int permit, int type, boolean group) {
        return new Entry(accessor, permit, type, group);
    }

    private static Mapped map(DocumentumProperties s, Acl acl) {
        return new DocumentumSecurityMapper(s).map(acl, "Rui Costa", "regulatory", PRINCIPALS);
    }

    private static boolean has(Mapped m, String identity, String type, String access) {
        return m.security().permissions().contains(new PermissionRule(identity, type, identity, access));
    }

    // ------------------------------------------------------------------ settings

    @Test
    void defaultsFollowTheIssue() {
        DocumentumProperties s = settings(Map.of());
        assertEquals(DocumentumProperties.VersionMode.CURRENT, s.versionMode());
        assertEquals(List.of("dm_document"), s.objectTypes());
        assertEquals(52_428_800L, s.maxContentSizeBytes());
        assertEquals(100, s.batchSize());
        assertEquals(DocumentumProperties.DeletionTracking.RECONCILE, s.deletionTracking());
        assertNull(s.serverTimeZone(), "automatic");
        assertTrue(s.validate().isEmpty(), s.validate().toString());
        assertEquals(DocumentumProperties.DeletionTracking.AUDIT,
                settings(Map.of("auditDeletionEnabled", "true")).deletionTracking());
        assertEquals(DocumentumProperties.VersionMode.LATEST_MAJOR, settings(Map.of("versionMode", "latest_major")).versionMode());
        assertEquals(DocumentumProperties.VersionMode.ALL, settings(Map.of("versionMode", "all_versions")).versionMode());
        assertEquals(1000, settings(Map.of("batchSize", "5000")).batchSize(), "capped at the REST page maximum");
    }

    @Test
    void springPropertiesUseTheIssuesKebabCaseKeys() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.opencrawling.connector.documentum.url", "https://dctm.example.com/dctm-rest/")
                .withProperty("spring.opencrawling.connector.documentum.repository-name", "corp")
                .withProperty("spring.opencrawling.connector.documentum.auth-type", "oauth2")
                .withProperty("spring.opencrawling.connector.documentum.oauth2.token-url", "https://otds/token")
                .withProperty("spring.opencrawling.connector.documentum.oauth2.client-id", "c")
                .withProperty("spring.opencrawling.connector.documentum.oauth2.client-secret", "s")
                .withProperty("spring.opencrawling.connector.documentum.crawl-mode", "folder")
                .withProperty("spring.opencrawling.connector.documentum.root-cabinet-path", "/Regulatory/")
                .withProperty("spring.opencrawling.connector.documentum.object-types[0]", "oc_contract")
                .withProperty("spring.opencrawling.connector.documentum.object-types[1]", "oc_submission")
                .withProperty("spring.opencrawling.connector.documentum.prefer-rendition", "pdf")
                .withProperty("spring.opencrawling.connector.documentum.server-time-zone", "Europe/Lisbon");
        DocumentumProperties s = DocumentumProperties.fromEnvironment(env);
        assertEquals("https://dctm.example.com/dctm-rest", s.url());
        assertEquals("https://dctm.example.com/dctm-rest/repositories/corp", s.repositoryUrl());
        assertEquals("/Regulatory", s.rootCabinetPath());
        assertEquals(List.of("oc_contract", "oc_submission"), s.objectTypes());
        assertEquals("pdf", s.preferRendition());
        assertEquals(ZoneId.of("Europe/Lisbon"), s.serverTimeZone());
        assertTrue(s.validate().isEmpty(), s.validate().toString());
        assertFalse(s.toString().contains("client-secret") || s.toString().contains("=s,"));
    }

    @Test
    void invalidSettingsAreReported() {
        assertTrue(settings(Map.of("authType", "kerberos")).validate().stream().anyMatch(p -> p.contains("SPNEGO")));
        assertTrue(DocumentumProperties.fromConfiguration(Map.of()).validate().stream()
                .anyMatch(p -> p.contains("repository-name")));
        assertTrue(settings(Map.of("crawlMode", "folder")).validate().stream().anyMatch(p -> p.contains("root-cabinet-path")));
        assertTrue(settings(Map.of("objectTypes", "dm_document; DROP")).validate().stream().anyMatch(p -> p.contains("type")));
        assertTrue(settings(Map.of("dqlQuery", "DELETE dm_document OBJECTS")).validate().stream()
                .anyMatch(p -> p.contains("dql-query")));
        assertTrue(settings(Map.of("modifiedAfter", "yesterday")).validate().stream().anyMatch(p -> p.contains("modified-after")));
    }

    // ------------------------------------------------------------------ DQL

    @Test
    void dateLiteralsAreWrittenInTheServerTimeZone() {
        Instant t = Instant.parse("2026-07-01T10:15:30.900Z");
        assertEquals("DATE('2026/07/01 10:15:30','yyyy/mm/dd hh:mi:ss')", DocumentumDql.date(t, ZoneOffset.UTC));
        assertEquals("DATE('2026/07/01 12:15:30','yyyy/mm/dd hh:mi:ss')", DocumentumDql.date(t, ZoneId.of("Europe/Berlin")));
        assertEquals("DATE('2026/07/01 05:15:30','yyyy/mm/dd hh:mi:ss')", DocumentumDql.date(t, ZoneOffset.ofHours(-5)));
    }

    @Test
    void listingsUseTheScopeAndAKeysetCursor() {
        DocumentumProperties s = settings(Map.of("crawlMode", "folder", "rootCabinetPath", "/Legal's", "where",
                "a_status = 'Approved'", "modifiedAfter", "2026-01-01"));
        DocumentumDql dql = new DocumentumDql(s);
        List<String> scope = dql.scope(null, ZoneOffset.UTC);
        assertEquals(List.of("FOLDER('/Legal''s', DESCEND)", "(a_status = 'Approved')",
                "r_modify_date >= DATE('2026/01/01 00:00:00','yyyy/mm/dd hh:mi:ss')"), scope);
        String first = dql.listing("dm_document", scope, null, null);
        assertEquals("SELECT " + DocumentumDql.LISTING + " FROM dm_document WHERE FOLDER('/Legal''s', DESCEND) AND "
                + "(a_status = 'Approved') AND r_modify_date >= DATE('2026/01/01 00:00:00','yyyy/mm/dd hh:mi:ss') "
                + "ORDER BY r_modify_date, r_object_id", first);
        String next = dql.listing("dm_document", List.of(), null, new DocumentumDql.Cursor(
                Instant.parse("2026-01-05T10:14:54Z"), ZoneOffset.ofHours(2), "090277b680000105"));
        assertTrue(next.endsWith("WHERE (r_modify_date > DATE('2026/01/05 12:14:54','yyyy/mm/dd hh:mi:ss') OR "
                + "(r_modify_date = DATE('2026/01/05 12:14:54','yyyy/mm/dd hh:mi:ss') AND r_object_id > '090277b680000105')) "
                + "ORDER BY r_modify_date, r_object_id"), next);
        assertEquals("dm_document (ALL)", new DocumentumDql(settings(Map.of("versionMode", "all_versions"))).from("dm_document"));
        assertEquals(List.of("FOLDER('/Legal''s')", "(a_status = 'Approved')"), new DocumentumDql(settings(Map.of(
                "crawlMode", "folder", "rootCabinetPath", "/Legal's", "includeSubfolders", "false",
                "where", "a_status = 'Approved'"))).scope(null, ZoneOffset.UTC));
    }

    @Test
    void aDqlQueryContributesItsTypeAndCondition() {
        DocumentumProperties s = settings(Map.of("dqlQuery",
                "SELECT * FROM oc_contract (ALL) WHERE ANY regions = 'EMEA' ORDER BY object_name ENABLE (RETURN_TOP 10)"));
        assertEquals(List.of("oc_contract"), s.types());
        assertEquals(List.of("(ANY regions = 'EMEA')"), new DocumentumDql(s).scope(null, ZoneOffset.UTC));
    }

    @Test
    void latestMajorIsTheHighestImplicitNDotZeroLabel() throws Exception {
        assertEquals(2, DocumentumRepositoryConnector.major(MAPPER.readTree("[\"2.0\",\"Approved\"]")));
        assertEquals(-1, DocumentumRepositoryConnector.major(MAPPER.readTree("[\"1.1\",\"CURRENT\"]")));
        assertEquals(-1, DocumentumRepositoryConnector.major(MAPPER.readTree("[\"1.1.1.0\"]")), "a branch is not major");
    }

    // ------------------------------------------------------------------ attribute values

    @Test
    void attributeValuesAreFlattened() throws Exception {
        assertEquals(List.of(), DocumentumDocumentBuilder.values(MAPPER.readTree("null")), "an empty repeating attribute");
        assertEquals(List.of("a", "b"), DocumentumDocumentBuilder.values(MAPPER.readTree("[\"a\",\"\",\"b\"]")));
        assertEquals(List.of("9689"), DocumentumDocumentBuilder.values(MAPPER.readTree("9689.0")));
        assertEquals(List.of("10.5"), DocumentumDocumentBuilder.values(MAPPER.readTree("10.5")));
        assertEquals(List.of("2020-07-17T15:51:38Z"),
                DocumentumDocumentBuilder.values(MAPPER.readTree("\"2020-07-17T17:51:38.000+02:00\"")));
        assertEquals(List.of(), DocumentumDocumentBuilder.values(MAPPER.readTree("\"0000000000000000\"")));
        assertEquals(List.of("false"), DocumentumDocumentBuilder.values(MAPPER.readTree("false")));
    }

    @Test
    void idsArePerChronicleOrPerVersion() {
        assertEquals("documentum://corp/documents/0901", new DocumentumDocumentBuilder(settings(Map.of())).id("0901", "0902"));
        assertEquals("documentum://corp/documents/0901/versions/0902",
                new DocumentumDocumentBuilder(settings(Map.of("versionMode", "all"))).id("0901", "0902"));
    }

    // ------------------------------------------------------------------ permissions

    @Test
    void accessPermitsOfReadOrMoreGrantAndLowerOnesDoNot() {
        Mapped m = map(settings(Map.of()), acl(e("dm_world", 2, 0, false), e("finance", 3, 0, true),
                e("John Doe", 6, 0, false), e("browsers", 2, 0, true), e("none", 1, 0, true)));
        assertTrue(has(m, "finance", "group", "read"));
        assertTrue(has(m, "john doe", "user", "read"));
        assertEquals(2, m.security().permissions().size());
        assertEquals(List.of("john doe"), m.users());
        assertEquals(List.of("finance"), m.groups());
    }

    @Test
    void restrictionsBecomeDeniesThatWin() {
        Mapped m = map(settings(Map.of()), acl(e("emea", 3, 0, true), e("Mallory Grey", 3, 3, false),
                e("Mallory Grey", 7, 0, false), e("legal", 5, 3, true)));
        assertTrue(has(m, "emea", "group", "read"));
        assertTrue(has(m, "mallory grey", "user", "deny"), "a restriction of READ takes READ away");
        assertFalse(has(m, "mallory grey", "user", "read"));
        assertFalse(has(m, "legal", "group", "deny"), "a restriction of VERSION leaves READ");
    }

    @Test
    void ownerGroupAndWorldAreResolved() {
        Mapped m = map(settings(Map.of()), acl(e("dm_owner", 7, 0, false), e("dm_group", 3, 0, false),
                e("dm_world", 3, 0, false)));
        assertTrue(has(m, "rui costa", "user", "read"));
        assertTrue(has(m, "regulatory", "group", "read"));
        assertTrue(has(m, "dm_world", "group", "read"));
        Mapped pub = map(settings(Map.of("worldPublic", "true")), acl(e("dm_world", 3, 0, false),
                e("Mallory Grey", 1, 3, false)));
        assertEquals("public", pub.acl());
        assertTrue(has(pub, "mallory grey", "user", "deny"));
        Mapped noWorld = map(settings(Map.of("worldIdentity", "")), acl(e("dm_world", 3, 0, false)));
        assertTrue(noWorld.security().permissions().isEmpty(), "dm_world grants nothing without an identity");
        Mapped ownerGroup = new DocumentumSecurityMapper(settings(Map.of())).map(acl(e("dm_owner", 7, 0, false)), "legal",
                "x", PRINCIPALS);
        assertTrue(has(ownerGroup, "legal", "group", "read"), "an owner that is a group");
    }

    @Test
    void requiredGroupsExcludeOrFallBack() {
        Acl required = acl(e("finance", 3, 0, true), e("legal", 0, 6, true));
        assertTrue(map(settings(Map.of()), required).excluded());
        Mapped fallback = map(settings(Map.of("requiredGroupDocuments", "fallback", "fallbackPrincipals", "records-admins")),
                required);
        assertFalse(fallback.excluded());
        assertEquals(List.of(new PermissionRule("records-admins", "group", "records-admins", "read")),
                fallback.security().permissions());
        assertTrue(map(settings(Map.of()), acl(e("dm_world", 3, 0, false), e("emea", 0, 7, true))).excluded());
    }

    @Test
    void nothingReadableIsNeverPublic() {
        Mapped nobody = map(settings(Map.of()), acl(e("dm_world", 2, 0, false), e("Kim Lee", 0, 1, false),
                e("Anna Smith", 0, 2, false)));
        assertTrue(nobody.security().permissions().isEmpty());
        assertFalse(nobody.security().permissions().stream().anyMatch(r -> r.identity().equals("public")));
        assertTrue(map(settings(Map.of()), null).security().permissions().isEmpty(), "a missing permission set");
        Mapped worldDenied = map(settings(Map.of("fallbackPrincipals", "admins")), acl(e("finance", 3, 0, true),
                e("dm_world", 3, 3, false)));
        assertEquals(List.of("admins"), worldDenied.groups(), "a restriction on dm_world leaves nobody but the fallback");
        assertTrue(map(settings(Map.of()), acl(e("%reviewers", 3, 0, true))).security().permissions().isEmpty());
    }

    @Test
    void loginFormatUsesLoginNamesAndDropsUnknownUsers() {
        Mapped m = map(settings(Map.of("principalFormat", "login")), acl(e("dm_owner", 7, 0, false),
                e("Mallory Grey", 3, 3, false), e("Ghost User", 3, 0, false), e("legal", 3, 0, true)));
        assertTrue(has(m, "rcosta", "user", "read"));
        assertTrue(has(m, "mallory", "user", "deny"));
        assertTrue(has(m, "legal", "group", "read"));
        assertFalse(m.security().permissions().stream().anyMatch(r -> r.identity().contains("ghost")));
    }

    @Test
    void aRestrictionOnSomeoneWithoutALoginLeavesTheFallbackOnly() {
        // "Ghost User" has no resolvable login (unknown, or a login several login domains share): dropping the
        // restriction would let them read through finance
        DocumentumProperties s = settings(Map.of("principalFormat", "login", "fallbackPrincipals", "admins"));
        Mapped m = map(s, acl(e("finance", 3, 0, true), e("Ghost User", 3, 3, false)));
        assertFalse(has(m, "finance", "group", "read"));
        assertEquals(List.of("admins"), m.groups());
        Mapped byName = map(settings(Map.of()), acl(e("finance", 3, 0, true), e("Ghost User", 3, 3, false)));
        assertTrue(has(byName, "finance", "group", "read"), "by name the restriction is a deny as usual");
        assertTrue(has(byName, "ghost user", "user", "deny"));
        Mapped alias = map(s, acl(e("finance", 3, 0, true), e("%reviewers", 3, 3, true)));
        assertTrue(has(alias, "finance", "group", "read"), "an unresolved template alias names nobody");
    }

    @Test
    void inListsKeepEveryRequestLineWellUnderTheServletContainersLimit() {
        String root = "https://dctm.example.com/dctm-rest/repositories/corp?page=1&items-per-page=1000&dql=";
        List<String> ids = IntStream.range(0, 1000).mapToObj(i -> String.format("09%014x", i)).toList();
        for (List<String> chunk : DocumentumRepositoryConnector.chunks(ids, DocumentumDql.IN_LIST)) {
            String url = root + URLEncoder.encode(DocumentumDql.folders(chunk), StandardCharsets.UTF_8);
            assertTrue(url.length() < 4000, url.length() + " characters");
        }
        List<String> names = IntStream.range(0, 400)
                .mapToObj(i -> "A rather long user name, number " + i + ", of the Finance & Controlling department").toList();
        List<List<String>> chunks = DocumentumRepositoryConnector.chunks(names, DocumentumDql.IN_LIST, n -> 4 + n.length());
        assertEquals(names.size(), chunks.stream().mapToInt(List::size).sum());
        for (List<String> chunk : chunks) {
            String url = root + URLEncoder.encode(DocumentumDql.users(chunk), StandardCharsets.UTF_8);
            assertTrue(url.length() < 6000, url.length() + " characters");
        }
    }

    @Test
    void fingerprintChangesWithTheRulesOnly() {
        DocumentumProperties s = settings(Map.of());
        Mapped a = map(s, acl(e("finance", 3, 0, true), e("legal", 4, 0, true)));
        Mapped b = map(s, acl(e("legal", 5, 0, true), e("finance", 7, 0, true)));
        Mapped c = map(s, acl(e("finance", 3, 0, true)));
        assertEquals(a.fingerprint(), b.fingerprint(), "same readers, different permits and order");
        assertFalse(a.fingerprint().equals(c.fingerprint()));
    }

    @Test
    void aclRowsAreParallelArrays() throws Exception {
        Acl a = Acl.of(MAPPER.readTree(getClass().getResourceAsStream("/documentum-mock-responses/acl-finance-restricted.json")));
        assertEquals(5, a.entries().size());
        assertEquals(new Entry("Mallory Grey", 3, 3, false), a.entries().get(4));
        assertEquals(2, a.vstamp());
        assertEquals("dmadmin", a.domain());
    }
}
