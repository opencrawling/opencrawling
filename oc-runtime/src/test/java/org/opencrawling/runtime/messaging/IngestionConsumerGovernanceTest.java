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
package org.opencrawling.runtime.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.governance.GovernanceDecision;
import org.opencrawling.core.governance.GovernanceSubject;
import org.opencrawling.core.governance.GovernanceUnavailableException;
import org.opencrawling.core.governance.PreEmbeddingGovernanceHook;
import org.opencrawling.core.messaging.DocumentChunkMessage;
import org.opencrawling.core.messaging.IngestionMessage;
import org.opencrawling.core.ois.OisMetadata;
import org.opencrawling.core.pipeline.PipelineMode;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;
import org.opencrawling.core.text.TextExtractionResult;
import org.opencrawling.core.text.TextExtractionService;
import org.opencrawling.runtime.config.KafkaConfig;
import org.opencrawling.runtime.governance.GovernanceGate;
import org.opencrawling.runtime.governance.GovernanceProperties.OnRejectedUpdate;
import org.opencrawling.runtime.governance.GovernanceProperties.Pii.Type;
import org.opencrawling.runtime.governance.PiiMaskingGovernanceHook;
import org.opencrawling.runtime.observability.TelemetryTraceStore;
import org.springframework.kafka.core.KafkaTemplate;

/** The pre-embedding governance gate in the ingestion consumer. */
class IngestionConsumerGovernanceTest {

    private static final String TEXT = "Contract for Alice. Write to alice@example.com about the renewal.";
    private static final SecurityConfig FINANCE = new SecurityConfig(false,
            List.of(new PermissionRule("finance", "GROUP", "Finance", "read")));

    private KafkaTemplate<String, Object> kafkaTemplate;
    private ClaimCheckStore claimCheckStore;
    private TelemetryTraceStore traceStore;
    private IngestionConsumer consumer;
    private final List<GovernanceSubject> seen = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        kafkaTemplate = mock(KafkaTemplate.class);
        doReturn(CompletableFuture.completedFuture(null)).when(kafkaTemplate).send(anyString(), anyString(), any());
        claimCheckStore = mock(ClaimCheckStore.class);
        when(claimCheckStore.supports(any())).thenReturn(true);
        when(claimCheckStore.get(any())).thenAnswer(invocation -> new ByteArrayInputStream(new byte[] {1}));
        TextExtractionService extraction = mock(TextExtractionService.class);
        when(extraction.extractText(any(byte[].class), any())).thenReturn(TextExtractionResult.success(TEXT, Map.of(), false));
        traceStore = mock(TelemetryTraceStore.class);
        consumer = new IngestionConsumer(kafkaTemplate, claimCheckStore, new ClaimCheckProperties(), traceStore, extraction);
    }

    private PreEmbeddingGovernanceHook hook(String name, GovernanceDecision decision) {
        return new PreEmbeddingGovernanceHook() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public GovernanceDecision evaluate(GovernanceSubject subject) {
                seen.add(subject);
                return decision;
            }
        };
    }

    private void gate(OnRejectedUpdate onRejectedUpdate, PreEmbeddingGovernanceHook... hooks) {
        consumer.setGovernanceGate(new GovernanceGate(List.of(hooks), onRejectedUpdate));
    }

    @Test
    void withoutAGateDocumentsAreChunkedAsTheyCome() {
        consumer.consume(message(DocumentAction.UPSERT));

        assertThat(chunks()).singleElement().satisfies(chunk -> assertThat(chunk.text()).isEqualTo(TEXT));
    }

    @Test
    void anApprovedDocumentIsChunkedAndTheHookSeesItsTextMetadataAndSecurity() {
        gate(OnRejectedUpdate.PURGE, hook("check", GovernanceDecision.approve()));

        consumer.consume(message(DocumentAction.UPSERT));

        assertThat(chunks()).singleElement().satisfies(chunk -> assertThat(chunk.text()).isEqualTo(TEXT));
        assertThat(seen).singleElement().satisfies(subject -> {
            assertThat(subject.text()).isEqualTo(TEXT);
            assertThat(subject.metadata()).containsEntry("owner", List.of("alice@example.com"));
            assertThat(subject.security()).isEqualTo(FINANCE);
            assertThat(subject.uri()).isEqualTo("file:///claims/doc");
        });
        verify(traceStore).recordSpan(org.mockito.ArgumentMatchers.argThat(span ->
                span.stage().equals("Governance") && span.status().equals("APPROVED")));
    }

    @Test
    void aSanitizedDocumentIsChunkedWithTheMaskedTextAndMetadataButItsOwnPermissions() {
        gate(OnRejectedUpdate.PURGE, new PiiMaskingGovernanceHook(java.util.EnumSet.allOf(Type.class), true));

        consumer.consume(message(DocumentAction.UPSERT));

        DocumentChunkMessage chunk = chunks().getFirst();
        assertThat(chunk.text()).doesNotContain("alice@example.com").contains("[REDACTED:EMAIL]");
        assertThat(chunk.metadata().get("owner")).isEqualTo(List.of("[REDACTED:EMAIL]"));
        assertThat(chunk.metadata().get("security_allowed_read")).asString().contains("finance");
        verify(traceStore).recordSpan(org.mockito.ArgumentMatchers.argThat(span -> span.stage().equals("Governance")
                && span.status().equals("SANITIZED") && "2".equals(span.attributes().get("pii-masking.masked.email"))));
    }

    @Test
    void aHookCannotChangeTheStoredPermissions() {
        Map<String, List<String>> widened = Map.of("acl", List.of("public"), "security", List.of("public"),
                "security_allowed_read", List.of("public"), "title", List.of("Changed"));
        gate(OnRejectedUpdate.PURGE, hook("widen", GovernanceDecision.sanitize("clean text", widened, Map.of())));

        consumer.consume(message(DocumentAction.UPSERT));

        DocumentChunkMessage chunk = chunks().getFirst();
        assertThat(chunk.text()).isEqualTo("clean text");
        assertThat(chunk.metadata().get("title")).isEqualTo(List.of("Changed"));
        assertThat(chunk.metadata().get("acl")).isEqualTo("finance-acl");
        assertThat(chunk.metadata().get("security_allowed_read")).asString().contains("finance").doesNotContain("public");
        assertThat(chunk.metadata().get("security")).isNotInstanceOf(List.class);
    }

    @Test
    void theDecisionIsRecordedAsOisGovernance() {
        gate(OnRejectedUpdate.PURGE, new PiiMaskingGovernanceHook(java.util.EnumSet.allOf(Type.class), true));

        consumer.consume(message(DocumentAction.UPSERT));

        Map<String, Object> metadata = chunks().getFirst().metadata();
        assertThat(metadata.get(OisMetadata.GOVERNANCE_DECISION)).isEqualTo(List.of("REDACT"));
        assertThat(metadata.get(OisMetadata.GOVERNANCE_REDACTED)).isEqualTo(List.of("true"));
        assertThat(metadata.get(OisMetadata.GOVERNANCE_REDACTION_CATEGORIES)).isEqualTo(List.of("EMAIL"));
        assertThat(metadata.get(OisMetadata.GOVERNANCE_POLICY_ID)).isEqualTo(List.of("pii-masking"));
        assertThat(metadata).containsKey(OisMetadata.GOVERNANCE_EVALUATED_AT);
    }

    @Test
    void anApprovedDocumentIsRecordedAsAllowed() {
        gate(OnRejectedUpdate.PURGE, hook("check", GovernanceDecision.approve()), hook("http", GovernanceDecision.approve()));

        consumer.consume(message(DocumentAction.UPSERT));

        Map<String, Object> metadata = chunks().getFirst().metadata();
        assertThat(metadata.get(OisMetadata.GOVERNANCE_DECISION)).isEqualTo(List.of("ALLOW"));
        assertThat(metadata.get(OisMetadata.GOVERNANCE_REDACTED)).isEqualTo(List.of("false"));
        assertThat(metadata).doesNotContainKey(OisMetadata.GOVERNANCE_REDACTION_CATEGORIES);
        assertThat(metadata.get(OisMetadata.GOVERNANCE_POLICY_ID)).isEqualTo(List.of("check,http"));
    }

    private static IngestionMessage claimingGovernance() {
        Map<String, List<String>> claimed = Map.of("owner", List.of("alice@example.com"),
                OisMetadata.PROVENANCE_ON_BEHALF_OF, List.of("alice"),
                OisMetadata.GOVERNANCE_DECISION, List.of("ALLOW"));
        return new IngestionMessage("doc", "file:///claims/doc", claimed, "finance-acl", FINANCE,
                "2026-01-01T00:00:00Z", null, null, Map.of(), DocumentAction.UPSERT, null, null);
    }

    @Test
    void aClaimedGovernanceDecisionIsDroppedWhenNoGateRan() {
        consumer.consume(claimingGovernance());

        Map<String, Object> ungoverned = chunks().getFirst().metadata();
        assertThat(ungoverned).doesNotContainKey(OisMetadata.GOVERNANCE_DECISION);
        assertThat(ungoverned.get(OisMetadata.PROVENANCE_ON_BEHALF_OF)).isEqualTo(List.of("alice"));
    }

    @Test
    void aHookCannotForgeTheOisBlocks() {
        IngestionMessage forged = claimingGovernance();
        Map<String, List<String>> rewritten = Map.of("owner", List.of("x"),
                OisMetadata.PROVENANCE_ON_BEHALF_OF, List.of("mallory"), OisMetadata.GOVERNANCE_DECISION, List.of("ALLOW"),
                OisMetadata.GOVERNANCE_POLICY_ID, List.of("forged"));
        gate(OnRejectedUpdate.PURGE, hook("rewrite", GovernanceDecision.sanitize("clean text", rewritten, Map.of())));

        consumer.consume(forged);

        assertThat(seen.getLast().metadata()).as("the hook never sees a claimed decision")
                .doesNotContainKey(OisMetadata.GOVERNANCE_DECISION);
        Map<String, Object> governed = chunks().getFirst().metadata();
        assertThat(governed.get(OisMetadata.PROVENANCE_ON_BEHALF_OF)).isEqualTo(List.of("alice"));
        assertThat(governed.get(OisMetadata.GOVERNANCE_DECISION)).isEqualTo(List.of("REDACT"));
        assertThat(governed.get(OisMetadata.GOVERNANCE_POLICY_ID)).isEqualTo(List.of("rewrite"));
        assertThat(governed.get("owner")).isEqualTo(List.of("x"));
    }

    @Test
    void aRejectedDocumentIsNotEmbeddedAndItsEarlierChunksArePurged() throws Exception {
        gate(OnRejectedUpdate.PURGE, hook("policy", GovernanceDecision.reject("contains unmasked credentials", Map.of())));

        consumer.consume(message(DocumentAction.UPSERT));

        DocumentChunkMessage tombstone = chunks().getFirst();
        assertThat(chunks()).hasSize(1);
        assertThat(tombstone.action()).isEqualTo(DocumentAction.DELETE);
        assertThat(tombstone.documentId()).isEqualTo("doc");
        verify(kafkaTemplate).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), eq("doc"), any());
        // The authorization sync learns of the rejection on the documents topic, keyed like the rejected version.
        ArgumentCaptor<Object> notice = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaConfig.TOPIC_NAME), eq("doc"), notice.capture());
        IngestionMessage rejection = (IngestionMessage) notice.getValue();
        assertThat(IngestionMessage.isGovernanceRejection(rejection)).isTrue();
        assertThat(rejection.lastModified()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(rejection.pipelineMode()).isEqualTo(PipelineMode.RAG);
        verify(claimCheckStore).delete(URI.create("file:///claims/doc"));
        verify(traceStore).recordSpan(org.mockito.ArgumentMatchers.argThat(span -> span.stage().equals("Governance")
                && span.status().equals("REJECTED") && "purged".equals(span.attributes().get("earlierVersion"))
                && "policy".equals(span.attributes().get("decidedBy"))));
    }

    @Test
    void withKeepARejectedDocumentLeavesTheEarlierVersion() throws Exception {
        gate(OnRejectedUpdate.KEEP, hook("policy", GovernanceDecision.reject("no", Map.of())));

        consumer.consume(message(DocumentAction.UPSERT));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(claimCheckStore).delete(URI.create("file:///claims/doc"));
    }

    @Test
    void whenGovernanceCannotDecideNothingIsEmbeddedOrRemoved() throws Exception {
        PreEmbeddingGovernanceHook down = new PreEmbeddingGovernanceHook() {
            @Override
            public String name() {
                return "service";
            }

            @Override
            public GovernanceDecision evaluate(GovernanceSubject subject) throws GovernanceUnavailableException {
                throw new GovernanceUnavailableException("the service answered 503 after 3 attempt(s)");
            }
        };
        gate(OnRejectedUpdate.PURGE, down);

        consumer.consume(message(DocumentAction.UPSERT));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        // The claim stays, so the document can be ingested again once governance is back.
        verify(claimCheckStore, never()).delete(any());
        verify(traceStore).recordError(eq("doc"), eq("ERROR"), eq("Governance"), contains("503"), any());
    }

    @Test
    void aGovernanceRejectionNoticeKeepsItsMarkAcrossKafkaSerialization() {
        tools.jackson.databind.json.JsonMapper json = tools.jackson.databind.json.JsonMapper.builder().build();
        IngestionMessage notice = IngestionMessage.governanceRejection(message(DocumentAction.UPSERT));

        IngestionMessage read = json.readValue(json.writeValueAsString(notice), IngestionMessage.class);

        assertThat(IngestionMessage.isGovernanceRejection(read)).isTrue();
        assertThat(read).isEqualTo(notice);
        assertThat(IngestionMessage.isGovernanceRejection(message(DocumentAction.DELETE))).isFalse();
    }

    @Test
    void aGovernanceRejectionNoticeIsNotForwardedToTheChunks() {
        gate(OnRejectedUpdate.PURGE, hook("policy", GovernanceDecision.reject("no", Map.of())));

        consumer.consume(IngestionMessage.governanceRejection(message(DocumentAction.UPSERT)));

        // The chunks were removed when the document was rejected; a second tombstone could remove a later version's.
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        assertThat(seen).isEmpty();
    }

    @Test
    void deletesAndMigrationsDoNotGoThroughTheGate() {
        gate(OnRejectedUpdate.PURGE, hook("policy", GovernanceDecision.reject("no", Map.of())));

        consumer.consume(message(DocumentAction.DELETE));
        consumer.consume(new IngestionMessage("doc", "file:///claims/doc", Map.of(), "finance-acl", FINANCE,
                "2026-01-01T00:00:00Z", null, null, Map.of(), DocumentAction.UPSERT, PipelineMode.MIGRATION, null));

        assertThat(seen).isEmpty();
        assertThat(chunks()).singleElement().satisfies(chunk -> assertThat(chunk.action()).isEqualTo(DocumentAction.DELETE));
    }

    private static IngestionMessage message(DocumentAction action) {
        return new IngestionMessage("doc", "file:///claims/doc", Map.of("owner", List.of("alice@example.com")),
                "finance-acl", FINANCE, "2026-01-01T00:00:00Z", null, null, Map.of(), action, null, null);
    }

    private List<DocumentChunkMessage> chunks() {
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeast(0)).send(eq(KafkaConfig.CHUNKS_TOPIC_NAME), anyString(), sent.capture());
        return sent.getAllValues().stream().map(DocumentChunkMessage.class::cast).toList();
    }
}
