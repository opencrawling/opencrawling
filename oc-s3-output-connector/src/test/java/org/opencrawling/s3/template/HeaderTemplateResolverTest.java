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
package org.opencrawling.s3.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;

class HeaderTemplateResolverTest {

    @Test
    @DisplayName("Static headers without placeholders return unchanged")
    void testStaticHeaderPassThrough() {
        assertEquals("finance-tenant", HeaderTemplateResolver.resolve("finance-tenant", null));
        assertEquals("AES256", HeaderTemplateResolver.resolve("AES256", null));
        assertNull(HeaderTemplateResolver.resolve(null, null));
        assertEquals("", HeaderTemplateResolver.resolve("", null));
    }

    @Test
    @DisplayName("Resolve dynamic metadata tokens from document")
    void testResolveMetadataTokens() {
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("department", List.of("Engineering"));
        metadata.put("category", List.of("Confidential"));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-123",
                "file:///data/specs.pdf",
                new ByteArrayInputStream(new byte[0]),
                metadata,
                "acl-1",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        String template = "Department=${metadata.department},Category=${metadata.category}";
        assertEquals("Department=Engineering,Category=Confidential", HeaderTemplateResolver.resolve(template, doc));
    }

    @Test
    @DisplayName("Resolve multiple values for single metadata key with comma separator")
    void testResolveMultiValueMetadata() {
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("tags", List.of("cloud", "migration", "s3"));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-123",
                "file:///data/specs.pdf",
                null,
                metadata,
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        assertEquals("cloud,migration,s3", HeaderTemplateResolver.resolve("${metadata.tags}", doc));
    }

    @Test
    @DisplayName("Fallback value is applied when metadata key is missing")
    void testResolveWithFallback() {
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("department", List.of("Finance"));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-123",
                "file:///data/specs.pdf",
                null,
                metadata,
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        assertEquals("Finance", HeaderTemplateResolver.resolve("${metadata.department | Unknown}", doc));
        assertEquals("Unclassified", HeaderTemplateResolver.resolve("${metadata.classification | Unclassified}", doc));
        assertEquals("Default-Tenant", HeaderTemplateResolver.resolve("${metadata.tenant|Default-Tenant}", doc));
    }

    @Test
    @DisplayName("Missing metadata without fallback resolves to empty string")
    void testMissingMetadataResolvesToEmpty() {
        Map<String, List<String>> metadata = new HashMap<>();
        RepositoryDocument doc = new RepositoryDocument(
                "doc-123",
                "file:///data/specs.pdf",
                null,
                metadata,
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        assertEquals("Prefix--Suffix", HeaderTemplateResolver.resolve("Prefix-${metadata.missing}-Suffix", doc));
    }

    @Test
    @DisplayName("Resolve document properties (${document.id}, ${document.uri}, ${document.action})")
    void testResolveDocumentProperties() {
        RepositoryDocument doc = new RepositoryDocument(
                "claim-987",
                "https://sharepoint.corp/docs/policy.docx",
                null,
                Map.of(),
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        assertEquals("claim-987", HeaderTemplateResolver.resolve("${document.id}", doc));
        assertEquals("https://sharepoint.corp/docs/policy.docx", HeaderTemplateResolver.resolve("${document.uri}", doc));
        assertEquals("UPSERT", HeaderTemplateResolver.resolve("${document.action}", doc));
    }

    @Test
    @DisplayName("Resolve active job ID from metadata")
    void testResolveJobId() {
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("job_id", List.of("job-migration-001"));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-1",
                "file:///tmp/doc.txt",
                null,
                metadata,
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        assertEquals("job-migration-001", HeaderTemplateResolver.resolve("${job.id}", doc));
    }

    @Test
    @DisplayName("resolveHeaders transforms a map and removes empty values")
    void testResolveHeadersMap() {
        Map<String, List<String>> metadata = new HashMap<>();
        metadata.put("dept", List.of("SecOps"));

        RepositoryDocument doc = new RepositoryDocument(
                "doc-99",
                "s3://bucket/test.bin",
                null,
                metadata,
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.UPSERT
        );

        Map<String, String> templates = Map.of(
                "x-emc-namespace", "finance-namespace",
                "x-emc-metadata-search", "Department=${metadata.dept},Class=${metadata.cls | Internal}",
                "x-empty-header", "${metadata.absent}",
                "x-amz-meta-source", "${document.id}"
        );

        Map<String, String> resolved = HeaderTemplateResolver.resolveHeaders(templates, doc);
        assertEquals(3, resolved.size());
        assertEquals("finance-namespace", resolved.get("x-emc-namespace"));
        assertEquals("Department=SecOps,Class=Internal", resolved.get("x-emc-metadata-search"));
        assertEquals("doc-99", resolved.get("x-amz-meta-source"));
        assertFalse(resolved.containsKey("x-empty-header"));
    }

    @Test
    @DisplayName("Null document gracefully uses fallbacks without NPE")
    void testNullDocumentHandling() {
        assertEquals("Default-Val", HeaderTemplateResolver.resolve("${metadata.key | Default-Val}", null));
        assertEquals("", HeaderTemplateResolver.resolve("${metadata.key}", null));
    }
}
