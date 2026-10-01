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
        when(client.createDocument(eq(REPO), anyMap(), any())).thenReturn(Fixtures.json("document-created.json").path("documentWsTO"));
        when(client.getVersions(REPO, "doc-0001")).thenReturn(Fixtures.list("versions.json"));
    }

    private DoxisOutputConnector connector(ConflictResolution conflict, DeleteMode deleteMode, ContentStrategy strategy, String uriPrefix) {
        DoxisOutputProperties props = new DoxisOutputProperties(null, "faststarter", "Supervisor", "pw", "admins", REPO,
                null, null, null, null, null, conflict, deleteMode, true,
                new Content(strategy, 1024, ContentStrategy.REFERENCE_ONLY, true),
                new Locator(null, uriPrefix, ""), 0, 10);
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
        verify(client).createDocument(eq(REPO), params.capture(), body.capture());
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
        when(client.getVersions(REPO, "doc-0001")).thenReturn(List.of(versions.get(0)));

        connector(ConflictResolution.NEW_VERSION, DeleteMode.LOGICAL, ContentStrategy.AUTO, "file:///mnt/doxis-store/")
                .send(document("file:///mnt/doxis-store/2026/10/master.mxf", Map.of("sizeInBytes", List.of("5497558138880")))).block();

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(client).createDocument(eq(REPO), params.capture(), isNull());
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
    }

    @Test
    void existingDocumentGetsNewVersion() throws Exception {
        Path file = Files.writeString(tmp.resolve("msa.pdf"), "%PDF-1.7 contract");
        when(client.searchDocumentIds(LOOKUP, false)).thenReturn(List.of("doc-0001"));

        connector().send(document(file.toUri().toString(), Map.of())).block();

        verify(client).addVersion(eq(REPO), eq("doc-0001"), anyMap(), notNull());
        verify(client, never()).createDocument(any(), any(), any());
        verify(client, never()).addPermissions(any(), any(), any());
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
        verify(client, never()).createDocument(any(), any(), any());
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
    void spiInstanceRefusesToSend() {
        DoxisOutputConnector spi = new DoxisOutputConnector();
        assertEquals("DoxisOutputConnector", spi.getName());
        assertThrows(IllegalStateException.class, () -> spi.send(RepositoryDocument.createTombstone("x", "y")).block());
    }
}
