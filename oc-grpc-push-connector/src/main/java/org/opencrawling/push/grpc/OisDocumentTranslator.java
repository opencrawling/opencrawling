/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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

        // Metadata mapping
        Map<String, List<String>> metadata = new HashMap<>();
        if (payload.getMetadataCount() > 0) {
            payload.getMetadataMap().forEach((k, v) -> {
                if (k != null && v != null) {
                    metadata.put(k, List.of(v));
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
