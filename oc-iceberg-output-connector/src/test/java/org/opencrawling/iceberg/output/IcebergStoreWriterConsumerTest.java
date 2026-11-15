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
package org.opencrawling.iceberg.output;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.iceberg.output.messaging.IcebergStoreWriterConsumer;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class IcebergStoreWriterConsumerTest {

    private IcebergOutputConnector outputConnector;
    private IcebergStoreWriterConsumer consumer;

    @BeforeEach
    public void setUp() {
        outputConnector = mock(IcebergOutputConnector.class);
        when(outputConnector.send(any(RepositoryDocument.class))).thenReturn(Mono.empty());
        consumer = new IcebergStoreWriterConsumer(outputConnector, null);
    }

    @Test
    public void testConsumeUpsertMessage() {
        SecurityConfig security = new SecurityConfig(
                true,
                List.of(new PermissionRule("analyst", "user", "Analyst", "read"))
        );

        IngestionMessage message = new IngestionMessage(
                "doc-101",
                "https://sharepoint.company.com/report.pdf",
                Map.of("title", List.of("Quarterly Report"), "entity.revenue", List.of("1200000")),
                null,
                security,
                null,
                null,
                null,
                null,
                DocumentAction.UPSERT
        );

        consumer.consume(message);

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(outputConnector, times(1)).send(captor.capture());

        RepositoryDocument sent = captor.getValue();
        assertThat(sent.id()).isEqualTo("doc-101");
        assertThat(sent.uri()).isEqualTo("https://sharepoint.company.com/report.pdf");
        assertThat(sent.action()).isEqualTo(DocumentAction.UPSERT);
        assertThat(sent.metadata()).containsKey("title");
        assertThat(sent.metadata()).containsKey("entity.revenue");
    }

    @Test
    public void testConsumeDeleteTombstoneMessage() {
        IngestionMessage message = new IngestionMessage(
                "doc-to-delete",
                "https://sharepoint.company.com/deleted.pdf",
                Map.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                DocumentAction.DELETE
        );

        consumer.consume(message);

        ArgumentCaptor<RepositoryDocument> captor = ArgumentCaptor.forClass(RepositoryDocument.class);
        verify(outputConnector, times(1)).send(captor.capture());

        RepositoryDocument sent = captor.getValue();
        assertThat(sent.id()).isEqualTo("doc-to-delete");
        assertThat(sent.action()).isEqualTo(DocumentAction.DELETE);
    }
}
