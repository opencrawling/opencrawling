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
package org.opencrawling.luxir.client;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LuxirClientTest {

    private MockWebServer server;
    private LuxirClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        client = new LuxirClient(server.url("/").toString(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.shutdown();
    }

    @Test
    void testPingSuccess() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"docs\": 100}"));
        assertTrue(client.ping());
    }

    @Test
    void testCollectionExists() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"docs\": 10}"));
        assertTrue(client.collectionExists("opencrawling"));

        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(new MockResponse().setResponseCode(404));
        assertFalse(client.collectionExists("nonexistent"));
    }

    @Test
    void testCreateCollection() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(201).setBody("{\"ok\": true}"));

        Map<String, Object> schema = Map.of("fields", Map.of("embedding_v", Map.of("type", "vector", "dims", 384)));
        client.createCollection("opencrawling", schema);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/collections/_create", req.getPath());
        assertTrue(req.getBody().readUtf8().contains("opencrawling"));
    }

    @Test
    void testSetSchema() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\": true}"));

        Map<String, Object> schema = Map.of("fields", Map.of("embedding_v", Map.of("type", "vector", "dims", 384, "metric", "cosine")));
        client.setSchema("opencrawling", schema);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/collections/opencrawling/_schema", req.getPath());
        assertTrue(req.getBody().readUtf8().contains("cosine"));
    }

    @Test
    void testUpdateDocuments() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\": true}"));

        Map<String, Object> doc = Map.of("id", "doc-1", "title_t", "Test Document");
        client.updateDocuments("opencrawling", List.of(doc), true);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/collections/opencrawling/_update", req.getPath());
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("doc-1"));
        assertTrue(body.contains("commit"));
    }

    @Test
    void testUpdateDocumentsErrorResponseThrowsIOException() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"error\",\"errors\":[{\"error\":{\"message\":\"Field not found\"}}]}"));

        Map<String, Object> doc = Map.of("id", "doc-1", "title_t", "Test Document");
        assertThrows(java.io.IOException.class, () -> client.updateDocuments("opencrawling", List.of(doc), true));
    }

    @Test
    void testDeleteDocuments() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\": true}"));

        client.deleteDocuments("opencrawling", List.of("doc-1", "doc-2"), true);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/collections/opencrawling/_update", req.getPath());
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("delete_ids"));
        assertTrue(body.contains("doc-1"));
    }

    @Test
    void testDeleteDocumentsErrorResponseThrowsIOException() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"error\",\"errors\":[{\"error\":{\"message\":\"Field not found\"}}]}"));

        assertThrows(java.io.IOException.class, () -> client.deleteDocuments("opencrawling", List.of("doc-1"), true));
    }

    @Test
    void testCommit() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\": true}"));

        client.commit("opencrawling");

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/collections/opencrawling/_update", req.getPath());
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("commit"));
    }
}
