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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.client.ContentBody;
import org.opencrawling.doxis.output.client.DoxisClient;
import org.opencrawling.doxis.output.config.DoxisOutputProperties;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ConflictResolution;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Content;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.DeleteMode;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Locator;
import org.opencrawling.doxis.output.content.ContentPlanner;
import org.opencrawling.doxis.output.content.PrefixLocatorResolver;
import org.opencrawling.doxis.output.schema.DoxisSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DoxisOutputConnectorTest {

    private static final String REPO = "D_TEXTER";
    private static final String TYPE_ID = "b89f3e49-ff11-467f-aba0-03740736646f";
    private static final String LOOKUP = "SELECT * FROM D_TEXTER WHERE OBJECTNUMBER = 'doc-1'";

    @TempDir
    Path tmp;

    private DoxisClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(DoxisClient.class);
        when(client.getRepository(REPO)).thenReturn(Fixtures.json("repository.json"));
        when(client.listDocumentTypes()).thenReturn(Fixtures.list("document-types.json"));
        when(client.listAttributeDefinitions()).thenReturn(Fixtures.list("attribute-definitions.json"));
        when(client.listMimeTypes()).thenReturn(Fixtures.list("mime-types.json"));
        when(client.listUsers()).thenReturn(Fixtures.list("users.json"));
        when(client.listGroups()).thenReturn(Fixtures.list("groups.json"));
        when(client.createDocument(eq(REPO), anyMap(), any(), any())).thenReturn(Fixtures.json("document-created.json").path("documentWsTO"));
        when(client.getVersions(REPO, "doc-0001")).thenReturn(versions("versions.json"));
    }

    private static List<JsonNode> versions(String fixture) {
        List<JsonNode> list = new java.util.ArrayList<>();
        Fixtures.json(fixture).path("versions").forEach(list::add);
        return list;
    }

    private DoxisOutputConnector connector(ConflictResolution conflict, DeleteMode deleteMode, ContentStrategy strategy, String uriPrefix) {
        DoxisOutputProperties props = new DoxisOutputProperties(null, "faststarter", "Supervisor", "pw", "admins", REPO,
                null, null, null, null, null, conflict, deleteMode, true,
                new Content(strategy, 1024, ContentStrategy.REFERENCE_ONLY, true, null),
                new Locator(null, uriPrefix, ""), null, null, null, 0, 10);
        DoxisSchema schema = new DoxisSchema(client);
        return new DoxisOutputConnector(client, props, schema,
                new ContentPlanner(props.content(), new PrefixLocatorResolver(props.locator())),
                new DoxisDocumentMapper(props, schema), new DoxisAclMapper(schema));
    }

    private DoxisOutputConnector connector() {
        return connector(ConflictResolution.NEW_VERSION, DeleteMode.LOGICAL, ContentStrategy.AUTO, null);
    }

    private RepositoryDocument document(String uri, Map<String, List<String>> metadata) {
        SecurityConfig security = new SecurityConfig(true, List.of(
                new PermissionRule("elena.weber", "user", "Elena Weber", "read"),
                new PermissionRule("contractors", "group", "Contractors", "deny")));
        return new RepositoryDocument("doc-1", uri, null, metadata, "elena.weber", security, Instant.parse("2026-09-30T08:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void newDocumentIsUploadedWithDescriptorsAclsAndVerified() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());

        connector().send(document(file.toUri().toString(), Map.of("title", List.of("MSA 2026")))).block();

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<ContentBody> body = ArgumentCaptor.forClass(ContentBody.class);
        verify(client).createDocument(eq(REPO), params.capture(), isNull(), body.capture());
        assertEquals(TYPE_ID, params.getValue().get("documentTypeUUID"));
        assertEquals("application/pdf", params.getValue().get("mimeTypeName"));
        assertEquals(17L, params.getValue().get("contentLength"));
        assertFalse(params.getValue().containsKey("predefinedLocator"));
        assertNotNull(body.getValue());
        assertTrue(body.getValue().reopenable());

        ArgumentCaptor<List<Map<String, Object>>> aces = ArgumentCaptor.forClass(List.class);
        verify(client).addPermissions(eq(REPO), eq("doc-0001"), aces.capture());
        assertEquals(2, aces.getValue().size());
        verify(client).getVersions(REPO, "doc-0001");
    }

    @Test
    @SuppressWarnings("unchecked")
    void hugeInPlaceFileIsRegisteredByLocatorWithoutTransferringBytes() throws Exception {
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());
        JsonNode versions = new ObjectMapper().readTree(Fixtures.text("versions.json").replace("\"length\":17", "\"length\":5497558138880"));
        when(client.getVersions(REPO, "doc-0001")).thenReturn(List.of(versions.path("versions").get(0)));

        connector(ConflictResolution.NEW_VERSION, DeleteMode.LOGICAL, ContentStrategy.AUTO, "file:///mnt/doxis-store/")
                .send(document("file:///mnt/doxis-store/2026/10/master.mxf", Map.of("sizeInBytes", List.of("5497558138880")))).block();

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(client).createDocument(eq(REPO), params.capture(), isNull(), isNull());
        assertEquals("2026/10/master.mxf", params.getValue().get("predefinedLocator"));
        assertEquals(5_497_558_138_880L, params.getValue().get("contentLength"));
        assertEquals("application/octet-stream", params.getValue().get("mimeTypeName"));
    }

    @Test
    void verificationFailsWhenDoxisReportsDifferentLength() throws Exception {
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                connector(ConflictResolution.NEW_VERSION, DeleteMode.LOGICAL, ContentStrategy.AUTO, "file:///mnt/doxis-store/")
                        .send(document("file:///mnt/doxis-store/2026/a.pdf", Map.of("sizeInBytes", List.of("999")))).block());

        assertTrue(e.getCause().getMessage().contains("does not match the source"));
        verify(client).deleteDocumentPhysically(REPO, "doc-0001");
    }

    @Test
    void verificationFailsWhenDoxisStoredNoContent() throws Exception {
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());
        when(client.getVersions(REPO, "doc-0001")).thenReturn(versions("versions-no-content.json"));

        RuntimeException e = assertThrows(RuntimeException.class, () ->
                connector(ConflictResolution.NEW_VERSION, DeleteMode.LOGICAL, ContentStrategy.AUTO, "file:///mnt/doxis-store/")
                        .send(document("file:///mnt/doxis-store/2026/a.pdf", Map.of("sizeInBytes", List.of("17")))).block());

        assertTrue(e.getCause().getMessage().contains("no content object"));
        verify(client).deleteDocumentPhysically(REPO, "doc-0001");
    }

    @Test
    void typeWithoutInstanceRightsKeepsTheDocument() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());
        doThrow(new org.opencrawling.doxis.output.client.DoxisApiException("Doxis Add permissions", 500, "SECU0050", "no instance rights"))
                .when(client).addPermissions(eq(REPO), eq("doc-0001"), anyList());

        assertDoesNotThrow(() -> connector().send(document(file.toUri().toString(), Map.of())).block());
        verify(client).getVersions(REPO, "doc-0001");
    }

    @Test
    void mimeTypeOutsideDocumentTypeAllowListIsRejectedBeforeWriting() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        DoxisOutputProperties props = new DoxisOutputProperties(null, "faststarter", "Supervisor", "pw", "admins", REPO,
                "DocumentTemplate", null, null, null, null, null, null, true, null, null, null, null, null, 0, 10);
        DoxisSchema schema = new DoxisSchema(client);
        DoxisOutputConnector templates = new DoxisOutputConnector(client, props, schema,
                new ContentPlanner(props.content(), new PrefixLocatorResolver(props.locator())),
                new DoxisDocumentMapper(props, schema), new DoxisAclMapper(schema));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> templates.send(document(file.toUri().toString(), Map.of())).block());

        assertTrue(e.getCause().getMessage().contains("is not allowed by Doxis document type 'DocumentTemplate'"));
        verify(client, never()).createDocument(any(), any(), any(), any());
    }

    private DoxisOutputConnector filingConnector(DoxisOutputProperties.Filing filing, DoxisOutputProperties.Security security) {
        DoxisOutputProperties props = new DoxisOutputProperties(null, "faststarter", "crawler", "pw", "admins", REPO,
                null, null, null, null, null, null, null, true, new Content(ContentStrategy.AUTO, 1024, ContentStrategy.REFERENCE_ONLY, true, null),
                null, filing, security, null, 0, 10);
        DoxisSchema schema = new DoxisSchema(client);
        return new DoxisOutputConnector(client, props, schema, new ContentPlanner(props.content(), new PrefixLocatorResolver(props.locator())),
                new DoxisDocumentMapper(props, schema), new DoxisAclMapper(schema));
    }

    private static DoxisOutputProperties.Filing filing(DoxisOutputProperties.FilingMode mode, String keyMetadata,
                                                       DoxisOutputProperties.FilingMethod method) {
        return new DoxisOutputProperties.Filing(mode, null, null, null, null, null, keyMetadata, "TX_SourceFolder", "ObjectNumber",
                "ObjectName", true, method, null);
    }

    private static DoxisOutputProperties.Security security(DoxisOutputProperties.SecurityMode mode, boolean strict, boolean removeStale) {
        return new DoxisOutputProperties.Security(mode, strict, removeStale, DoxisOutputProperties.RecordAclSync.CREATE_ONLY, true);
    }

    @Test
    void newDocumentIsFiledIntoTheRecordNamedInMetadataViaPrimaryParent() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());

        connector().send(document(file.toUri().toString(), Map.of("doxisRecordId", List.of("efile-4711")))).block();

        verify(client).createDocument(eq(REPO), anyMap(), isNull(), any());
        verify(client).setDocumentPrimaryParent(REPO, "doc-0001", "efile-4711");
    }

    @Test
    @SuppressWarnings("unchecked")
    void relationshipMethodFilesInTheCreateRequestIncludingFolderNode() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());
        when(client.getRecord(REPO, "efile-4711")).thenReturn(new ObjectMapper().readTree(
                "{\"uuid\":\"efile-4711\",\"contentRepositoryUUID\":\"repo-uuid\",\"instanceDate\":\"2026-09-01T10:00:00.000+02:00\"}"));

        filingConnector(filing(DoxisOutputProperties.FilingMode.METADATA, null, DoxisOutputProperties.FilingMethod.RELATIONSHIP),
                DoxisOutputProperties.Security.defaults())
                .send(document(file.toUri().toString(), Map.of("doxisRecordId", List.of("efile-4711"),
                        "doxisFolderNodeId", List.of("node-contracts")))).block();

        ArgumentCaptor<Map<String, Object>> relationship = ArgumentCaptor.forClass(Map.class);
        verify(client).createDocument(eq(REPO), anyMap(), relationship.capture(), any());
        assertEquals(Map.of("sourceObjectUUID", "efile-4711", "sourceFolderNodeUUID", "node-contracts",
                "sourceContentRepositoryUUID", "repo-uuid", "sourceObjectInstanceDate", "2026-09-01T10:00:00.000+02:00",
                "sourceObjectType", "RECORD"), relationship.getValue());
        verify(client, never()).setDocumentPrimaryParent(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sourceFolderModeAutoCreatesOneEfilePerFolderWithRecordPermissions() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("contracts"));
        Path first = Files.writeString(folder.resolve("a.pdf"), "%PDF a");
        Path second = Files.writeString(folder.resolve("b.pdf"), "%PDF b");
        String folderUri = first.toUri().toString().substring(0, first.toUri().toString().lastIndexOf('/'));
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of());
        when(client.listInformationObjectTypes()).thenReturn(List.of(new ObjectMapper().readTree(
                "{\"name\":\"TX_SourceFolder\",\"uuid\":\"rt-1\",\"schemaMetaType\":\"RECORD\"}")));
        when(client.searchRecordIds(anyString())).thenReturn(List.of());
        when(client.createRecord(eq(REPO), anyMap())).thenReturn(new ObjectMapper().readTree("{\"uuid\":\"rec-new\"}"));
        when(client.getLoggedInUser()).thenReturn(new ObjectMapper().readTree("{\"uuid\":\"user-connector\",\"name\":\"crawler\"}"));
        when(client.getVersions(eq(REPO), anyString())).thenReturn(versions("versions.json"));
        when(client.getVersions(REPO, "doc-0001")).thenReturn(List.of(new ObjectMapper().readTree(
                Fixtures.text("versions.json").replace("\"length\":17", "\"length\":6")).path("versions").get(0)));

        DoxisOutputConnector c = filingConnector(filing(DoxisOutputProperties.FilingMode.SOURCE_FOLDER, null,
                DoxisOutputProperties.FilingMethod.PRIMARY_PARENT), security(DoxisOutputProperties.SecurityMode.RECORD, false, false));
        c.send(document(first.toUri().toString(), Map.of())).block();
        c.send(document(second.toUri().toString(), Map.of())).block();

        ArgumentCaptor<Map<String, Object>> record = ArgumentCaptor.forClass(Map.class);
        verify(client, times(1)).createRecord(eq(REPO), record.capture());
        assertEquals("rt-1", record.getValue().get("compoundEntityTypeUUID"));
        assertTrue(record.getValue().toString().contains("contracts"));
        verify(client, times(1)).searchRecordIds(anyString());
        ArgumentCaptor<List<Map<String, Object>>> aces = ArgumentCaptor.forClass(List.class);
        verify(client).addRecordPermissions(eq(REPO), eq("rec-new"), aces.capture());
        assertTrue(aces.getValue().stream().filter(a -> !"user-connector".equals(a.get("organizationalElementId")))
                .allMatch(a -> a.get("permission").toString().contains("FOLDER")));
        assertTrue(aces.getValue().stream().anyMatch(a -> "user-connector".equals(a.get("organizationalElementId"))
                && "DELETE_FOLDER".equals(a.get("permission"))), "connector user keeps management rights on its e-files");
        verify(client, times(2)).setDocumentPrimaryParent(eq(REPO), anyString(), eq("rec-new"));
        verify(client, never()).addPermissions(any(), any(), any());
        assertNotNull(folderUri);
    }

    private RepositoryDocument document(String id, String uri, PermissionRule... rules) {
        return new RepositoryDocument(id, uri, null, Map.of("doxisRecordId", List.of("efile-4711")), "elena.weber",
                new SecurityConfig(true, List.of(rules)), Instant.parse("2026-09-30T08:00:00Z"));
    }

    private static final String RECORD_4711 = "{\"uuid\":\"efile-4711\",\"contentRepositoryUUID\":\"repo-uuid\",\"instanceDate\":\"2026-09-01T10:00:00.000+02:00\"}";

    /** An e-file version whose only node is the NODES_ONLY root, optionally with one child. */
    private static JsonNode recordWithNodes(String child) throws Exception {
        return new ObjectMapper().readTree("{\"uuid\":\"efile-4711\",\"versions\":[{\"currentVersion\":true,"
                + "\"rootFolderNodeReferenceUUID\":\"node-root\",\"folderNodes\":[{\"uuid\":\"node-root\",\"name\":\"TX_SourceFolder\","
                + "\"nodeMetaType\":\"NODES_ONLY\",\"childrenFolderNodes\":[" + (child == null ? "" : child) + "]}]}]}");
    }

    private DoxisOutputConnector relationshipConnector() {
        return filingConnector(filing(DoxisOutputProperties.FilingMode.METADATA, null, DoxisOutputProperties.FilingMethod.RELATIONSHIP),
                DoxisOutputProperties.Security.defaults());
    }

    @Test
    @SuppressWarnings("unchecked")
    void relationshipWithoutNodeIdCreatesTheDocumentsNodeOncePerEfile() throws Exception {
        Path a = Files.writeString(tmp.resolve("a.pdf"), "%PDF-1.7 contract");
        Path b = Files.writeString(tmp.resolve("b.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of());
        when(client.getRecord(REPO, "efile-4711")).thenReturn(new ObjectMapper().readTree(RECORD_4711));
        when(client.getRecordWithNodes(REPO, "efile-4711")).thenReturn(recordWithNodes(null));
        when(client.createFolderNode(eq(REPO), eq("efile-4711"), anyMap())).thenReturn("node-documents");

        DoxisOutputConnector c = relationshipConnector();
        c.send(document("doc-a", a.toUri().toString(), new PermissionRule("elena.weber", "user", "Elena", "read"))).block();
        c.send(document("doc-b", b.toUri().toString(), new PermissionRule("elena.weber", "user", "Elena", "read"))).block();

        ArgumentCaptor<Map<String, Object>> node = ArgumentCaptor.forClass(Map.class);
        verify(client, times(1)).createFolderNode(eq(REPO), eq("efile-4711"), node.capture());
        assertEquals(Map.of("nodeName", "Documents", "parentFolderNodeUUID", "node-root", "nodeMetaType", "STATIC",
                "allowedTargetInformationObjectTypeUUIDs", List.of(TYPE_ID), "defaultTargetInformationObjectTypeUUID", TYPE_ID), node.getValue());
        verify(client, times(1)).getRecordWithNodes(REPO, "efile-4711");
        ArgumentCaptor<Map<String, Object>> relationship = ArgumentCaptor.forClass(Map.class);
        verify(client, times(2)).createDocument(eq(REPO), anyMap(), relationship.capture(), any());
        assertTrue(relationship.getAllValues().stream().allMatch(r -> "node-documents".equals(r.get("sourceFolderNodeUUID"))));
        verify(client, never()).setDocumentPrimaryParent(any(), any(), any());
    }

    @Test
    void relationshipReusesAnExistingDocumentsNode() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of());
        when(client.getRecord(REPO, "efile-4711")).thenReturn(new ObjectMapper().readTree(RECORD_4711));
        when(client.getRecordWithNodes(REPO, "efile-4711")).thenReturn(recordWithNodes(
                "{\"uuid\":\"node-docs\",\"name\":\"documents\",\"nodeMetaType\":\"STATIC\",\"parentFolderNodeUUID\":\"node-root\"}"));

        relationshipConnector().send(document("doc-a", file.toUri().toString(), new PermissionRule("elena.weber", "user", "Elena", "read"))).block();

        verify(client, never()).createFolderNode(any(), any(), any());
        verify(client).createDocument(eq(REPO), anyMap(), argThat(r -> "node-docs".equals(r.get("sourceFolderNodeUUID"))), any());
    }

    @Test
    void relationshipRefusesANodeOfThatNameThatCannotHoldDocuments() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of());
        when(client.getRecord(REPO, "efile-4711")).thenReturn(new ObjectMapper().readTree(RECORD_4711));
        when(client.getRecordWithNodes(REPO, "efile-4711")).thenReturn(recordWithNodes(
                "{\"uuid\":\"node-docs\",\"name\":\"Documents\",\"nodeMetaType\":\"NODES_ONLY\"}"));

        RuntimeException e = assertThrows(RuntimeException.class, () -> relationshipConnector()
                .send(document("doc-a", file.toUri().toString(), new PermissionRule("elena.weber", "user", "Elena", "read"))).block());

        assertTrue(e.getCause().getMessage().contains("cannot hold documents"));
        verify(client, never()).createDocument(any(), anyMap(), any(), any());
        verify(client, never()).createFolderNode(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void additiveSyncReadsEfilePermissionsOnceAndAddsOnlyNewAces() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("contracts"));
        Path a = Files.writeString(folder.resolve("a.pdf"), "%PDF-1.7 contract");
        Path b = Files.writeString(folder.resolve("b.pdf"), "%PDF-1.7 contract");
        Path c = Files.writeString(folder.resolve("c.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of());
        when(client.searchRecordIds(anyString())).thenReturn(List.of("rec-9"));
        when(client.getRecordPermissions(REPO, "rec-9")).thenReturn(List.of(new ObjectMapper().readTree(
                "{\"organizationalElementId\":\"user-elena\",\"permissionName\":\"VIEW_FOLDER_CONTENTS\",\"authorizationVariant\":\"GRANT\"}")));

        DoxisOutputConnector connector = filingConnector(filing(DoxisOutputProperties.FilingMode.SOURCE_FOLDER, null,
                DoxisOutputProperties.FilingMethod.PRIMARY_PARENT), new DoxisOutputProperties.Security(DoxisOutputProperties.SecurityMode.RECORD,
                false, false, DoxisOutputProperties.RecordAclSync.ADDITIVE, true));
        PermissionRule elena = new PermissionRule("elena.weber", "user", "Elena", "read");
        PermissionRule legal = new PermissionRule("legal-counsel", "group", "Legal", "read");
        connector.send(document("doc-a", a.toUri().toString(), elena)).block();
        connector.send(document("doc-b", b.toUri().toString(), elena, legal)).block();
        connector.send(document("doc-c", c.toUri().toString(), elena, legal)).block();

        verify(client, times(1)).getRecordPermissions(REPO, "rec-9");
        ArgumentCaptor<List<Map<String, Object>>> added = ArgumentCaptor.forClass(List.class);
        verify(client, atLeastOnce()).addRecordPermissions(eq(REPO), eq("rec-9"), added.capture());
        List<Map<String, Object>> nonEmpty = added.getAllValues().stream().filter(l -> !l.isEmpty()).flatMap(List::stream).toList();
        assertEquals(List.of(Map.of("organizationalElementId", "group-legal", "permission", "VIEW_FOLDER_CONTENTS",
                "authorizationVariant", "GRANT")), nonEmpty, "only the new ACE is added, once, and nothing is removed");
        verify(client, never()).createRecord(any(), any());
        verify(client, times(3)).setDocumentPrimaryParent(eq(REPO), anyString(), eq("rec-9"));
    }

    private static DoxisOutputProperties.Security additive() {
        return new DoxisOutputProperties.Security(DoxisOutputProperties.SecurityMode.RECORD, false, false,
                DoxisOutputProperties.RecordAclSync.ADDITIVE, true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void additiveSyncOnReCrawlAddsANewIdentityToTheEfile() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of("doc-0001"));
        when(client.getRecordPermissions(REPO, "efile-4711")).thenReturn(List.of(new ObjectMapper().readTree(
                "{\"organizationalElementId\":\"user-elena\",\"permissionName\":\"VIEW_FOLDER_CONTENTS\",\"authorizationVariant\":\"GRANT\"}")));

        filingConnector(filing(DoxisOutputProperties.FilingMode.METADATA, null, DoxisOutputProperties.FilingMethod.PRIMARY_PARENT), additive())
                .send(document("doc-a", file.toUri().toString(), new PermissionRule("elena.weber", "user", "Elena", "read"),
                        new PermissionRule("legal-counsel", "group", "Legal", "read"))).block();

        verify(client).addRecordPermissions(REPO, "efile-4711", List.of(Map.of("organizationalElementId", "group-legal",
                "permission", "VIEW_FOLDER_CONTENTS", "authorizationVariant", "GRANT")));
        verify(client, never()).setDocumentPrimaryParent(any(), any(), any());
    }

    @Test
    void additiveSyncOnReCrawlNeverCreatesAnEfile() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("contracts"));
        Path file = Files.writeString(folder.resolve("a.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of("doc-0001"));
        when(client.searchRecordIds(anyString())).thenReturn(List.of());

        filingConnector(filing(DoxisOutputProperties.FilingMode.SOURCE_FOLDER, null, DoxisOutputProperties.FilingMethod.PRIMARY_PARENT), additive())
                .send(document("doc-a", file.toUri().toString(), new PermissionRule("legal-counsel", "group", "Legal", "read"))).block();

        verify(client).searchRecordIds(anyString());
        verify(client, never()).createRecord(any(), any());
        verify(client, never()).addRecordPermissions(any(), any(), any());
    }

    @Test
    void createOnlySyncLeavesTheEfileAloneOnReCrawl() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of("doc-0001"));

        filingConnector(filing(DoxisOutputProperties.FilingMode.METADATA, null, DoxisOutputProperties.FilingMethod.PRIMARY_PARENT),
                security(DoxisOutputProperties.SecurityMode.RECORD, false, false))
                .send(document("doc-a", file.toUri().toString(), new PermissionRule("legal-counsel", "group", "Legal", "read"))).block();

        verify(client, never()).getRecordPermissions(any(), any());
        verify(client, never()).addRecordPermissions(any(), any(), any());
    }

    @Test
    void createOnlySyncNeverTouchesAnExistingEfile() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(anyString(), eq(false))).thenReturn(List.of());
        when(client.searchRecordIds(anyString())).thenReturn(List.of("rec-9"));

        filingConnector(filing(DoxisOutputProperties.FilingMode.SOURCE_FOLDER, null, DoxisOutputProperties.FilingMethod.PRIMARY_PARENT),
                security(DoxisOutputProperties.SecurityMode.RECORD, false, false))
                .send(document("doc-a", file.toUri().toString(), new PermissionRule("legal-counsel", "group", "Legal", "read"))).block();

        verify(client, never()).getRecordPermissions(any(), any());
        verify(client, never()).addRecordPermissions(any(), any(), any());
    }

    @Test
    void keyMetadataModeReusesExistingEfile() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());
        when(client.searchRecordIds("SELECT * FROM D_TEXTER WHERE OBJECTNUMBER = 'CUST-4711'")).thenReturn(List.of("rec-9"));

        filingConnector(filing(DoxisOutputProperties.FilingMode.KEY_METADATA, "customerId", DoxisOutputProperties.FilingMethod.PRIMARY_PARENT),
                security(DoxisOutputProperties.SecurityMode.RECORD, false, false))
                .send(document(file.toUri().toString(), Map.of("customerId", List.of("CUST-4711")))).block();

        verify(client, never()).createRecord(any(), any());
        verify(client).setDocumentPrimaryParent(REPO, "doc-0001", "rec-9");
    }

    @Test
    void strictSecurityRollsBackWhenPermissionsCannotBeApplied() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());
        doThrow(new org.opencrawling.doxis.output.client.DoxisApiException("Doxis Add permissions", 500, "SECU0050", "no instance rights"))
                .when(client).addPermissions(eq(REPO), eq("doc-0001"), anyList());

        RuntimeException e = assertThrows(RuntimeException.class, () -> filingConnector(
                filing(DoxisOutputProperties.FilingMode.NONE, null, DoxisOutputProperties.FilingMethod.PRIMARY_PARENT),
                security(DoxisOutputProperties.SecurityMode.DOCUMENT, true, false))
                .send(document(file.toUri().toString(), Map.of())).block());

        assertTrue(e.getCause().getMessage().contains("security.strict"));
        verify(client).deleteDocumentPhysically(REPO, "doc-0001");
    }

    @Test
    void removeStaleDeletesOnlyManagedPermissionsNoLongerAtTheSource() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));
        ObjectMapper om = new ObjectMapper();
        when(client.getPermissions(REPO, "doc-0001")).thenReturn(List.of(
                om.readTree("{\"organizationalElementId\":\"user-elena\",\"permissionName\":\"VIEW_DOCUMENT_CONTENTS\",\"authorizationVariant\":\"GRANT\"}"),
                om.readTree("{\"organizationalElementId\":\"group-old\",\"permissionName\":\"VIEW_DOCUMENT_CONTENTS\",\"authorizationVariant\":\"GRANT\"}"),
                om.readTree("{\"organizationalElementId\":\"group-admins\",\"permissionName\":\"DELETE_DOCUMENT\",\"authorizationVariant\":\"GRANT\"}")));

        filingConnector(filing(DoxisOutputProperties.FilingMode.NONE, null, DoxisOutputProperties.FilingMethod.PRIMARY_PARENT),
                security(DoxisOutputProperties.SecurityMode.DOCUMENT, false, true))
                .send(document(file.toUri().toString(), Map.of())).block();

        verify(client).deleteDocumentPermission(REPO, "doc-0001", "VIEW_DOCUMENT_CONTENTS", "group-old", "GRANT");
        verify(client, never()).deleteDocumentPermission(eq(REPO), eq("doc-0001"), eq("DELETE_DOCUMENT"), any(), any());
        verify(client, never()).deleteDocumentPermission(eq(REPO), eq("doc-0001"), any(), eq("user-elena"), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reCrawlAddsOnlyMissingPermissions() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));
        when(client.getPermissions(REPO, "doc-0001")).thenReturn(List.of(new ObjectMapper().readTree(
                "{\"organizationalElementId\":\"user-elena\",\"permissionName\":\"VIEW_DOCUMENT_CONTENTS\",\"authorizationVariant\":\"GRANT\"}")));

        connector().send(document(file.toUri().toString(), Map.of())).block();

        ArgumentCaptor<List<Map<String, Object>>> aces = ArgumentCaptor.forClass(List.class);
        verify(client).addPermissions(eq(REPO), eq("doc-0001"), aces.capture());
        assertEquals(List.of(Map.of("organizationalElementId", "group-contractors", "permission", "VIEW_DOCUMENT_CONTENTS",
                "authorizationVariant", "DENY")), aces.getValue());
    }

    @Test
    void unchangedReCrawlSkipsNewVersionWhenChangeMarkerMatches() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));
        String marker = "2026-09-30T08:00:00Z|17";
        JsonNode version = new ObjectMapper().readTree("{\"versionNumber\":\"1\",\"currentVersion\":true,\"attributes\":["
                + "{\"attributeDefinitionUUID\":\"996fcb9f-d7cb-4e18-b8b9-1b02e7fd68cb\",\"values\":[\"" + marker + "\"]}]}");
        JsonNode amended = new ObjectMapper().readTree(Fixtures.text("versions.json").replace("\"length\":17", "\"length\":26"))
                .path("versions").get(0);
        // calls: first send -> change check; second send -> change check, then read-back verification
        when(client.getVersions(REPO, "doc-0001")).thenReturn(List.of(version), List.of(version), List.of(amended));
        DoxisOutputProperties props = new DoxisOutputProperties(null, "faststarter", "crawler", "pw", "admins", REPO,
                null, null, null, null, null, null, null, true,
                new Content(ContentStrategy.AUTO, 1024, ContentStrategy.REFERENCE_ONLY, true, "URL"), null, null, null, null, 0, 10);
        DoxisSchema schema = new DoxisSchema(client);
        DoxisOutputConnector withMarker = new DoxisOutputConnector(client, props, schema,
                new ContentPlanner(props.content(), new PrefixLocatorResolver(props.locator())),
                new DoxisDocumentMapper(props, schema), new DoxisAclMapper(schema));

        withMarker.send(document(file.toUri().toString(), Map.of())).block();
        verify(client, never()).addVersion(any(), any(), any(), any());

        Files.writeString(file, "%PDF-1.7 contract, amended");
        withMarker.send(document(file.toUri().toString(), Map.of())).block();
        verify(client).addVersion(eq(REPO), eq("doc-0001"), anyMap(), notNull());
    }

    private DoxisOutputConnector linkConnector(org.opencrawling.doxis.output.content.ContentLinkWriter writer) {
        DoxisOutputProperties props = new DoxisOutputProperties(null, "faststarter", "crawler", "pw", "admins", REPO,
                null, null, null, null, null, null, null, true,
                new Content(ContentStrategy.AUTO, 1024, ContentStrategy.REFERENCE_ONLY, true, null), null, null, null,
                new DoxisOutputProperties.ContentLink("/opt/doxis-client", null, 0, "file:///mnt/archive/", "\\\\fileserver\\archive\\",
                        org.opencrawling.doxis.output.content.ContentLinkWriter.LinkType.UNC, null, null), 0, 10);
        DoxisSchema schema = new DoxisSchema(client);
        return new DoxisOutputConnector(client, props, schema, DoxisOutputConnector.newContentPlanner(props, writer),
                new DoxisDocumentMapper(props, schema), new DoxisAclMapper(schema), writer);
    }

    @Test
    void largeInPlaceFileIsLinkedThroughTheContentLinkWriter() throws Exception {
        var writer = mock(org.opencrawling.doxis.output.content.ContentLinkWriter.class);
        when(writer.createLinkedDocument(any())).thenReturn("doc-0001");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of());
        JsonNode linked = new ObjectMapper().readTree(Fixtures.text("versions.json")
                .replace("\"length\":17", "\"length\":0")
                .replace("\"fullFilename\":\"msa.pdf\"", "\"fullFilename\":\"\\\\\\\\fileserver\\\\archive\\\\2026\\\\master.mxf\""));
        when(client.getVersions(REPO, "doc-0001")).thenReturn(List.of(linked.path("versions").get(0)));

        linkConnector(writer).send(document("file:///mnt/archive/2026/master.mxf", Map.of("sizeInBytes", List.of("5497558138880")))).block();

        ArgumentCaptor<org.opencrawling.doxis.output.content.ContentLinkWriter.Request> request =
                ArgumentCaptor.forClass(org.opencrawling.doxis.output.content.ContentLinkWriter.Request.class);
        verify(writer).createLinkedDocument(request.capture());
        assertEquals("D_TEXTER", request.getValue().repository());
        assertEquals(TYPE_ID, request.getValue().documentTypeId());
        assertEquals(org.opencrawling.doxis.output.content.ContentLinkWriter.LinkType.UNC, request.getValue().linkType());
        assertEquals("\\\\fileserver\\archive\\2026\\master.mxf", request.getValue().link());
        assertTrue(request.getValue().descriptors().stream().anyMatch(d -> d.values().contains("doc-1")));
        verify(client, never()).createDocument(any(), any(), any(), any());
        verify(client).addPermissions(eq(REPO), eq("doc-0001"), anyList());
        verify(client, never()).deleteDocumentPhysically(any(), any());
    }

    @Test
    void reCrawlOfLinkedDocumentUpdatesDescriptorsOnly() throws Exception {
        var writer = mock(org.opencrawling.doxis.output.content.ContentLinkWriter.class);
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));

        linkConnector(writer).send(document("file:///mnt/archive/2026/master.mxf", Map.of("sizeInBytes", List.of("5497558138880")))).block();

        verify(client).updateAttributes(eq(REPO), eq("doc-0001"), eq("1"), anyList());
        verify(client, never()).addVersion(any(), any(), any(), any());
        verify(writer, never()).createLinkedDocument(any());
    }

    @Test
    void existingDocumentGetsNewVersion() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));

        connector().send(document(file.toUri().toString(), Map.of())).block();

        verify(client).addVersion(eq(REPO), eq("doc-0001"), anyMap(), notNull());
        verify(client, never()).createDocument(any(), any(), any(), any());
    }

    @Test
    void updateMetadataPatchesCurrentVersionOnly() throws Exception {
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));

        connector(ConflictResolution.UPDATE_METADATA, DeleteMode.LOGICAL, ContentStrategy.AUTO, null)
                .send(document("https://example.org/msa.pdf", Map.of("title", List.of("New title")))).block();

        verify(client).updateAttributes(eq(REPO), eq("doc-0001"), eq("1"), anyList());
        verify(client, never()).addVersion(any(), any(), any(), any());
    }

    @Test
    void skipLeavesExistingDocumentUntouched() throws Exception {
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));

        connector(ConflictResolution.SKIP, DeleteMode.LOGICAL, ContentStrategy.AUTO, null)
                .send(document("https://example.org/msa.pdf", Map.of())).block();

        verify(client, never()).addVersion(any(), any(), any(), any());
        verify(client, never()).updateAttributes(any(), any(), any(), any());
        verify(client, never()).createDocument(any(), any(), any(), any());
    }

    @Test
    void tombstoneRemovesLogicallyByDefault() throws Exception {
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));

        connector().send(RepositoryDocument.createTombstone("doc-1", "file:///x")).block();

        verify(client).removeDocumentLogically(REPO, "doc-0001");
        verify(client, never()).deleteDocumentPhysically(any(), any());
    }

    @Test
    void tombstoneDeletesPhysicallyIncludingRemovedDocumentsWhenConfigured() throws Exception {
        when(client.searchDocumentIds(LOOKUP, true)).thenReturn(List.of("doc-0001", "doc-0002"));

        connector(ConflictResolution.NEW_VERSION, DeleteMode.PHYSICAL, ContentStrategy.AUTO, null)
                .send(RepositoryDocument.createTombstone("doc-1", "file:///x")).block();

        verify(client).deleteDocumentPhysically(REPO, "doc-0001");
        verify(client).deleteDocumentPhysically(REPO, "doc-0002");
    }

    @Test
    void lookupUsesRepositoryShortNameBecauseCqlRejectsDottedNames() throws Exception {
        when(client.getRepository(REPO)).thenReturn(new ObjectMapper().readTree(
                "{\"name\":\"de.ser.doxis4.sp.common.templates\",\"shortName\":\"DX4COMTEMPLATES\",\"uuid\":\"r-1\"}"));
        when(client.searchDocumentIds(anyString(), anyBoolean())).thenReturn(List.of("doc-0001"));

        connector().send(RepositoryDocument.createTombstone("doc-1", "file:///x")).block();

        verify(client).searchDocumentIds("SELECT * FROM DX4COMTEMPLATES WHERE OBJECTNUMBER = 'doc-1'", false);
    }

    @Test
    void spiInstanceRefusesToSend() {
        DoxisOutputConnector spi = new DoxisOutputConnector();
        assertEquals("DoxisOutputConnector", spi.getName());
        assertThrows(IllegalStateException.class, () -> spi.send(RepositoryDocument.createTombstone("x", "y")).block());
    }
}
