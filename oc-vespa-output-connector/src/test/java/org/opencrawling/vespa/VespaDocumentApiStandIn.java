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
package org.opencrawling.vespa;

import com.sun.net.httpserver.HttpServer;
import org.opencrawling.vespa.config.VespaOutputProperties;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for Vespa's document API delete by selection, holding documents by full id
 * ({@code id:<namespace>:<type>::<local>}). It evaluates the two selections {@link VespaChunkRemover} sends the way
 * Vespa does: exact, case-sensitive string comparison, and {@code == null} for a field a document does not have.
 */
final class VespaDocumentApiStandIn implements AutoCloseable {

    private static final Pattern BY_DOCUMENT_ID = Pattern.compile("^(\\w+)\\.document_id == \"((?:[^\"\\\\]|\\\\.)*)\"$");
    private static final Pattern LEGACY_BY_URI =
            Pattern.compile("^(\\w+)\\.uri == \"((?:[^\"\\\\]|\\\\.)*)\" and \\w+\\.document_id == null$");

    /** Stored documents: full id → fields. */
    final Map<String, Map<String, String>> documents = new ConcurrentHashMap<>();
    /** The selections deleted, as "namespace/type: selection". */
    final List<String> deletions = new CopyOnWriteArrayList<>();
    /** The document types the application has; the others answer like Vespa does for an undeployed type. */
    volatile java.util.Set<String> deployedTypes = java.util.Set.of("opencrawling_chunk", "opencrawling_chunk_384",
            "opencrawling_chunk_768", "opencrawling_chunk_1024");
    /** The timeChunk each call asked for. */
    final List<String> timeChunks = new CopyOnWriteArrayList<>();
    /** At most this many documents are removed per call; the rest is left for a continuation. */
    volatile int perCall = Integer.MAX_VALUE;
    private final HttpServer server;

    VespaDocumentApiStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/document/v1/", exchange -> {
            String[] path = exchange.getRequestURI().getPath().split("/");
            String namespace = path[3];
            String type = path[4];
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            String answer;
            int status = 200;
            timeChunks.add(String.valueOf(query.get("timeChunk")));
            if ("DELETE".equals(exchange.getRequestMethod()) && "opencrawling_content".equals(query.get("cluster"))
                    && !deployedTypes.contains(type)) {
                status = 400;
                answer = "{\"message\":\"There is no document type '" + type + "' in cluster 'opencrawling_content', only "
                        + deployedTypes + "\"}";
            } else if (!"DELETE".equals(exchange.getRequestMethod()) || !"opencrawling_content".equals(query.get("cluster"))) {
                status = 400;
                answer = "{\"message\":\"Your Vespa deployment has no content cluster '" + query.get("cluster") + "'\"}";
            } else {
                String selection = query.get("selection");
                deletions.add(namespace + "/" + type + ": " + selection);
                List<String> matches = new ArrayList<>();
                documents.forEach((id, fields) -> {
                    if (id.startsWith("id:" + namespace + ":" + type + "::") && matches(selection, fields)) {
                        matches.add(id);
                    }
                });
                List<String> removed = matches.subList(0, Math.min(perCall, matches.size()));
                removed.forEach(documents::remove);
                answer = "{\"documentCount\":" + removed.size()
                        + (matches.size() > removed.size() ? ",\"continuation\":\"more\"" : "") + "}";
            }
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    void store(String namespace, String type, String local, String documentId, String uri) {
        Map<String, String> fields = new HashMap<>();
        if (documentId != null) {
            fields.put("document_id", documentId);
        }
        fields.put("uri", uri);
        documents.put("id:" + namespace + ":" + type + "::" + local, fields);
    }

    VespaOutputProperties properties() {
        return VespaOutputProperties.builder()
                .endpoint("http://127.0.0.1:" + server.getAddress().getPort())
                .timeoutSeconds(5)
                .build();
    }

    private static boolean matches(String selection, Map<String, String> fields) {
        Matcher byId = BY_DOCUMENT_ID.matcher(selection);
        if (byId.matches()) {
            return unescape(byId.group(2)).equals(fields.get("document_id"));
        }
        Matcher legacy = LEGACY_BY_URI.matcher(selection);
        if (legacy.matches()) {
            return unescape(legacy.group(2)).equals(fields.get("uri")) && fields.get("document_id") == null;
        }
        throw new IllegalArgumentException("unexpected selection: " + selection);
    }

    private static String unescape(String literal) {
        return literal.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> query = new HashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            query.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return query;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
