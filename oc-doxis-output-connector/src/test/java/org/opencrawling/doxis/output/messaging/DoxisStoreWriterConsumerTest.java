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
package org.opencrawling.doxis.output.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.doxis.output.DoxisOutputConnector;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DoxisStoreWriterConsumerTest {

    private DoxisOutputConnector connector;
    private ClaimCheckStore store;
    private DoxisStoreWriterConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connector = mock(DoxisOutputConnector.class);
        store = mock(ClaimCheckStore.class);
        ObjectProvider<ClaimCheckStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        when(connector.send(any())).thenReturn(Mono.empty());
        consumer = new DoxisStoreWriterConsumer(connector, provider);
    }

    private static IngestionMessage message(String uri, DocumentAction action) {
        return new IngestionMessage("doc-1", uri, Map.of("title", List.of("MSA")), "elena", SecurityConfig.createPublic(),
                "2026-09-30T08:00:00Z", null, null, Map.of(), action);
    }

    @Test
    void claimCheckContentIsOpenedLazilyOnlyWhenRead() throws Exception {
        when(store.get(URI.create("ozone://vol/bucket/doc-1"))).thenReturn(new ByteArrayInputStream(new byte[]{42}));

        RepositoryDocument document = consumer.toDocument(message("ozone://vol/bucket/doc-1", DocumentAction.UPSERT));

        verify(store, never()).get(any());
        assertEquals(42, document.contentStream().read());
        verify(store).get(URI.create("ozone://vol/bucket/doc-1"));
        assertEquals("doc-1", document.id());
        assertEquals("2026-09-30T08:00:00Z", document.lastModified().toString());
    }

    @Test
    void localFilesAreLeftToThePlannerWithoutAStream() {
        RepositoryDocument document = consumer.toDocument(message("file:///mnt/share/a.pdf", DocumentAction.UPSERT));

        assertNull(document.contentStream());
        assertEquals("file:///mnt/share/a.pdf", document.uri());
    }

    @Test
    void deleteMessagesBecomeTombstones() {
        consumer.consume(message("file:///mnt/share/a.pdf", DocumentAction.DELETE));

        verify(connector).send(argThat(d -> d.action() == DocumentAction.DELETE && "doc-1".equals(d.id())));
    }

    @Test
    void failuresAreLoggedNotPropagated() {
        when(connector.send(any())).thenReturn(Mono.error(new RuntimeException("Doxis down")));

        assertDoesNotThrow(() -> consumer.consume(message("file:///mnt/share/a.pdf", DocumentAction.UPSERT)));
    }
}
