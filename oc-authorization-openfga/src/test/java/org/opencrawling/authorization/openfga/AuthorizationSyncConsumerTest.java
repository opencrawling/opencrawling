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
package org.opencrawling.authorization.openfga;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.security.EffectiveAcl;
import org.opencrawling.core.security.SecurityConfig;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthorizationSyncConsumerTest {

    private final OpenFgaTupleWriter writer = mock(OpenFgaTupleWriter.class);
    private final AuthorizationSyncConsumer consumer = new AuthorizationSyncConsumer(writer);

    private static IngestionMessage message(String id, String acl, DocumentAction action) {
        return new IngestionMessage(id, "file:///" + id, Map.of(), acl, SecurityConfig.createPublic(), "now",
                null, null, Map.of(), action);
    }

    @Test
    void upsertsSyncTheEffectivePermissions() throws Exception {
        consumer.consume(message("d1", "finance", DocumentAction.UPSERT));

        ArgumentCaptor<EffectiveAcl> acl = ArgumentCaptor.forClass(EffectiveAcl.class);
        verify(writer).sync(eq("d1"), acl.capture());
        // The default public placeholder is overridden by the restrictive acl, exactly as the stores index it.
        assertThat(acl.getValue().allowedRead()).containsExactly("finance");
    }

    @Test
    void aMessageWithoutSecurityGrantsNoOne() throws Exception {
        consumer.consume(new IngestionMessage("d1", "file:///d1", Map.of(), "", null, "now",
                null, null, Map.of(), DocumentAction.UPSERT));

        ArgumentCaptor<EffectiveAcl> acl = ArgumentCaptor.forClass(EffectiveAcl.class);
        verify(writer).sync(eq("d1"), acl.capture());
        // Missing permissions are never written to OpenFGA as a public grant.
        assertThat(acl.getValue().allowedRead()).isEmpty();
        assertThat(acl.getValue().isPublic()).isFalse();
    }

    @Test
    void tombstonesRemoveAllTuples() throws Exception {
        consumer.consume(message("d1", null, DocumentAction.DELETE));
        verify(writer).delete("d1");
    }

    private static IngestionMessage version(String id, String lastModified) {
        return new IngestionMessage(id, "file:///" + id, Map.of(), "finance", SecurityConfig.none(), lastModified,
                null, null, Map.of(), DocumentAction.UPSERT);
    }

    @Test
    void aGovernanceRejectionRemovesTheTuplesWrittenForTheRejectedVersion() throws Exception {
        IngestionMessage rejected = version("d1", "v1");
        consumer.consume(rejected);
        consumer.consume(IngestionMessage.governanceRejection(rejected));

        verify(writer).sync(eq("d1"), any());
        verify(writer).delete("d1");
    }

    @Test
    void aGovernanceRejectionIsIgnoredWhenALaterVersionWasSyncedAfterIt() throws Exception {
        IngestionMessage rejected = version("d1", "v1");
        consumer.consume(rejected);
        consumer.consume(version("d1", "v2"));
        consumer.consume(IngestionMessage.governanceRejection(rejected));

        verify(writer, never()).delete(any());
    }

    @Test
    void aGovernanceRejectionWithoutARecordedVersionRemovesTheTuples() throws Exception {
        // After a restart nothing is remembered: removing fails closed until the next crawl.
        consumer.consume(IngestionMessage.governanceRejection(version("d1", "v1")));
        verify(writer).delete("d1");
    }

    @Test
    void aGovernanceRejectionMatchesAVersionWithoutLastModified() throws Exception {
        IngestionMessage rejected = version("d1", null);
        consumer.consume(rejected);
        consumer.consume(IngestionMessage.governanceRejection(rejected));
        verify(writer).delete("d1");
    }

    @Test
    void aRejectionAfterAnotherDocumentsUpdateStillApplies() throws Exception {
        IngestionMessage rejected = version("d1", "v1");
        consumer.consume(rejected);
        consumer.consume(version("d2", "v9"));
        consumer.consume(IngestionMessage.governanceRejection(rejected));
        verify(writer).delete("d1");
        verify(writer, never()).delete("d2");
    }

    @Test
    void theRememberedVersionsAreBounded() throws Exception {
        consumer.consume(version("d0", "v2"));
        for (int i = 1; i <= AuthorizationSyncConsumer.REMEMBERED_VERSIONS; i++) {
            consumer.consume(version("d" + i, "v1"));
        }
        // d0's later version was evicted, so the stale rejection cannot be compared and removes the tuples (fail closed).
        consumer.consume(IngestionMessage.governanceRejection(version("d0", "v1")));
        verify(writer).delete("d0");
    }

    @Test
    void messagesWithoutADocumentIdAreSkipped() throws Exception {
        consumer.consume(message(" ", "finance", DocumentAction.UPSERT));
        consumer.consume(null);
        verifyNoInteractions(writer);
    }

    @Test
    void failuresAreRethrownForRetryAndDeadLettering() throws Exception {
        doThrow(new IllegalStateException("openfga down")).when(writer).sync(eq("d1"), any());
        assertThatThrownBy(() -> consumer.consume(message("d1", "finance", DocumentAction.UPSERT)))
                .hasMessage("openfga down");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSyncContainerRetriesThenDeadLetters() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new OpenFgaAuthorizationConfiguration()
                .authzSyncContainerFactory(mock(ConsumerFactory.class), mock(KafkaTemplate.class));
        assertThat(factory.getContainerProperties()).isNotNull();
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(factory, "commonErrorHandler"))
                .isInstanceOf(DefaultErrorHandler.class);
        assertThat(new OpenFgaAuthorizationConfiguration().authzSyncDeadLetterTopic().name())
                .isEqualTo("opencrawling-documents.authz-dlt");
    }
}
