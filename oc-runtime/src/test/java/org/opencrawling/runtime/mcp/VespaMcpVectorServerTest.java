/*
 * Copyright © 2026 textercabaceira (luis.cabaceira@texter.ai)
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
package org.opencrawling.runtime.mcp;

import org.junit.jupiter.api.Test;
import org.opencrawling.vespa.VespaFields;
import org.opencrawling.vespa.config.VespaOutputProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VespaMcpVectorServerTest {

    private static final VespaOutputProperties UNREACHABLE = VespaOutputProperties.builder()
            .endpoint("http://127.0.0.1:59999")
            .timeoutSeconds(2)
            .build();

    private final VespaMcpVectorServer mcpServer = new VespaMcpVectorServer(UNREACHABLE, null);

    @Test
    void testSecureVectorSearchUnreachableEndpointThrows() {
        assertThrows(RuntimeException.class, () ->
                mcpServer.vespaSecureVectorSearch("kubernetes", "user@enterprise.com", "engineering", 5, "hybrid", null));
    }

    @Test
    void testGetDocumentContentUnreachableEndpointThrows() {
        assertThrows(RuntimeException.class, () ->
                mcpServer.vespaGetDocumentContent("file:///doc.txt", "user@enterprise.com", null, null));
    }

    @Test
    void testListAccessibleSourcesUnreachableEndpointThrows() {
        assertThrows(RuntimeException.class, () ->
                mcpServer.vespaListAccessibleSources("user@enterprise.com", null, null));
    }

    @Test
    void testIsAccessiblePublicLegacyAclAllowsAnyone() {
        VespaMcpVectorServer.RawHit hit = new VespaMcpVectorServer.RawHit(
                "c1", "file:///a.txt", "text", "public", List.of(), List.of(), 1.0);
        assertTrue(VespaMcpVectorServer.isAccessible(hit, "anyone@enterprise.com", List.of()));
    }

    @Test
    void testIsAccessibleDenyOverridesAllow() {
        VespaMcpVectorServer.RawHit hit = new VespaMcpVectorServer.RawHit(
                "c1", "file:///a.txt", "text", "", List.of("finance"), List.of("user@enterprise.com"), 1.0);
        assertFalse(VespaMcpVectorServer.isAccessible(hit, "user@enterprise.com", List.of("finance")));
    }

    @Test
    void testIsAccessibleAllowedGroupMatches() {
        VespaMcpVectorServer.RawHit hit = new VespaMcpVectorServer.RawHit(
                "c1", "file:///a.txt", "text", "", List.of("finance"), List.of(), 1.0);
        assertTrue(VespaMcpVectorServer.isAccessible(hit, "user@enterprise.com", List.of("finance")));
        assertFalse(VespaMcpVectorServer.isAccessible(hit, "user@enterprise.com", List.of("engineering")));
    }

    @Test
    void testIsAccessibleEmptyRulesFallBackToLegacyAcl() {
        VespaMcpVectorServer.RawHit hit = new VespaMcpVectorServer.RawHit(
                "c1", "file:///a.txt", "text", "finance-manager@enterprise.com", List.of(), List.of(), 1.0);
        assertTrue(VespaMcpVectorServer.isAccessible(hit, "finance-manager@enterprise.com", List.of()));
        assertFalse(VespaMcpVectorServer.isAccessible(hit, "someone-else@enterprise.com", List.of()));
    }

    @Test
    void testBuildDenyExclusionEscapesQuotes() {
        String yql = VespaMcpVectorServer.buildDenyExclusion("weird\"user", List.of());
        assertTrue(yql.contains("weird\\\"user"));
        assertTrue(yql.contains(VespaFields.FIELD_SECURITY_DENIED_READ));
    }

    @Test
    void testBuildDenyExclusionWithNoIdentityAlwaysTrue() {
        assertEquals("true", VespaMcpVectorServer.buildDenyExclusion(null, List.of()));
    }

    @Test
    void testResolveDocumentTypeByDimensions() {
        assertEquals(VespaFields.DOCUMENT_TYPE_384, mcpServer.resolveDocumentType(384));
        assertEquals(VespaFields.DOCUMENT_TYPE_768, mcpServer.resolveDocumentType(768));
        assertEquals(VespaFields.DOCUMENT_TYPE_1024, mcpServer.resolveDocumentType(1024));
        assertEquals("opencrawling_chunk", mcpServer.resolveDocumentType(null));
    }
}
