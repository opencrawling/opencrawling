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
package org.opencrawling.solr.messaging;

import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.mockito.Mockito.*;

class SolrStoreWriterConsumerTest {

    @Test
    void testConsumeDeleteTombstoneDeletesFromSolr() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        SolrStoreWriterConsumer consumer = new SolrStoreWriterConsumer(solrClient);
        ReflectionTestUtils.setField(consumer, "collectionName", "enterprise_kb");
        ReflectionTestUtils.setField(consumer, "commitWithinMs", 1000);

        DocumentEmbeddedMessage tombstone = new DocumentEmbeddedMessage(
                "doc-solr-1",
                "chunk-solr-1",
                "",
                Map.of(),
                new float[0],
                DocumentAction.DELETE
        );

        consumer.consume(tombstone);

        verify(solrClient).deleteByQuery(eq("enterprise_kb"), eq("id:\"doc-solr-1\" OR chunk_id:\"chunk-solr-1\""), eq(1000));
    }
}
