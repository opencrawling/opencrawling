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
package org.opencrawling.doxis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.client.schema.DoxisSchema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DoxisDocumentBuilder}, {@link DoxisSecurityMapper} and {@link DoxisRepositorySettings}, without HTTP.
 */
class DoxisMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private static JsonNode ace(String org, String permission, String variant) {
        return MAPPER.createObjectNode().put("organizationalElementId", org).put("permissionName", permission)
                .put("authorizationVariant", variant);
    }

    private static final Map<String, DoxisSchema.Principal> PRINCIPALS = Map.of(
            "g-every", new DoxisSchema.Principal("g-every", "everybody", "group"),
            "g-legal", new DoxisSchema.Principal("g-legal", "Legal", "group"),
            "r-admins", new DoxisSchema.Principal("r-admins", "admins", "role"),
            "u-maya", new DoxisSchema.Principal("u-maya", "maya.collins", "user"));

    private static DoxisSecurityMapper mapper(List<String> fallback) {
        return new DoxisSecurityMapper(id -> Optional.ofNullable(PRINCIPALS.get(id)), fallback);
    }

    // ------------------------------------------------------------------ versions and content

    @Test
    void currentVersionPrefersTheFlaggedOneElseTheLast() throws Exception {
        JsonNode flagged = json("""
                {"versions":[{"versionNumber":"1","currentVersion":true},{"versionNumber":"2","currentVersion":false}]}""");
        JsonNode unflagged = json("""
                {"versions":[{"versionNumber":"1"},{"versionNumber":"2"}]}""");

        assertEquals("1", DoxisDocumentBuilder.currentVersion(flagged).orElseThrow().path("versionNumber").asText());
        assertEquals("2", DoxisDocumentBuilder.currentVersion(unflagged).orElseThrow().path("versionNumber").asText());
        assertTrue(DoxisDocumentBuilder.currentVersion(json("{}")).isEmpty());
    }

    @Test
    void contentUsesTheDefaultRepresentationsFirstContentObject() throws Exception {
        JsonNode version = json("""
                {"versionNumber":"2","representations":[
                  {"uuid":"r-rend","defaultRepresentation":false,"contentObjects":[{"uuid":"c-x","index":0,"length":5}]},
                  {"uuid":"r-main","defaultRepresentation":true,"mimeTypeName":"application/pdf","contentObjects":[
                    {"uuid":"c-2","index":1,"length":9,"fullFilename":"b.pdf"},
                    {"uuid":"c-1","index":0,"length":1234,"fullFilename":"C:\\\\import\\\\NDA-001.pdf"}]}]}""");

        DoxisDocumentBuilder.ContentRef ref = DoxisDocumentBuilder.content(version).orElseThrow();

        assertEquals("2", ref.versionNr());
        assertEquals("r-main", ref.representationId());
        assertEquals("c-1", ref.contentObjectId());
        assertEquals("application/pdf", ref.mimeType());
        assertEquals("NDA-001.pdf", ref.fileName());
        assertEquals(1234, ref.length());
        assertFalse(ref.isLink());
    }

    @Test
    void zeroLengthContentWithAFileNameIsAContentLink() throws Exception {
        JsonNode version = json("""
                {"versionNumber":"1","representations":[{"uuid":"r1","contentObjects":[
                  {"uuid":"c1","length":0,"mimeTypeName":"application/pdf","fullFilename":"\\\\\\\\fs01\\\\share\\\\big.pdf"}]}]}""");

        DoxisDocumentBuilder.ContentRef ref = DoxisDocumentBuilder.content(version).orElseThrow();

        assertTrue(ref.isLink());
        assertEquals("\\\\fs01\\share\\big.pdf", ref.contentLink());
        assertEquals("big.pdf", ref.fileName());
    }

    @Test
    void noRepresentationMeansNoContent() throws Exception {
        assertTrue(DoxisDocumentBuilder.content(json("{\"versionNumber\":\"1\",\"representations\":[]}")).isEmpty());
    }

    // ------------------------------------------------------------------ document building

    private static final JsonNode DOCUMENT;
    private static final JsonNode VERSION;

    static {
        try {
            DOCUMENT = json("""
                    {"uuid":"d1","documentTypeUUID":"t1","modificationDate":"2026-10-05T21:46:00.123+01:00",
                     "creationDate":"2026-10-05T21:45:00.000+01:00","primaryParentObjectUUID":"e1","lifecycleState":"EXTENDABLE"}""");
            VERSION = json("""
                    {"versionNumber":"1","modificationDate":"2026-10-05T21:46:00.123+01:00","attributes":[
                      {"attributeDefinitionUUID":"a-name","values":["Mutual NDA"]},
                      {"attributeDefinitionUUID":"a-authors","values":["Acme","J. Doe"]},
                      {"attributeDefinitionUUID":"a-reserved","values":["x"]},
                      {"attributeDefinitionUUID":"a-empty","values":[]},
                      {"attributeDefinitionUUID":"a-unknown","values":["y"]}]}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Map<String, String> NAMES = Map.of("a-name", "ObjectName", "a-authors", "ObjectAuthors",
            "a-reserved", "title", "a-empty", "ObjectDate");
    private static final DoxisDocumentBuilder.Context CONTEXT = new DoxisDocumentBuilder.Context("DX4", "DB1",
            "TX_MigratedDocument", "Supervisor", "maya.collins", "e1", "Acme Master File");
    private static final DoxisDocumentBuilder.ContentRef CONTENT = new DoxisDocumentBuilder.ContentRef("1", "r1", "c1",
            "application/pdf", "NDA-001.pdf", 1234, null);

    private static RepositoryDocument build(String prefix, boolean descriptors, SecurityConfig security) throws Exception {
        return DoxisDocumentBuilder.build("doxis://DX4/DB1/documents/d1", CONTEXT, DOCUMENT, VERSION, true, CONTENT, descriptors,
                prefix, uuid -> Optional.ofNullable(NAMES.get(uuid)), null, security);
    }

    @Test
    void buildEmitsDescriptorNamesAsIsAndTheIssueSystemFields() throws Exception {
        SecurityConfig security = new SecurityConfig(false, List.of());

        RepositoryDocument doc = build("", true, security);

        assertEquals("doxis://DX4/DB1/documents/d1", doc.id());
        assertEquals(doc.id(), doc.uri());
        assertEquals(DocumentAction.UPSERT, doc.action());
        assertEquals(Instant.parse("2026-10-05T20:46:00.123Z"), doc.lastModified());
        Map<String, List<String>> m = doc.metadata();
        assertEquals(List.of("Mutual NDA"), m.get("ObjectName"));
        assertEquals(List.of("Acme", "J. Doe"), m.get("ObjectAuthors"));
        assertEquals(List.of("x"), m.get("doxis.attr.title"));
        assertFalse(m.containsKey("ObjectDate"));
        assertEquals(List.of("Mutual NDA"), m.get("title"));
        assertEquals(List.of("NDA-001.pdf"), m.get("name"));
        assertEquals(List.of("application/pdf"), m.get("mimeType"));
        assertEquals(List.of("d1"), m.get("doxis.documentId"));
        assertEquals(List.of("TX_MigratedDocument"), m.get("doxis.documentClass"));
        assertEquals(List.of("t1"), m.get("doxis.documentClassId"));
        assertEquals(List.of("1"), m.get("doxis.version"));
        assertEquals(List.of("true"), m.get("doxis.isLatestVersion"));
        assertEquals(List.of("2026-10-05T21:45:00.000+01:00"), m.get("doxis.createdDate"));
        assertEquals(List.of("Supervisor"), m.get("doxis.createdBy"));
        assertEquals(List.of("maya.collins"), m.get("doxis.modifiedBy"));
        assertEquals(List.of("e1"), m.get("doxis.parentFolderId"));
        assertEquals(List.of("Acme Master File"), m.get("doxis.parentFolderName"));
        assertEquals(List.of("1234"), m.get("doxis.fileSize"));
        assertFalse(m.containsKey("doxis.contentLink"));
        assertSame(security, doc.security());
    }

    @Test
    void aDescriptorPrefixAppliesToEveryDescriptor() throws Exception {
        Map<String, List<String>> m = build("doxis_desc_", true, SecurityConfig.createPublic()).metadata();

        assertEquals(List.of("Mutual NDA"), m.get("doxis_desc_ObjectName"));
        assertEquals(List.of("x"), m.get("doxis_desc_title"));
        assertFalse(m.containsKey("ObjectName"));
        assertEquals(List.of("Mutual NDA"), m.get("title"));
    }

    @Test
    void withoutDescriptorsTheTitleIsStillSet() throws Exception {
        Map<String, List<String>> m = build("", false, SecurityConfig.createPublic()).metadata();

        assertFalse(m.containsKey("ObjectName"));
        assertEquals(List.of("Mutual NDA"), m.get("title"));
        assertEquals(List.of("d1"), m.get("doxis.documentId"));
    }

    @Test
    void tombstoneCarriesTheIssueMetadata() {
        RepositoryDocument tombstone = DoxisDocumentBuilder.tombstone("doxis://DX4/DB1/documents/d1", "DX4", "DB1", "d1");

        assertEquals(DocumentAction.DELETE, tombstone.action());
        assertEquals("doxis://DX4/DB1/documents/d1", tombstone.id());
        assertEquals(List.of("d1"), tombstone.metadata().get("doxis.documentId"));
        assertEquals(List.of("DELETED"), tombstone.metadata().get("doxis.status"));
    }

    // ------------------------------------------------------------------ security

    @Test
    void viewGrantsBecomeReadAndEverybodyBecomesPublic() throws Exception {
        SecurityConfig security = mapper(List.of()).map(List.of(
                ace("g-every", "VIEW_DOCUMENT_CONTENTS", "GRANT"),
                ace("u-maya", "VIEW_DOCUMENT_CONTENTS", "GRANT"),
                ace("r-admins", "UPDATE_DOCUMENT", "GRANT"),
                ace("u-maya", "DELETE_DOCUMENT", "GRANT")), List.of());

        assertFalse(security.inheritanceEnabled());
        assertTrue(security.permissions().contains(new PermissionRule("public", "public", "Public Access", "read")));
        assertTrue(security.permissions().contains(new PermissionRule("maya.collins", "user", "maya.collins", "read")));
        assertTrue(security.permissions().contains(new PermissionRule("admins", "group", "admins", "write")));
        assertEquals(3, security.permissions().size());
    }

    @Test
    void eFileAcesAreMergedAndDenyWins() throws Exception {
        SecurityConfig security = mapper(List.of()).map(
                List.of(ace("g-legal", "VIEW_DOCUMENT_CONTENTS", "GRANT")),
                List.of(ace("g-legal", "VIEW_FOLDER_CONTENTS", "DENY"), ace("u-maya", "VIEW_FOLDER_CONTENTS", "GRANT")));

        assertTrue(security.inheritanceEnabled());
        assertTrue(security.permissions().contains(new PermissionRule("Legal", "group", "Legal", "deny")));
        assertTrue(security.permissions().contains(new PermissionRule("maya.collins", "user", "maya.collins", "read")));
        assertEquals(2, security.permissions().size());
    }

    @Test
    void unknownOrganisationalElementIsKeptByUuid() throws Exception {
        SecurityConfig security = mapper(List.of()).map(List.of(ace("x-123", "VIEW_DOCUMENT_CONTENTS", "GRANT")), List.of());

        assertEquals(List.of(new PermissionRule("x-123", "user", "x-123", "read")), security.permissions());
    }

    @Test
    void noInstanceAcesIsDenyByDefaultNeverPublic() throws Exception {
        assertTrue(mapper(List.of()).map(List.of(), List.of()).permissions().isEmpty());
    }

    @Test
    void noInstanceAcesUsesTheConfiguredFallback() throws Exception {
        SecurityConfig security = mapper(List.of("group:Legal", "user:maya.collins", "everybody")).map(List.of(), List.of());

        assertEquals(List.of(
                new PermissionRule("Legal", "group", "Legal", "read"),
                new PermissionRule("maya.collins", "user", "maya.collins", "read"),
                new PermissionRule("public", "public", "Public Access", "read")), security.permissions());
    }

    // ------------------------------------------------------------------ settings

    @Test
    void settingsDefaultsAndCql() {
        DoxisRepositorySettings settings = DoxisRepositorySettings.fromConfiguration(Map.of(
                "customerName", "DX4", "username", "Supervisor", "password", "s3cret",
                "repositoryId", "DB1", "searchQuery", "OBJECTNUMBER2 LIKE 'contracts-2026*'"));

        assertEquals("admins", settings.role());
        assertEquals("basic", settings.authType());
        assertEquals(DoxisRepositorySettings.CrawlMode.SEARCH, settings.crawlMode());
        assertEquals(DoxisRepositorySettings.VersionMode.LATEST_ONLY, settings.versionMode());
        assertEquals(100, settings.batchSize());
        assertEquals(2, settings.parallelism());
        assertTrue(settings.includeContentStream());
        assertTrue(settings.includeDescriptors());
        assertTrue(settings.emitLogicalDeletes());
        assertEquals("", settings.descriptorPrefix());
        assertTrue(settings.validate().isEmpty());
        assertEquals("SELECT * FROM DB1 WHERE (OBJECTNUMBER2 LIKE 'contracts-2026*')", settings.cql("DB1"));
        assertFalse(settings.toString().contains("s3cret"));
    }

    @Test
    void settingsModifiedSinceAddsACondition() {
        DoxisRepositorySettings settings = DoxisRepositorySettings.fromConfiguration(Map.of(
                "modifiedSince", "2026-10-01T00:00:00.000+01:00", "batchSize", "0", "parallelism", "abc"));

        assertEquals("SELECT * FROM DB1 WHERE DXE_MODDATE >= '2026-10-01T00:00:00.000+01:00'", settings.cql("DB1"));
        assertEquals(1, settings.batchSize());
        assertEquals(2, settings.parallelism());
    }

    @Test
    void validationNamesEveryProblem() {
        List<String> problems = DoxisRepositorySettings.fromConfiguration(Map.of("authType", "ticket", "crawlMode", "folder"))
                .validate();

        assertEquals(5, problems.size(), problems.toString());
        assertTrue(problems.getFirst().contains("auth-type 'ticket'"));
        assertTrue(problems.getLast().contains("root-folder-id"));
    }

    @Test
    void springPropertiesUseKebabCaseAndAcceptYamlLists() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.opencrawling.connector.doxis.url", "http://csb:8080/restws/publicws/rest/api/v1")
                .withProperty("spring.opencrawling.connector.doxis.customer-name", "DX4")
                .withProperty("spring.opencrawling.connector.doxis.repository-id", "DB1")
                .withProperty("spring.opencrawling.connector.doxis.crawl-mode", "folder")
                .withProperty("spring.opencrawling.connector.doxis.root-folder-id", "e1")
                .withProperty("spring.opencrawling.connector.doxis.document-classes[0]", "TX_MigratedDocument")
                .withProperty("spring.opencrawling.connector.doxis.document-classes[1]", "TX_LargeExternalDocument")
                .withProperty("spring.opencrawling.connector.doxis.version-mode", "all_versions")
                .withProperty("spring.opencrawling.connector.doxis.max-content-size-bytes", "1024")
                .withProperty("spring.opencrawling.connector.doxis.descriptor-prefix", "doxis_desc_");

        DoxisRepositorySettings settings = DoxisRepositorySettings.fromEnvironment(env);

        assertEquals("http://csb:8080/restws/publicws/rest/api/v1", settings.url());
        assertEquals("DX4", settings.customerName());
        assertEquals(DoxisRepositorySettings.CrawlMode.FOLDER, settings.crawlMode());
        assertEquals("e1", settings.rootFolderId());
        assertEquals(List.of("TX_MigratedDocument", "TX_LargeExternalDocument"), settings.documentClasses());
        assertEquals(DoxisRepositorySettings.VersionMode.ALL_VERSIONS, settings.versionMode());
        assertEquals(1024, settings.maxContentSizeBytes());
        assertEquals("doxis_desc_", settings.descriptorPrefix());
    }
}
