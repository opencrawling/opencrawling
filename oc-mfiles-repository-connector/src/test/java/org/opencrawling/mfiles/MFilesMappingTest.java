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
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Settings, typed values, ids and OIS document mapping. */
class MFilesMappingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    // ------------------------------------------------------------------ settings

    @Test
    void defaultsFollowTheIssue() {
        MFilesProperties s = MFilesProperties.fromConfiguration(Map.of());
        assertEquals("token", s.authType());
        assertEquals(MFilesProperties.CrawlMode.SEARCH, s.crawlMode());
        assertEquals(0, s.objectType());
        assertEquals(MFilesProperties.VersionMode.LATEST_ONLY, s.versionMode());
        assertEquals(52_428_800L, s.maxFileSizeBytes());
        assertTrue(s.includeFiles() && s.includeProperties() && s.includeAcls() && s.emitDeletes());
        assertFalse(s.incrementalEnabled());
        assertEquals("mfiles_prop_", s.propertyPrefix());
        assertEquals(Duration.ofSeconds(30), s.timeout());
    }

    @Test
    void springPropertiesUseKebabCaseYamlListsAndNestedOauth2() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.opencrawling.connector.mfiles.url", "https://mfiles.example.com/REST/")
                .withProperty("spring.opencrawling.connector.mfiles.vault-guid", "{C0B3B52E-6512-4BD8-8F2B-2A8E9A349201}")
                .withProperty("spring.opencrawling.connector.mfiles.auth-type", "oauth2")
                .withProperty("spring.opencrawling.connector.mfiles.oauth2.token-url", "https://login.example.com/token")
                .withProperty("spring.opencrawling.connector.mfiles.oauth2.client-id", "oc")
                .withProperty("spring.opencrawling.connector.mfiles.oauth2.client-secret", "shh")
                .withProperty("spring.opencrawling.connector.mfiles.crawl-mode", "all_objects")
                .withProperty("spring.opencrawling.connector.mfiles.class-ids[0]", "14")
                .withProperty("spring.opencrawling.connector.mfiles.class-ids[1]", "22")
                .withProperty("spring.opencrawling.connector.mfiles.version-mode", "all_versions")
                .withProperty("spring.opencrawling.connector.mfiles.incremental-enabled", "true")
                .withProperty("spring.opencrawling.connector.mfiles.modified-after", "2026-01-01T00:00:00Z")
                .withProperty("spring.opencrawling.connector.mfiles.max-file-size-bytes", "1024");
        MFilesProperties s = MFilesProperties.fromEnvironment(env);
        assertEquals("https://mfiles.example.com/REST", s.url());
        assertEquals("C0B3B52E-6512-4BD8-8F2B-2A8E9A349201", s.vaultKey());
        assertEquals(MFilesProperties.LoginMode.BEARER, s.loginMode());
        assertEquals("https://login.example.com/token", s.oauth2TokenUrl());
        assertEquals(MFilesProperties.CrawlMode.ALL_OBJECTS, s.crawlMode());
        assertEquals(List.of(14, 22), s.classIds());
        assertEquals(MFilesProperties.VersionMode.ALL_VERSIONS, s.versionMode());
        assertTrue(s.incrementalEnabled());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), s.modifiedAfterInstant().orElseThrow());
        assertEquals(1024, s.maxFileSizeBytes());
        assertTrue(s.validate().isEmpty(), s.validate().toString());
    }

    @Test
    void validationNamesEveryProblem() {
        List<String> problems = MFilesProperties.fromConfiguration(Map.of("url", "mfiles.local", "authType", "kerberos",
                "crawlMode", "view", "incrementalEnabled", "true", "modifiedAfter", "yesterday")).validate();
        String all = String.join(" ", problems);
        assertTrue(all.contains("url"), all);
        assertTrue(all.contains("vault-guid"), all);
        assertTrue(all.contains("kerberos"), all);
        assertTrue(all.contains("view-id"), all);
        assertTrue(all.contains("incremental"), all);
        assertTrue(all.contains("modified-after"), all);
        assertTrue(MFilesProperties.fromConfiguration(Map.of("crawlMode", "vaf")).validate().stream()
                .anyMatch(p -> p.contains("vaf-base-path")));
    }

    @Test
    void toStringNeverShowsSecrets() {
        String text = MFilesProperties.fromConfiguration(Map.of("password", "p@ss", "oauth2ClientSecret", "shh",
                "oauth2AccessToken", "eyJ.secret")).toString();
        assertFalse(text.contains("p@ss") || text.contains("shh") || text.contains("eyJ.secret"));
    }

    @Test
    void stateFileDependsOnVaultAndScope() {
        MFilesProperties a = MFilesProperties.fromConfiguration(Map.of("vaultGuid", "{AAA}", "classIds", "1"));
        MFilesProperties b = MFilesProperties.fromConfiguration(Map.of("vaultGuid", "{AAA}", "classIds", "2"));
        Path fa = MFilesCrawlState.file(a);
        assertTrue(fa.getFileName().toString().startsWith("AAA-"));
        assertNotEquals(fa, MFilesCrawlState.file(b));
        assertEquals(fa, MFilesCrawlState.file(MFilesProperties.fromConfiguration(Map.of("vaultGuid", "AAA", "classIds", "1"))));
    }

    @Test
    void searchConditionsAndViewPaths() {
        assertEquals("", MFilesRepositoryConnector.conditions(" "));
        assertEquals("p1020=5&q=nda", MFilesRepositoryConnector.conditions("?p1020=5&q=nda"));
        assertEquals("q=vendor+nda", MFilesRepositoryConnector.conditions("vendor nda"));
        assertEquals("v101", MFilesRepositoryConnector.viewPath("101"));
        assertEquals("v101/L5", MFilesRepositoryConnector.viewPath("/v101/L5/"));
    }

    // ------------------------------------------------------------------ typed values

    @Test
    void typedValuesAreNormalisedByDataType() throws Exception {
        assertEquals(List.of("2026-05-01"), MFilesValues.normalize(json("{\"DataType\":5,\"Value\":\"2026-05-01T00:00:00Z\"}")).values());
        assertEquals(List.of("2026-09-27T15:20:00Z"),
                MFilesValues.normalize(json("{\"DataType\":7,\"Value\":\"2026-09-27T17:20:00+02:00\"}")).values());
        assertEquals(List.of("150000.5"), MFilesValues.normalize(json("{\"DataType\":3,\"Value\":150000.50}")).values());
        assertEquals(List.of("42"), MFilesValues.normalize(json("{\"DataType\":11,\"Value\":\"42\"}")).values());
        assertEquals(List.of("false"), MFilesValues.normalize(json("{\"DataType\":8,\"Value\":false}")).values());
        MFilesValues.Value lookup = MFilesValues.normalize(json(
                "{\"DataType\":9,\"Lookup\":{\"Item\":5,\"DisplayValue\":\"Cyberdyne Systems\"}}"));
        assertEquals(List.of("Cyberdyne Systems"), lookup.values());
        assertEquals(List.of("5"), lookup.lookupIds());
        MFilesValues.Value multi = MFilesValues.normalize(json("""
                {"DataType":10,"Lookups":[{"Item":1,"DisplayValue":"Legal"},{"Item":2,"DisplayValue":"Gone","Deleted":true}]}"""));
        assertEquals(List.of("Legal"), multi.values());
        assertTrue(MFilesValues.normalize(json("{\"DataType\":1,\"HasValue\":false,\"Value\":null}")).isEmpty());
        assertEquals(List.of("10:30"), MFilesValues.normalize(json("{\"DataType\":12,\"DisplayValue\":\"10:30\"}")).values());
    }

    @Test
    void propertyKeysAreLowerCamelCase() {
        assertEquals("contractValue", MFilesDocumentBuilder.propertyKey("Contract Value", 1021));
        assertEquals("nameOrTitle", MFilesDocumentBuilder.propertyKey("Name or title", 0));
        assertEquals("dateDÉchéance", MFilesDocumentBuilder.propertyKey("Date d'échéance", 1030));
        assertEquals("p1040", MFilesDocumentBuilder.propertyKey("---", 1040));
        assertEquals("p1041", MFilesDocumentBuilder.propertyKey(null, 1041));
    }

    // ------------------------------------------------------------------ ids and documents

    @Test
    void idsAreStableAcrossVersionsAndPerFileForMultiFileDocuments() throws Exception {
        JsonNode msa = json(MockMFiles.fixture("object-48193.json"));
        String base = "mfiles://V/objects/0/48193";
        assertEquals(List.of(base + "/files/11", base + "/files/12"), MFilesDocumentBuilder.ids("V", msa, true));
        assertEquals(List.of(base + "/versions/2/files/11", base + "/versions/2/files/12"), MFilesDocumentBuilder.ids("V", msa, false));
        JsonNode nda = json(MockMFiles.fixture("object-48192.json"));
        assertEquals(List.of("mfiles://V/objects/0/48192"), MFilesDocumentBuilder.ids("V", nda, true));
        JsonNode oneFileNoFlag = json("{\"ObjVer\":{\"ID\":1,\"Type\":0,\"Version\":1},\"Files\":[{\"ID\":3,\"Name\":\"a\"}]}");
        assertTrue(MFilesDocumentBuilder.isSingleFile(oneFileNoFlag, MFilesDocumentBuilder.files(oneFileNoFlag)));
    }

    @Test
    void fileNamesAndMimeTypes() {
        assertEquals("Contract.pdf", new MFilesDocumentBuilder.FileRef(1, "Contract", "pdf", -1).fileName());
        assertEquals("Contract.PDF", new MFilesDocumentBuilder.FileRef(1, "Contract.PDF", "pdf", -1).fileName());
        assertEquals("README", new MFilesDocumentBuilder.FileRef(1, "README", "", -1).fileName());
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                MFilesDocumentBuilder.mimeType("MSA.DOCX"));
        assertNull(MFilesDocumentBuilder.mimeType("noext"));
    }

    @Test
    void tombstoneCarriesTheObject() {
        RepositoryDocument t = MFilesDocumentBuilder.tombstone("mfiles://V/objects/0/7", "V", 0, 7);
        assertEquals(DocumentAction.DELETE, t.action());
        assertEquals(List.of("DELETED"), t.metadata().get("mfiles.status"));
        assertEquals(List.of("7"), t.metadata().get("mfiles.objId"));
        assertNull(t.contentStream());
    }

    @Test
    void propertyNameCollisionsGetTheIdSuffix() throws Exception {
        JsonNode ov = json("{\"ObjVer\":{\"ID\":1,\"Type\":0,\"Version\":1},\"Files\":[]}");
        List<JsonNode> props = List.of(
                json("{\"PropertyDef\":1050,\"TypedValue\":{\"DataType\":1,\"Value\":\"a\"}}"),
                json("{\"PropertyDef\":1051,\"TypedValue\":{\"DataType\":1,\"Value\":\"b\"}}"));
        Map<Integer, MFilesDocumentBuilder.PropertyDef> defs = Map.of(
                1050, new MFilesDocumentBuilder.PropertyDef(1050, "Project Code", 1),
                1051, new MFilesDocumentBuilder.PropertyDef(1051, "Project-Code", 1));
        RepositoryDocument d = MFilesDocumentBuilder.build("id", new MFilesDocumentBuilder.Context("V", null, true, true, "p_"),
                ov, props, defs, null, null, null, -1, MFilesSecurityMapper.publicAccess());
        assertEquals(List.of("a"), d.metadata().get("p_projectCode"));
        assertEquals(List.of("b"), d.metadata().get("p_projectCode_1051"));
        assertEquals("public", d.acl());
        assertEquals(List.of("0-1"), d.metadata().get("name"), "no title and no file: type-id");
    }
}
