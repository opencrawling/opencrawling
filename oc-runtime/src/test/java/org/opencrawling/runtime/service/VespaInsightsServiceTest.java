/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.service;

import java.util.List;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import org.opencrawling.runtime.service.VespaInsightsService.DocumentTypeCount;
import org.opencrawling.runtime.service.VespaInsightsService.VespaDeployResult;
import org.opencrawling.runtime.service.VespaInsightsService.VespaHealthResult;
import org.opencrawling.runtime.service.VespaInsightsService.VespaQueryResult;

class VespaInsightsServiceTest {

    private final VespaInsightsService insightsService = new VespaInsightsService(null);

    @Test
    void testCheckHealthUnreachable() {
        VespaHealthResult result = insightsService.checkHealth("http://127.0.0.1:59999");
        assertFalse(result.up());
        assertTrue(result.message().contains("Failed to reach Vespa"));
    }

    @Test
    void testGetDocumentCountsUnreachableMarksAllUnavailable() {
        List<DocumentTypeCount> counts = insightsService.getDocumentCounts("http://127.0.0.1:59999");
        assertEquals(4, counts.size());
        assertTrue(counts.stream().noneMatch(DocumentTypeCount::available));
    }

    @Test
    void testRunQueryDefaultRankProfileDoesNotRequireEmbeddingModel() {
        VespaQueryResult result = insightsService.runQuery("http://127.0.0.1:59999", "opencrawling_chunk", "hello", "default");
        assertTrue(result.degraded());
        assertTrue(result.message().contains("Query failed"));
    }

    @Test
    void testRunQuerySemanticWithoutEmbeddingModelDegradesToBm25() {
        VespaQueryResult result = insightsService.runQuery("http://127.0.0.1:59999", "opencrawling_chunk_1024", "hello", "semantic");
        assertTrue(result.degraded());
        assertTrue(result.message().contains("No embedding model is configured"));
    }

    @Test
    void testDeployBundledSchemaLoadsClasspathResourcesBeforeFailingOnUnreachableEndpoint() {
        VespaDeployResult result = insightsService.deployBundledSchema("http://127.0.0.1:59999");
        assertFalse(result.success());
        // A message about "prepare the bundled schema package" would mean the classpath resources
        // (packaged via oc-vespa-output-connector's <resources> entry) failed to load; a message about
        // the deploy call itself confirms the schema files were found and zipped correctly first.
        assertTrue(result.message().contains("Failed to deploy application package to"));
    }

    @Test
    void testDeployCustomSchemaRejectsUnsupportedContentType() {
        VespaDeployResult result = insightsService.deployCustomSchema("http://127.0.0.1:59999", new byte[]{1, 2, 3}, "text/plain");
        assertFalse(result.success());
        assertTrue(result.message().contains("Unsupported content type"));
    }

    @Test
    void testCheckHealthRejectsNonHttpScheme() {
        VespaHealthResult result = insightsService.checkHealth("file:///etc/passwd");
        assertFalse(result.up());
        assertTrue(result.message().contains("must be an http or https URL"));
    }

    @Test
    void testGetDocumentCountsRejectsNonHttpScheme() {
        List<DocumentTypeCount> counts = insightsService.getDocumentCounts("gopher://internal-service:70/");
        assertEquals(4, counts.size());
        assertTrue(counts.stream().noneMatch(DocumentTypeCount::available));
    }

    @Test
    void testRunQueryRejectsNonHttpScheme() {
        VespaQueryResult result = insightsService.runQuery("file:///etc/passwd", "opencrawling_chunk", "hello", "default");
        assertTrue(result.degraded());
        assertTrue(result.message().contains("must be an http or https URL"));
    }

    @Test
    void testDeployBundledSchemaRejectsNonHttpScheme() {
        VespaDeployResult result = insightsService.deployBundledSchema("file:///etc/passwd");
        assertFalse(result.success());
        assertTrue(result.message().contains("must be an http or https URL"));
    }
}
