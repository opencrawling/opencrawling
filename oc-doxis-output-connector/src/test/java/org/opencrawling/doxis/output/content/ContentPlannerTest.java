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
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Content;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.Locator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ContentPlannerTest {

    @TempDir
    Path tmp;

    private static ContentPlanner planner(ContentStrategy strategy, long maxBytes, String uriPrefix) {
        return new ContentPlanner(new Content(strategy, maxBytes, ContentStrategy.REFERENCE_ONLY, true),
                new PrefixLocatorResolver(new Locator("doxisLocator", uriPrefix, "")));
    }

    private static RepositoryDocument doc(String uri, InputStream content, Map<String, List<String>> metadata) {
        return new RepositoryDocument("doc-1", uri, content, metadata, "", SecurityConfig.createPublic(), Instant.now());
    }

    /** A stream that records whether it was read or closed — planning must never read content. */
    static final class TrackingStream extends ByteArrayInputStream {
        final AtomicBoolean read = new AtomicBoolean();
        final AtomicBoolean closed = new AtomicBoolean();

        TrackingStream() {
            super("data".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            read.set(true);
            return super.read(b, off, len);
        }

        @Override
        public synchronized int read() {
            read.set(true);
            return super.read();
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    @Test
    void autoPrefersExplicitLocatorFromMetadataAndNeverReadsContent() {
        TrackingStream stream = new TrackingStream();
        RepositoryDocument document = doc("s3://bucket/huge.mxf", stream, Map.of(
                "doxisLocator", List.of("store-01/2026/10/01/huge.mxf"),
                "sizeInBytes", List.of("5497558138880"),
                "sha256", List.of("abc123"),
                "mimeType", List.of("application/mxf")));

        ContentPlan plan = planner(ContentStrategy.AUTO, 1024, null).plan(document);

        assertEquals(ContentStrategy.PREDEFINED_LOCATOR, plan.strategy());
        assertEquals("store-01/2026/10/01/huge.mxf", plan.locator());
        assertEquals(5_497_558_138_880L, plan.length());
        assertEquals("abc123", plan.sha256());
        assertNull(plan.body());
        assertFalse(stream.read.get());
        assertTrue(stream.closed.get());
    }

    @Test
    void locatorIsDerivedFromUriPrefixForInPlaceContent() {
        RepositoryDocument document = doc("file:///mnt/doxis-store/D_TEXTER/2026/Film%20Master.mxf", null, Map.of());

        ContentPlan plan = new ContentPlanner(Content.defaults(),
                new PrefixLocatorResolver(new Locator(null, "file:///mnt/doxis-store/", "fs01/"))).plan(document);

        assertEquals(ContentStrategy.PREDEFINED_LOCATOR, plan.strategy());
        assertEquals("fs01/D_TEXTER/2026/Film Master.mxf", plan.locator());
        assertEquals("Film Master.mxf", plan.fileName());
    }

    @Test
    void autoUploadsSmallLocalFilesWithReopenableStream() throws Exception {
        Path file = Files.writeString(tmp.resolve("contract.pdf"), "%PDF-1.7 contract");
        TrackingStream original = new TrackingStream();

        ContentPlan plan = planner(ContentStrategy.AUTO, 1024, null).plan(doc(file.toUri().toString(), original, Map.of()));

        assertEquals(ContentStrategy.UPLOAD, plan.strategy());
        assertEquals(17L, plan.length());
        assertEquals("application/pdf", plan.mimeType());
        assertTrue(plan.body().reopenable());
        try (InputStream in = plan.body().stream().get()) {
            assertEquals("%PDF-1.7 contract", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertTrue(original.closed.get());
    }

    @Test
    void autoFallsBackToReferenceOnlyAboveUploadLimit() throws Exception {
        Path file = Files.write(tmp.resolve("big.bin"), new byte[2048]);

        ContentPlan plan = planner(ContentStrategy.AUTO, 1024, null).plan(doc(file.toUri().toString(), null, Map.of()));

        assertEquals(ContentStrategy.REFERENCE_ONLY, plan.strategy());
        assertEquals(2048L, plan.length());
        assertNull(plan.body());
    }

    @Test
    void explicitUploadRefusesFilesAboveLimit() throws Exception {
        Path file = Files.write(tmp.resolve("big.bin"), new byte[2048]);

        assertThrows(IllegalStateException.class,
                () -> planner(ContentStrategy.UPLOAD, 1024, null).plan(doc(file.toUri().toString(), null, Map.of())));
    }

    @Test
    void explicitLocatorStrategyRequiresALocator() {
        assertThrows(IllegalStateException.class,
                () -> planner(ContentStrategy.PREDEFINED_LOCATOR, 1024, null).plan(doc("s3://b/k", null, Map.of())));
    }

    @Test
    void remoteStreamIsUploadedAsSingleUseBody() {
        TrackingStream stream = new TrackingStream();

        ContentPlan plan = planner(ContentStrategy.AUTO, 1024, null).plan(doc("ozone://vol/bucket/key", stream, Map.of()));

        assertEquals(ContentStrategy.UPLOAD, plan.strategy());
        assertFalse(plan.body().reopenable());
        assertSame(stream, plan.body().stream().get());
    }

    @Test
    void documentWithoutContentBecomesReferenceOnly() {
        ContentPlan plan = planner(ContentStrategy.AUTO, 1024, null).plan(doc("https://example.org/x.pdf", null, Map.of()));

        assertEquals(ContentStrategy.REFERENCE_ONLY, plan.strategy());
        assertEquals("x.pdf", plan.fileName());
    }
}
