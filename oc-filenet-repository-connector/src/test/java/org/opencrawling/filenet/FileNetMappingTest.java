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
package org.opencrawling.filenet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNetMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static FileNetProperties settings(Map<String, String> extra) {
        Map<String, String> c = new HashMap<>(Map.of("url", "https://cpe.example.com/content-services-graphql/graphql",
                "objectStore", "OS1", "username", "p8crawler", "password", "secret"));
        c.putAll(extra);
        return FileNetProperties.fromConfiguration(c);
    }

    static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text.replace('\'', '"'));
    }

    private static List<String> identities(FileNetSecurityMapper.Mapped m, String access) {
        return m.security().permissions().stream().filter(r -> r.access().equals(access)).map(PermissionRule::identity).toList();
    }

    private static String ace(String name, String type, String access, int mask) {
        return "{'granteeName':'" + name + "','granteeType':'" + type + "','accessType':'" + access + "','accessMask':" + mask
                + ",'permissionSource':'SOURCE_DIRECT'}";
    }

    // ------------------------------------------------------------------ settings

    @Test
    void defaultsAndValidation() {
        FileNetProperties s = settings(Map.of());
        assertTrue(s.validate().isEmpty(), s.validate().toString());
        assertEquals(List.of("Document"), s.documentClasses());
        assertEquals(FileNetProperties.VersionMode.CURRENT, s.versionMode());
        assertEquals(131201, s.readAccessMask());
        assertEquals(FileNetProperties.DeletionTracking.RECONCILE, s.deletionTracking());
        assertEquals("https://cpe.example.com/content-services-graphql", s.contentBase());
        assertFalse(s.toString().contains("secret"));
        assertFalse(settings(Map.of("authType", "kerberos")).validate().isEmpty());
        assertFalse(settings(Map.of("documentClasses", "Doc ument")).validate().isEmpty());
        assertFalse(settings(Map.of("crawlMode", "folder", "rootFolderPath", "Mortgages")).validate().isEmpty());
        assertFalse(settings(Map.of("authType", "oauth2")).validate().isEmpty());
        assertTrue(settings(Map.of("authType", "ltpa", "ltpaToken", "x")).validate().isEmpty());
        // the issue's aliases
        assertEquals(FileNetProperties.VersionMode.RELEASED, settings(Map.of("versionMode", "latest_major")).versionMode());
        assertEquals(FileNetProperties.VersionMode.ALL, settings(Map.of("versionMode", "all_versions")).versionMode());
        assertEquals(FileNetProperties.DeletionTracking.NONE,
                settings(Map.of("deletionTrackingEnabled", "false")).deletionTracking());
    }

    @Test
    void springPropertiesAreKebabCaseWithNestedOAuth2() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.opencrawling.connector.filenet.object-store", "OS9")
                .withProperty("spring.opencrawling.connector.filenet.auth-type", "oauth2")
                .withProperty("spring.opencrawling.connector.filenet.oauth2.token-url", "https://iam/token")
                .withProperty("spring.opencrawling.connector.filenet.oauth2.client-id", "c")
                .withProperty("spring.opencrawling.connector.filenet.oauth2.client-secret", "s")
                .withProperty("spring.opencrawling.connector.filenet.document-classes[0]", "Contract")
                .withProperty("spring.opencrawling.connector.filenet.document-classes[1]", "Invoice");
        FileNetProperties s = FileNetProperties.fromEnvironment(env);
        assertEquals("OS9", s.objectStore());
        assertEquals("https://iam/token", s.oauth2TokenUrl());
        assertEquals(List.of("Contract", "Invoice"), s.documentClasses());
        assertTrue(s.validate().isEmpty(), s.validate().toString());
    }

    @Test
    void sqlQueryGivesFromAndWhere() {
        FileNetProperties s = settings(Map.of("sqlQuery", "SELECT Id, Name FROM LoanDocument d WITH INCLUDESUBCLASSES "
                + "WHERE d.[LoanStatus] = 'ACTIVE' ORDER BY DateCreated"));
        String[] parsed = s.parsedSql().orElseThrow();
        assertEquals("LoanDocument d WITH INCLUDESUBCLASSES", parsed[0]);
        assertEquals("d.[LoanStatus] = 'ACTIVE'", parsed[1]);
        assertNull(settings(Map.of("sqlQuery", "SELECT Id FROM Document")).parsedSql().orElseThrow()[1]);
        assertFalse(settings(Map.of("sqlQuery", "DELETE FROM Document")).validate().isEmpty());
    }

    @Test
    void searchesCombineVersionFolderAndConditions() {
        FileNetRepositoryConnector c = new FileNetRepositoryConnector(settings(Map.of("crawlMode", "folder",
                "rootFolderPath", "/Mortgages/O'Brien", "where", "[DocumentTitle] LIKE 'Loan%'",
                "documentClasses", "Contract,Invoice", "modifiedAfter", "2026-01-01")));
        List<String[]> searches = c.searches(null);
        assertEquals(2, searches.size());
        assertEquals("Contract", searches.get(0)[0]);
        assertEquals("[IsCurrentVersion] = True AND This INSUBFOLDER '/Mortgages/O''Brien' AND ([DocumentTitle] LIKE 'Loan%') "
                + "AND [DateLastModified] >= 20260101T000000Z", searches.get(0)[1]);
        assertEquals("[VersionStatus] = 1 AND This INFOLDER '/X'", new FileNetRepositoryConnector(settings(Map.of(
                "versionMode", "released", "includeSubfolders", "false"))).searches("/X").get(0)[1]);
        assertEquals("", new FileNetRepositoryConnector(settings(Map.of("versionMode", "all"))).searches(null).get(0)[1]);
        assertEquals("20260304T050607Z", FileNetRepositoryConnector.ceTimestamp(Instant.parse("2026-03-04T05:06:07.890Z")));
    }

    // ------------------------------------------------------------------ documents

    @Test
    void idsAreStablePerVersionSeriesExceptInAllVersionMode() throws Exception {
        JsonNode doc = json("{'id':'{AAAA0000-0000-0000-0000-000000000001}','versionSeries':{'id':'{BBBB0000-0000-0000-0000-000000000002}'}}");
        assertEquals("filenet://OS1/documents/BBBB0000-0000-0000-0000-000000000002", new FileNetDocumentBuilder(settings(Map.of())).id(doc));
        assertEquals("filenet://OS1/documents/BBBB0000-0000-0000-0000-000000000002/versions/AAAA0000-0000-0000-0000-000000000001",
                new FileNetDocumentBuilder(settings(Map.of("versionMode", "all"))).id(doc));
        assertEquals("x/elements/2", FileNetDocumentBuilder.elementId("x", 2));
        assertEquals("x", FileNetDocumentBuilder.elementId("x", 0));
    }

    @Test
    void documentMetadataAndCustomProperties() throws Exception {
        JsonNode doc = json("{'id':'{A1}','name':'Loan agreement','className':'LoanDocument','creator':'marcus',"
                + "'dateCreated':'2026-02-18T10:15:00.000Z','lastModifier':'elena','dateLastModified':'2026-09-29T16:40:00+02:00',"
                + "'majorVersionNumber':2,'minorVersionNumber':0,'isCurrentVersion':true,'versionStatus':'RELEASED',"
                + "'contentSize':5242880.0,'versionSeries':{'id':'{S1}'},"
                + "'properties':[{'id':'LoanNumber','type':'STRING','cardinality':'SINGLE','value':'LN-1'},"
                + "{'id':'LoanAmount','type':'DOUBLE','cardinality':'SINGLE','value':4500000.0},"
                + "{'id':'InterestRate','type':'DOUBLE','cardinality':'SINGLE','value':5.75},"
                + "{'id':'Regions','type':'STRING','cardinality':'LIST','value':['EMEA','APAC']},"
                + "{'id':'ClosingDate','type':'DATE','cardinality':'SINGLE','value':'2026-03-01T00:00:00+01:00'},"
                + "{'id':'URLAddress','type':'STRING','cardinality':'SINGLE','value':'https://x'},"
                + "{'id':'Owner','type':'STRING','cardinality':'SINGLE','value':'ceadmin'},"
                + "{'id':'StorageArea','type':'OBJECT','cardinality':'SINGLE','value':null},"
                + "{'id':'Empty','type':'STRING','cardinality':'SINGLE','value':null}]}");
        JsonNode element = json("{'elementSequenceNumber':1,'contentType':'application/pdf','retrievalName':'annex.pdf',"
                + "'contentSize':10.0,'downloadUrl':'/content?x'}");
        FileNetSecurityMapper.Mapped security = new FileNetSecurityMapper(settings(Map.of())).map(json(
                "[" + ace("elena", "USER", "ALLOW", 131201) + "]"));
        RepositoryDocument d = new FileNetDocumentBuilder(settings(Map.of())).build(doc, element, 2, null,
                List.of("/Mortgages/2026", "/Archive"), security);
        Map<String, List<String>> m = d.metadata();
        assertEquals("filenet://OS1/documents/S1/elements/1", d.id());
        assertEquals(DocumentAction.UPSERT, d.action());
        assertEquals(List.of("filenet://OS1/documents/S1"), m.get("filenet.parentId"));
        assertEquals(List.of("annex.pdf"), m.get("name"));
        assertEquals(List.of("Loan agreement"), m.get("title"));
        assertEquals(List.of("application/pdf"), m.get("mimeType"));
        assertEquals(List.of("2.0"), m.get("filenet.versionLabel"));
        assertEquals(List.of("2026-09-29T14:40:00Z"), m.get("filenet.dateLastModified"));
        assertEquals(List.of("5242880"), m.get("filenet.contentSize"));
        assertEquals(List.of("/Mortgages/2026", "/Archive"), m.get("filenet.folderPath"));
        assertEquals(List.of("LN-1"), m.get("filenet_prop_loanNumber"));
        assertEquals(List.of("4500000"), m.get("filenet_prop_loanAmount"));
        assertEquals(List.of("5.75"), m.get("filenet_prop_interestRate"));
        assertEquals(List.of("EMEA", "APAC"), m.get("filenet_prop_regions"));
        assertEquals(List.of("2026-02-28T23:00:00Z"), m.get("filenet_prop_closingDate"));
        assertEquals(List.of("https://x"), m.get("filenet_prop_urlAddress"));
        assertFalse(m.containsKey("filenet_prop_owner"), "system properties are skipped by default");
        assertFalse(m.containsKey("filenet_prop_storageArea"));
        assertFalse(m.containsKey("filenet_prop_empty"));
        assertEquals(List.of("elena"), m.get("filenet_identity_users"));
        assertEquals(Instant.parse("2026-09-29T14:40:00Z"), d.lastModified());
        assertTrue(new FileNetDocumentBuilder(settings(Map.of("includeSystemProperties", "true"))).build(doc, null, 0, null,
                List.of(), security).metadata().containsKey("filenet_prop_owner"));
    }

    @Test
    void camelCaseOfSymbolicNames() {
        assertEquals("loanNumber", FileNetDocumentBuilder.camel("LoanNumber"));
        assertEquals("urlAddress", FileNetDocumentBuilder.camel("URLAddress"));
        assertEquals("id", FileNetDocumentBuilder.camel("ID"));
        assertEquals("already", FileNetDocumentBuilder.camel("already"));
        assertEquals("a", FileNetDocumentBuilder.camel("A"));
    }

    @Test
    void tombstoneCarriesNoContent() {
        RepositoryDocument t = FileNetDocumentBuilder.tombstone("filenet://OS1/documents/S1", "OS1", "{A1}");
        assertEquals(DocumentAction.DELETE, t.action());
        assertNull(t.contentStream());
        assertEquals(List.of("DELETED"), t.metadata().get("filenet.status"));
    }

    // ------------------------------------------------------------------ security

    @Test
    void viewerLevelGrantsAndPartialRightsDoNot() throws Exception {
        FileNetSecurityMapper.Mapped m = new FileNetSecurityMapper(settings(Map.of())).map(json("["
                + ace("Contract Readers", "GROUP", "ALLOW", 131201) + "," + ace("jdoe", "USER", "ALLOW", 998903) + ","
                + ace("Auditors", "GROUP", "ALLOW", 1) + "," + ace("Viewers", "GROUP", "ALLOW", 129) + "]"));
        assertEquals(List.of("contract readers", "jdoe"), identities(m, "read"));
        assertEquals(List.of("jdoe"), m.users());
        assertEquals(List.of("contract readers"), m.groups());
        assertEquals("jdoe,contract readers", m.acl());
        assertFalse(m.security().inheritanceEnabled());
    }

    @Test
    void allowsAccumulateAcrossEntriesAndSources() throws Exception {
        // read from a direct entry, view content and read permissions inherited from the folder
        FileNetSecurityMapper.Mapped m = new FileNetSecurityMapper(settings(Map.of())).map(json("["
                + ace("asmith", "USER", "ALLOW", 1) + ","
                + "{'granteeName':'asmith','granteeType':'USER','accessType':'ALLOW','accessMask':131200,'permissionSource':'SOURCE_PARENT'}]"));
        assertEquals(List.of("asmith"), identities(m, "read"));
    }

    @Test
    void aDenyOfAnyReadRightDeniesUnlikeManifoldCf() throws Exception {
        // ManifoldCF only honours a deny of the whole view level; a deny of view content alone must still deny
        FileNetSecurityMapper.Mapped m = new FileNetSecurityMapper(settings(Map.of())).map(json("["
                + ace("Legal", "GROUP", "ALLOW", 131201) + "," + ace("mallory", "USER", "ALLOW", 131201) + ","
                + ace("mallory", "USER", "DENY", 128) + "]"));
        assertEquals(List.of("legal"), identities(m, "read"));
        assertEquals(List.of("mallory"), identities(m, "deny"));
        // a deny of rights outside the read mask (e.g. delete) does not matter
        FileNetSecurityMapper.Mapped n = new FileNetSecurityMapper(settings(Map.of())).map(json("["
                + ace("Legal", "GROUP", "ALLOW", 131201) + "," + ace("Legal", "GROUP", "DENY", 65536) + "]"));
        assertEquals(List.of("legal"), identities(n, "read"));
    }

    @Test
    void specialGranteesAndUnknownPrincipals() throws Exception {
        String acl = "[" + ace("#AUTHENTICATED-USERS", "GROUP", "ALLOW", 131201) + ","
                + ace("#CREATOR-OWNER", "USER", "ALLOW", 998903) + ","
                + ace("S-1-5-21-1-2-3-512", "UNKNOWN", "ALLOW", 131201) + "]";
        FileNetSecurityMapper.Mapped m = new FileNetSecurityMapper(settings(Map.of())).map(json(acl));
        assertEquals(List.of("#authenticated-users", "s-1-5-21-1-2-3-512"), identities(m, "read"));
        FileNetSecurityMapper.Mapped pub = new FileNetSecurityMapper(settings(Map.of("authenticatedUsersPublic", "true")))
                .map(json("[" + ace("#AUTHENTICATED-USERS", "GROUP", "ALLOW", 131201) + ","
                        + ace("mallory", "USER", "DENY", 131201) + "]"));
        assertEquals("public", pub.acl());
        assertEquals(List.of("public"), identities(pub, "read"));
        assertEquals(List.of("mallory"), identities(pub, "deny"), "a deny still excludes its grantee from a public document");
        FileNetSecurityMapper.Mapped none = new FileNetSecurityMapper(settings(Map.of("authenticatedUsersIdentity", "")))
                .map(json("[" + ace("#AUTHENTICATED-USERS", "GROUP", "ALLOW", 131201) + "]"));
        assertTrue(identities(none, "read").isEmpty());
    }

    @Test
    void nothingReadableIsNobodyOrTheFallbackNeverPublic() throws Exception {
        FileNetSecurityMapper mapper = new FileNetSecurityMapper(settings(Map.of()));
        assertTrue(mapper.map(json("[]")).security().permissions().isEmpty());
        assertTrue(mapper.map(json("[{}, {'className':'CmRolePermission'}]")).security().permissions().isEmpty());
        assertTrue(mapper.map(null).security().permissions().isEmpty());
        FileNetSecurityMapper.Mapped fb = new FileNetSecurityMapper(settings(Map.of("fallbackPrincipals", "records-team")))
                .map(json("[" + ace("Auditors", "GROUP", "ALLOW", 1) + "]"));
        assertEquals(List.of("records-team"), identities(fb, "read"));
        assertEquals("public", new FileNetSecurityMapper(settings(Map.of("includeAcls", "false"))).map(json("[]")).acl());
    }

    @Test
    void shortPrincipalFormatAndFingerprint() throws Exception {
        FileNetSecurityMapper mapper = new FileNetSecurityMapper(settings(Map.of("principalFormat", "short")));
        assertEquals("finance", mapper.identity("cn=Finance,ou=groups,dc=example,dc=com"));
        assertEquals("jdoe", mapper.identity("CORP\\JDoe"));
        assertEquals("jdoe@example.com", mapper.identity("JDoe@Example.com"));
        assertEquals("cn=finance,ou=groups", new FileNetSecurityMapper(settings(Map.of())).identity("cn=Finance,ou=groups"));
        FileNetSecurityMapper.Mapped a = mapper.map(json("[" + ace("a", "USER", "ALLOW", 131201) + "," + ace("b", "USER", "ALLOW", 131201) + "]"));
        FileNetSecurityMapper.Mapped b = mapper.map(json("[" + ace("b", "USER", "ALLOW", 131201) + "," + ace("a", "USER", "ALLOW", 131201) + "]"));
        assertEquals(a.fingerprint(), b.fingerprint(), "order does not change the fingerprint");
        assertFalse(a.fingerprint().equals(mapper.map(json("[" + ace("a", "USER", "ALLOW", 131201) + "]")).fingerprint()));
    }
}
