/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.push.grpc;

import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.push.v1.OisDocumentPayload;
import org.opencrawling.push.v1.PushOptions;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Translates Protobuf OIS documents and security ACL configurations into OpenCrawling
 * native models, automatically offloading payloads exceeding threshold to ClaimCheckStore.
 */
public final class OisDocumentTranslator {

    private OisDocumentTranslator() {}

    /**
     * Translates a protobuf {@link OisDocumentPayload} into an OpenCrawling {@link RepositoryDocument}.
     */
    public static RepositoryDocument translate(
            OisDocumentPayload payload,
            PushOptions options,
            ClaimCheckStore claimCheckStore,
            int inlineBinaryThresholdKb) throws Exception {

        String id = payload.getId();
        DocumentAction action = payload.getAction() == org.opencrawling.push.v1.DocumentAction.DELETE
                ? DocumentAction.DELETE
                : DocumentAction.UPSERT;

        if (action == DocumentAction.DELETE) {
            return RepositoryDocument.createTombstone(id, id);
        }

        Instant lastModified = payload.getTimestamp() > 0
                ? Instant.ofEpochMilli(payload.getTimestamp())
                : Instant.now();

        String mimeType = payload.getMimeType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = "text/plain";
        }

        String uri = id;
        InputStream contentStream = null;
        int thresholdBytes = inlineBinaryThresholdKb > 0 ? inlineBinaryThresholdKb * 1024 : 512 * 1024;

        switch (payload.getContentPayloadCase()) {
            case RAW_BYTES -> {
                byte[] bytes = payload.getRawBytes().toByteArray();
                if (claimCheckStore != null && bytes.length > thresholdBytes) {
                    String ext = extensionFor(mimeType);
                    String filename = sanitizeId(id) + "_" + UUID.randomUUID() + ext;
                    URI claimUri = claimCheckStore.put(filename, new ByteArrayInputStream(bytes), bytes.length, mimeType);
                    uri = claimUri.toString();
                    contentStream = null;
                } else {
                    contentStream = new ByteArrayInputStream(bytes);
                }
            }
            case CLAIM_CHECK_URI -> {
                uri = payload.getClaimCheckUri();
                contentStream = null;
            }
            case TEXT_CONTENT -> {
                byte[] bytes = payload.getTextContent().getBytes(StandardCharsets.UTF_8);
                if (claimCheckStore != null && bytes.length > thresholdBytes) {
                    String filename = sanitizeId(id) + "_" + UUID.randomUUID() + ".txt";
                    URI claimUri = claimCheckStore.put(filename, new ByteArrayInputStream(bytes), bytes.length, "text/plain");
                    uri = claimUri.toString();
                    contentStream = null;
                } else {
                    contentStream = new ByteArrayInputStream(bytes);
                }
            }
            case CONTENTPAYLOAD_NOT_SET -> {
                contentStream = null;
            }
        }

        // Metadata mapping (expands JSON scalar arrays such as ["alpha","beta"] into multi-valued lists, #407)
        Map<String, List<String>> metadata = new HashMap<>();
        if (payload.getMetadataCount() > 0) {
            payload.getMetadataMap().forEach((k, v) -> {
                if (k != null && v != null) {
                    metadata.put(k, parseMetadataValues(v));
                }
            });
        }
        metadata.put("mimeType", List.of(mimeType));

        if (payload.getChecksumSha256() != null && !payload.getChecksumSha256().isBlank()) {
            metadata.put("checksum_sha256", List.of(payload.getChecksumSha256()));
        }

        if (options != null) {
            if (options.getBypassTikaExtraction()) {
                metadata.put("bypass_tika", List.of("true"));
            }
            if (options.getRoutingKey() != null && !options.getRoutingKey().isBlank()) {
                metadata.put("routing_key", List.of(options.getRoutingKey()));
            }
        }

        // Security ACL mapping
        SecurityConfig securityConfig = translateSecurity(payload.getSecurity());
        String acl = extractAcl(payload.getSecurity());

        return new RepositoryDocument(id, uri, contentStream, metadata, acl, securityConfig, lastModified, action);
    }

    /**
     * Translates protobuf SecurityConfig into OpenCrawling SecurityConfig.
     */
    public static SecurityConfig translateSecurity(org.opencrawling.push.v1.SecurityConfig protoSecurity) {
        if (protoSecurity == null) {
            return SecurityConfig.createPublic();
        }

        List<PermissionRule> rules = new ArrayList<>();

        for (String sid : protoSecurity.getAllowSidsList()) {
            if (sid != null && !sid.isBlank()) {
                rules.add(new PermissionRule(sid, "GROUP", sid, "read"));
            }
        }

        for (String sid : protoSecurity.getDenySidsList()) {
            if (sid != null && !sid.isBlank()) {
                rules.add(new PermissionRule(sid, "GROUP", sid, "deny"));
            }
        }

        if (protoSecurity.getOwner() != null && !protoSecurity.getOwner().isBlank()) {
            rules.add(new PermissionRule(protoSecurity.getOwner(), "USER", protoSecurity.getOwner(), "write"));
        }

        if (protoSecurity.getTenantId() != null && !protoSecurity.getTenantId().isBlank()) {
            rules.add(new PermissionRule(protoSecurity.getTenantId(), "TENANT", protoSecurity.getTenantId(), "read"));
        }

        if (rules.isEmpty()) {
            return SecurityConfig.createPublic();
        }

        return new SecurityConfig(false, rules);
    }

    /**
     * Extracts legacy flat ACL string for vector database filters.
     */
    public static String extractAcl(org.opencrawling.push.v1.SecurityConfig protoSecurity) {
        if (protoSecurity == null) {
            return "public";
        }
        if (protoSecurity.getAllowSidsCount() > 0) {
            return protoSecurity.getAllowSids(0);
        }
        if (protoSecurity.getOwner() != null && !protoSecurity.getOwner().isBlank()) {
            return protoSecurity.getOwner();
        }
        return "public";
    }

    /**
     * Expands a language-neutral JSON array of scalars (e.g. {@code ["alpha","beta"]}) carried in the
     * protobuf {@code map<string, string>} into a multi-valued {@link List}, preserving literal strings
     * that are not valid JSON scalar arrays (#407).
     */
    static List<String> parseMetadataValues(String raw) {
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == '[' && trimmed.charAt(trimmed.length() - 1) == ']') {
            List<String> parsed = tryParseJsonScalarArray(trimmed);
            if (parsed != null) {
                return parsed;
            }
        }
        return List.of(raw);
    }

    /**
     * Parses a JSON array containing only scalar values (strings, numbers, booleans, nulls) without
     * external dependencies; returns {@code null} if {@code text} is not a valid JSON scalar array.
     */
    private static List<String> tryParseJsonScalarArray(String text) {
        int len = text.length();
        int i = 1; // after '['
        List<String> values = new ArrayList<>();
        i = skipWhitespace(text, i);
        if (i < len && text.charAt(i) == ']') {
            return i == len - 1 ? List.of() : null;
        }
        while (i < len) {
            i = skipWhitespace(text, i);
            if (i >= len) {
                return null;
            }
            char c = text.charAt(i);
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < len) {
                    char ch = text.charAt(i++);
                    if (ch == '"') {
                        closed = true;
                        break;
                    }
                    if (ch == '\\') {
                        if (i >= len) {
                            return null;
                        }
                        char esc = text.charAt(i++);
                        switch (esc) {
                            case '"', '\\', '/' -> sb.append(esc);
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                if (i + 4 > len) {
                                    return null;
                                }
                                try {
                                    sb.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                                    i += 4;
                                } catch (NumberFormatException e) {
                                    return null;
                                }
                            }
                            default -> {
                                return null;
                            }
                        }
                    } else if (ch < 0x20) {
                        return null;
                    } else {
                        sb.append(ch);
                    }
                }
                if (!closed) {
                    return null;
                }
                values.add(sb.toString());
            } else if (text.startsWith("true", i)) {
                values.add("true");
                i += 4;
            } else if (text.startsWith("false", i)) {
                values.add("false");
                i += 5;
            } else if (text.startsWith("null", i)) {
                i += 4;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int start = i;
                if (c == '-') {
                    i++;
                }
                int digitsStart = i;
                while (i < len && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                    i++;
                }
                if (i == digitsStart) {
                    return null;
                }
                if (i < len && text.charAt(i) == '.') {
                    i++;
                    int fracStart = i;
                    while (i < len && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                        i++;
                    }
                    if (i == fracStart) {
                        return null;
                    }
                }
                if (i < len && (text.charAt(i) == 'e' || text.charAt(i) == 'E')) {
                    i++;
                    if (i < len && (text.charAt(i) == '+' || text.charAt(i) == '-')) {
                        i++;
                    }
                    int expStart = i;
                    while (i < len && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
                        i++;
                    }
                    if (i == expStart) {
                        return null;
                    }
                }
                values.add(text.substring(start, i));
            } else {
                // Nested object, nested array, or invalid token -> not a scalar array
                return null;
            }

            i = skipWhitespace(text, i);
            if (i >= len) {
                return null;
            }
            char sep = text.charAt(i++);
            if (sep == ']') {
                return i == len ? List.copyOf(values) : null;
            }
            if (sep != ',') {
                return null;
            }
        }
        return null;
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private static String sanitizeId(String id) {
        if (id == null) return "doc";
        return id.replaceAll("[^a-zA-Z0-9.-]", "_");
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return ".bin";
        return switch (mimeType.toLowerCase()) {
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/html" -> ".html";
            case "application/json" -> ".json";
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            default -> ".bin";
        };
    }
}
