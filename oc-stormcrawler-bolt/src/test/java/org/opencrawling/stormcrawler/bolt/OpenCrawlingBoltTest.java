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
package org.opencrawling.stormcrawler.bolt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.apache.stormcrawler.Constants;
import org.apache.stormcrawler.Metadata;
import org.apache.stormcrawler.indexing.AbstractIndexerBolt;
import org.apache.stormcrawler.persistence.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.stormcrawler.bolt.dispatcher.InMemoryPayloadDispatcher;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenCrawlingBoltTest {

    private OpenCrawlingBolt bolt;
    private InMemoryPayloadDispatcher dispatcher;
    private OutputCollector collector;
    private TopologyContext context;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        dispatcher = new InMemoryPayloadDispatcher();
        bolt = new OpenCrawlingBolt(dispatcher);
        collector = mock(OutputCollector.class);
        context = mock(TopologyContext.class);
        objectMapper = new ObjectMapper();

        bolt.prepare(Map.of(
                OpenCrawlingBolt.CONF_INSTANCE_ID, "test-storm-cluster",
                OpenCrawlingBolt.CONF_EMIT_DELETIONS, "true",
                AbstractIndexerBolt.metadata2fieldParamName, List.of("parse.title=title"),
                AbstractIndexerBolt.canonicalMetadataParamName, "canonical"
        ), context, collector);
    }

    @Test
    @DisplayName("Content Tuple Processing: Emits OIS UPSERT Payload with Content Hash and Mapped Metadata")
    void testExecuteContentTupleEmitsUpsert() throws Exception {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn("default");
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/security");
        when(tuple.contains("content")).thenReturn(true);
        byte[] contentBytes = "<h1>Security Policy</h1><p>Zero Trust enterprise guidelines.</p>".getBytes(StandardCharsets.UTF_8);
        when(tuple.getValueByField("content")).thenReturn(contentBytes);
        when(tuple.contains("text")).thenReturn(true);
        when(tuple.getStringByField("text")).thenReturn("Security Policy Zero Trust enterprise guidelines.");
        when(tuple.contains("metadata")).thenReturn(true);

        Metadata metadata = new Metadata();
        metadata.setValue("parse.Content-Type", "text/html");
        metadata.setValue("parse.title", "Enterprise Security Policy");
        metadata.setValue("canonical", "/docs/security-policy");
        metadata.setValue("http.status", "200");
        when(tuple.getValueByField("metadata")).thenReturn(metadata);

        bolt.execute(tuple);

        verify(collector, times(1)).emit(Constants.StatusStreamName, tuple,
                new Values("https://example.com/docs/security", metadata, Status.FETCHED));
        verify(collector, times(1)).ack(tuple);
        verify(collector, never()).fail(any());

        assertEquals(1, dispatcher.getDispatchedItems().size());
        InMemoryPayloadDispatcher.DispatchedItem item = dispatcher.getDispatchedItems().get(0);
        assertEquals("https://example.com/docs/security", item.documentId());
        assertEquals("UPSERT", item.action());

        JsonNode json = objectMapper.readTree(item.jsonPayload());
        assertEquals("https://example.com/docs/security", json.path("id").asText());
        assertEquals("UPSERT", json.path("action").asText());
        assertEquals("stormcrawler", json.path("source").path("type").asText());
        assertEquals("test-storm-cluster", json.path("source").path("instance").asText());
        assertEquals("text/html", json.path("content").path("mimeType").asText());
        assertEquals("Security Policy Zero Trust enterprise guidelines.", json.path("content").path("text").asText());
        assertNotNull(json.path("metadata").path("contentHash").asText());
        assertEquals("Enterprise Security Policy", json.path("metadata").path("title").asText());
        assertTrue(json.path("metadata").path("http.status").isMissingNode(), "unmapped key in payload");
        assertEquals("https://example.com/docs/security-policy", json.path("metadata").path("canonical.url").asText());
        assertFalse(json.path("security").path("inheritanceEnabled").asBoolean());
    }

    @Test
    @DisplayName("Noindex Page: Not Dispatched, Still Reported as FETCHED")
    void testNoindexPageIsNotDispatchedButReportedFetched() {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn("default");
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/private");
        when(tuple.contains("metadata")).thenReturn(true);

        Metadata metadata = new Metadata();
        metadata.setValue("robots.noIndex", "true");
        when(tuple.getValueByField("metadata")).thenReturn(metadata);

        bolt.execute(tuple);

        assertEquals(0, dispatcher.getDispatchedItems().size());
        verify(collector, times(1)).emit(Constants.StatusStreamName, tuple,
                new Values("https://example.com/docs/private", metadata, Status.FETCHED));
        verify(collector, times(1)).ack(tuple);
    }

    @Test
    @DisplayName("Status Stream 404: Emits OIS DELETE Tombstone Payload")
    void testExecuteStatusTupleWithHttp404EmitsDeleteTombstone() throws Exception {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn(Constants.StatusStreamName);
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/obsolete");
        when(tuple.contains("status")).thenReturn(true);
        when(tuple.getValueByField("status")).thenReturn(Status.FETCH_ERROR);
        when(tuple.contains("metadata")).thenReturn(true);

        Metadata metadata = new Metadata();
        metadata.setValue("http.status", "404");
        when(tuple.getValueByField("metadata")).thenReturn(metadata);

        bolt.execute(tuple);

        verify(collector, times(1)).ack(tuple);
        verify(collector, never()).fail(any());

        assertEquals(1, dispatcher.getDispatchedItems().size());
        InMemoryPayloadDispatcher.DispatchedItem item = dispatcher.getDispatchedItems().get(0);
        assertEquals("https://example.com/docs/obsolete", item.documentId());
        assertEquals("DELETE", item.action());

        JsonNode json = objectMapper.readTree(item.jsonPayload());
        assertEquals("https://example.com/docs/obsolete", json.path("id").asText());
        assertEquals("DELETE", json.path("action").asText());
        assertEquals("stormcrawler", json.path("source").path("type").asText());
        assertEquals("FETCH_ERROR", json.path("metadata").path("stormcrawler.status").asText());
        assertEquals("404", json.path("metadata").path("http.status").asText());
        assertNotNull(json.path("metadata").path("deletedAt").asText());
        assertTrue(json.path("content").isMissingNode());
    }

    @Test
    @DisplayName("Status Stream 410 Gone: Emits OIS DELETE Tombstone Payload")
    void testExecuteStatusTupleWithHttp410EmitsDeleteTombstone() throws Exception {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn(Constants.StatusStreamName);
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/gone");
        when(tuple.contains("status")).thenReturn(true);
        when(tuple.getValueByField("status")).thenReturn(Status.ERROR);
        when(tuple.contains("metadata")).thenReturn(true);

        Metadata metadata = new Metadata();
        metadata.setValue("http.status", "410");
        when(tuple.getValueByField("metadata")).thenReturn(metadata);

        bolt.execute(tuple);

        verify(collector, times(1)).ack(tuple);
        assertEquals(1, dispatcher.getDispatchedItems().size());
        assertEquals("DELETE", dispatcher.getDispatchedItems().get(0).action());
    }

    @Test
    @DisplayName("Dedicated Deletion Stream: Emits OIS DELETE Tombstone Payload")
    void testExecuteDedicatedDeletionStreamEmitsDeleteTombstone() throws Exception {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn(Constants.DELETION_STREAM_NAME);
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/purged");
        when(tuple.contains("status")).thenReturn(false);
        when(tuple.contains("metadata")).thenReturn(false);

        bolt.execute(tuple);

        verify(collector, times(1)).ack(tuple);
        assertEquals(1, dispatcher.getDispatchedItems().size());
        InMemoryPayloadDispatcher.DispatchedItem item = dispatcher.getDispatchedItems().get(0);
        assertEquals("https://example.com/docs/purged", item.documentId());
        assertEquals("DELETE", item.action());
    }

    @Test
    @DisplayName("Status Stream Non-Deletion (FETCHED): Acknowledged Without Dispatch")
    void testExecuteStatusTupleFetchedDoesNotEmitTombstone() {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn(Constants.StatusStreamName);
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/active");
        when(tuple.contains("status")).thenReturn(true);
        when(tuple.getValueByField("status")).thenReturn(Status.FETCHED);
        when(tuple.contains("metadata")).thenReturn(true);

        Metadata metadata = new Metadata();
        metadata.setValue("http.status", "200");
        when(tuple.getValueByField("metadata")).thenReturn(metadata);

        bolt.execute(tuple);

        verify(collector, times(1)).ack(tuple);
        assertEquals(0, dispatcher.getDispatchedItems().size());
    }

    @Test
    @DisplayName("Emit Deletions Disabled: Status Deletions are Ignored")
    void testExecuteWhenEmitDeletionsDisabled() {
        bolt.prepare(Map.of(
                OpenCrawlingBolt.CONF_EMIT_DELETIONS, "false"
        ), context, collector);

        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn(Constants.StatusStreamName);
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/404");
        when(tuple.contains("status")).thenReturn(true);
        when(tuple.getValueByField("status")).thenReturn(Status.FETCH_ERROR);
        when(tuple.contains("metadata")).thenReturn(true);

        Metadata metadata = new Metadata();
        metadata.setValue("http.status", "404");
        when(tuple.getValueByField("metadata")).thenReturn(metadata);

        bolt.execute(tuple);

        verify(collector, times(1)).ack(tuple);
        assertEquals(0, dispatcher.getDispatchedItems().size());
    }

    @Test
    @DisplayName("Compute Hash: Produces Consistent SHA-256 and MD5 Digests")
    void testComputeHash() {
        byte[] input = "hello opencrawling stormcrawler".getBytes(StandardCharsets.UTF_8);
        String sha256 = OpenCrawlingBolt.computeHash(input, "SHA-256");
        String md5 = OpenCrawlingBolt.computeHash(input, "MD5");

        assertNotNull(sha256);
        assertEquals(64, sha256.length());
        assertNotNull(md5);
        assertEquals(32, md5.length());
    }

    @Test
    @DisplayName("Dispatcher Failure: Triggers OutputCollector.fail")
    void testDispatcherFailureFailsTuple() throws Exception {
        InMemoryPayloadDispatcher failingDispatcher = spy(new InMemoryPayloadDispatcher());
        doThrow(new RuntimeException("Simulated network outage")).when(failingDispatcher).dispatch(any(), any(), any());

        OpenCrawlingBolt failingBolt = new OpenCrawlingBolt(failingDispatcher);
        failingBolt.prepare(Map.of(), context, collector);

        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn("default");
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/fail");
        when(tuple.contains("content")).thenReturn(true);
        when(tuple.getValueByField("content")).thenReturn("test content".getBytes(StandardCharsets.UTF_8));
        when(tuple.contains("text")).thenReturn(true);
        when(tuple.getStringByField("text")).thenReturn("test content");
        when(tuple.contains("metadata")).thenReturn(true);
        when(tuple.getValueByField("metadata")).thenReturn(new Metadata());

        failingBolt.execute(tuple);

        verify(failingDispatcher, times(1)).dispatch(any(), any(), any());
        verify(collector, times(1)).fail(tuple);
        verify(collector, never()).ack(tuple);
        verify(collector, never()).emit(anyString(), any(Tuple.class), anyList());
    }

    @Test
    @DisplayName("Content Tuple Without Metadata: Dispatched with the Fetched URL as Canonical")
    void testContentTupleWithoutMetadataIsDispatched() throws Exception {
        Tuple tuple = mock(Tuple.class);
        when(tuple.getSourceStreamId()).thenReturn("default");
        when(tuple.getStringByField("url")).thenReturn("https://example.com/docs/bare");
        when(tuple.contains("text")).thenReturn(true);
        when(tuple.getStringByField("text")).thenReturn("bare page");
        when(tuple.contains("metadata")).thenReturn(false);

        bolt.execute(tuple);

        verify(collector, never()).fail(any());
        verify(collector, times(1)).ack(tuple);
        assertEquals(1, dispatcher.getDispatchedItems().size());
        JsonNode json = objectMapper.readTree(dispatcher.getDispatchedItems().get(0).jsonPayload());
        assertEquals("https://example.com/docs/bare", json.path("id").asText());
        assertEquals("https://example.com/docs/bare", json.path("metadata").path("canonical.url").asText());
    }
}
