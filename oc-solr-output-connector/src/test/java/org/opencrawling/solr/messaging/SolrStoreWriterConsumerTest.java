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
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.DocumentEmbeddedMessage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        verify(solrClient).deleteByQuery(eq("enterprise_kb"), eq("id:\"doc-solr-1\" OR documentId:\"doc-solr-1\" OR chunk_id:\"chunk-solr-1\""), eq(1000));
    }

    @Test
    void aFailedDeleteIsRethrownSoKafkaRetriesIt() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.deleteByQuery(anyString(), anyString(), anyInt())).thenThrow(new java.io.IOException("solr unavailable"));
        SolrStoreWriterConsumer consumer = new SolrStoreWriterConsumer(solrClient);
        ReflectionTestUtils.setField(consumer, "collectionName", "enterprise_kb");
        ReflectionTestUtils.setField(consumer, "commitWithinMs", 1000);

        assertThatThrownBy(() -> consumer.consume(new DocumentEmbeddedMessage("doc-solr-1", "chunk-solr-1", "",
                Map.of(), new float[0], DocumentAction.DELETE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-solr-1")
                .hasRootCauseMessage("solr unavailable");
    }

    @Test
    void storesChunkWhenMetadataAlreadyCarriesFlattenedSecurityFields() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        SolrStoreWriterConsumer consumer = new SolrStoreWriterConsumer(solrClient);
        ReflectionTestUtils.setField(consumer, "collectionName", "enterprise_kb");
        ReflectionTestUtils.setField(consumer, "commitWithinMs", 1000);

        // Shape of the metadata the ingestion consumer publishes (lists arrive mutable after JSON deserialization).
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("uri", "file:///docs/a.txt");
        metadata.put("acl", "finance");
        metadata.put("security", Map.of("inheritanceEnabled", true, "permissions", List.of(
                Map.of("identity", "finance", "access", "read"),
                Map.of("identity", "cn=bob,ou=users,dc=corp", "access", "deny"))));
        metadata.put("security_inheritance", true);
        metadata.put("security_allowed_read", new ArrayList<>(List.of("finance")));
        metadata.put("security_denied_read", new ArrayList<>(List.of("cn=bob,ou=users,dc=corp")));
        metadata.put("title", "A");

        consumer.consume(new DocumentEmbeddedMessage("doc-1", "chunk-1", "text", metadata,
                new float[]{0.1f, 0.2f}, DocumentAction.UPSERT));

        ArgumentCaptor<SolrInputDocument> captor = ArgumentCaptor.forClass(SolrInputDocument.class);
        verify(solrClient).add(eq("enterprise_kb"), captor.capture(), eq(1000));
        SolrInputDocument doc = captor.getValue();
        assertThat(doc.getFieldValues("security_allowed_read")).containsExactly("finance");
        assertThat(doc.getFieldValues("security_denied_read")).containsExactly("cn=bob,ou=users,dc=corp");
        assertThat(doc.getFieldValue("acl")).isEqualTo("finance");
        assertThat(doc.getFieldValue("title")).isEqualTo("A");
    }
}
