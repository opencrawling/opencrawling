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
package org.opencrawling.doxis.output.content;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Content;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentLink;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Locator;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContentLinkResolverTest {

    private static RepositoryDocument doc(String uri, Map<String, List<String>> metadata) {
        return new RepositoryDocument("doc-1", uri, null, metadata, "", SecurityConfig.createPublic(), Instant.now());
    }

    private static ContentLink unc() {
        return new ContentLink("/opt/doxis-client", null, 0, "file:///mnt/archive/", "\\\\fileserver\\archive\\",
                ContentLinkWriter.LinkType.UNC, null, null);
    }

    @Test
    void mapsMountedPathToUncShareWithBackslashes() {
        ContentLinkResolver.Target target = new ContentLinkResolver(unc())
                .resolve(doc("file:///mnt/archive/2026/Film%20Master.mxf", Map.of())).orElseThrow();

        assertEquals(ContentLinkWriter.LinkType.UNC, target.type());
        assertEquals("\\\\fileserver\\archive\\2026\\Film Master.mxf", target.link());
    }

    @Test
    void explicitMetadataLinkWins() {
        ContentLinkResolver.Target target = new ContentLinkResolver(unc())
                .resolve(doc("s3://bucket/key", Map.of("doxisContentLink", List.of("\\\\nas\\x\\y.bin")))).orElseThrow();

        assertEquals("\\\\nas\\x\\y.bin", target.link());
    }

    @Test
    void uriOutsidePrefixHasNoLink() {
        assertTrue(new ContentLinkResolver(unc()).resolve(doc("file:///other/a.pdf", Map.of())).isEmpty());
    }

    @Test
    void autoLinksLargeFilesOnlyWhenAWriterIsAvailable() {
        RepositoryDocument big = doc("file:///mnt/archive/2026/master.mxf", Map.of("sizeInBytes", List.of("5497558138880")));
        Content content = new Content(ContentStrategy.AUTO, 1024, ContentStrategy.REFERENCE_ONLY, true, null);
        PrefixLocatorResolver noLocator = new PrefixLocatorResolver(Locator.defaults());

        ContentPlan linked = new ContentPlanner(content, noLocator, new ContentLinkResolver(unc()), true).plan(big);
        assertEquals(ContentStrategy.CONTENT_LINK, linked.strategy());
        assertEquals("\\\\fileserver\\archive\\2026\\master.mxf", linked.contentLink().link());
        assertNull(linked.body());

        ContentPlan withoutWriter = new ContentPlanner(content, noLocator, new ContentLinkResolver(unc()), false).plan(big);
        assertEquals(ContentStrategy.REFERENCE_ONLY, withoutWriter.strategy());
    }

    @Test
    void explicitContentLinkStrategyRequiresWriterAndLink() {
        Content content = new Content(ContentStrategy.CONTENT_LINK, 1024, ContentStrategy.REFERENCE_ONLY, true, null);
        PrefixLocatorResolver noLocator = new PrefixLocatorResolver(Locator.defaults());

        assertThrows(IllegalStateException.class, () -> new ContentPlanner(content, noLocator, new ContentLinkResolver(unc()), false)
                .plan(doc("file:///mnt/archive/a.pdf", Map.of())));
        assertThrows(IllegalStateException.class, () -> new ContentPlanner(content, noLocator, new ContentLinkResolver(unc()), true)
                .plan(doc("file:///other/a.pdf", Map.of())));
        assertEquals(ContentStrategy.CONTENT_LINK, new ContentPlanner(content, noLocator, new ContentLinkResolver(unc()), true)
                .plan(doc("file:///mnt/archive/a.pdf", Map.of())).strategy());
    }
}
