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
package org.opencrawling.core.ois;

import org.junit.jupiter.api.Test;
import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.EffectiveAcl;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OisMetadataTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void triggerSourcesMapToTheRecommendedValuesAndOthersPassThrough() {
        assertEquals("manual", OisMetadata.triggeredBy(ExecutionProvenance.SOURCE_API));
        assertEquals("mcp-agent", OisMetadata.triggeredBy(ExecutionProvenance.SOURCE_MCP));
        assertEquals("webhook", OisMetadata.triggeredBy(ExecutionProvenance.SOURCE_BPM));
        assertEquals("system", OisMetadata.triggeredBy(ExecutionProvenance.SOURCE_STARTUP));
        assertEquals("grpc-push", OisMetadata.triggeredBy(ExecutionProvenance.SOURCE_GRPC_PUSH));
        assertEquals("dynamic-api", OisMetadata.triggeredBy(ExecutionProvenance.SOURCE_DYNAMIC_API));
        assertNull(OisMetadata.triggeredBy(null));
    }

    @Test
    void delegatedRunsRecordTheAgentAsActorAndTheUserAsOnBehalfOf() {
        Map<String, List<String>> delegated = OisMetadata.withSourceAndProvenance(Map.of(), "cmis", "https://ecm",
                "1.0", "job-1", ExecutionProvenance.verified("alice", "agent-7", List.of(), null,
                        ExecutionProvenance.SOURCE_MCP, null), NOW);
        assertEquals(List.of("alice"), delegated.get(OisMetadata.PROVENANCE_ON_BEHALF_OF));
        assertEquals(List.of("agent-7"), delegated.get(OisMetadata.PROVENANCE_ACTOR_AGENT));
        assertEquals(List.of("mcp-agent"), delegated.get(OisMetadata.PROVENANCE_TRIGGERED_BY));
        assertEquals(List.of("job-1"), delegated.get(OisMetadata.PROVENANCE_JOB_ID));
        assertEquals(List.of(NOW.toString()), delegated.get(OisMetadata.PROVENANCE_INGESTED_AT));
        assertEquals(List.of("https://ecm"), delegated.get(OisMetadata.SOURCE_INSTANCE));

        Map<String, List<String>> direct = OisMetadata.withSourceAndProvenance(Map.of(), null, null, null, "job-1",
                ExecutionProvenance.verified("bob", null, List.of(), null, ExecutionProvenance.SOURCE_API, null), NOW);
        assertEquals(List.of("bob"), direct.get(OisMetadata.PROVENANCE_ON_BEHALF_OF));
        assertFalse(direct.containsKey(OisMetadata.PROVENANCE_ACTOR_AGENT));

        Map<String, List<String>> anonymous = OisMetadata.withSourceAndProvenance(Map.of(), null, null, null, "job-1",
                ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_OIS, null), NOW);
        assertFalse(anonymous.containsKey(OisMetadata.PROVENANCE_ON_BEHALF_OF), "nobody unverified is named");
        assertEquals(List.of("ois"), anonymous.get(OisMetadata.PROVENANCE_TRIGGERED_BY));
    }

    @Test
    void stampingReplacesWhatTheSourceClaimed() {
        Map<String, List<String>> forged = Map.of(
                "ois:provenance.onBehalfOf", List.of("mallory"),
                "ois:provenance.actorAgent", List.of("evil-agent"),
                "ois:source.instance", List.of("https://elsewhere"),
                "ois:governance.decision", List.of("ALLOW"),
                "ois:provenance", List.of("{jobId=x}"),
                "title", List.of("kept"));
        Map<String, List<String>> clean = OisMetadata.withoutOisBlocks(forged);
        assertEquals(Map.of("title", List.of("kept")), clean);

        Map<String, List<String>> stamped = OisMetadata.withSourceAndProvenance(forged, "cmis", null, null, "job-1",
                ExecutionProvenance.anonymous(ExecutionProvenance.SOURCE_API, null), NOW);
        assertFalse(stamped.containsKey("ois:provenance.actorAgent"));
        assertFalse(stamped.containsKey("ois:source.instance"));
        assertFalse(stamped.containsKey("ois:provenance"));

        assertTrue(OisMetadata.isOisBlockKey("ois:governance.decision"));
        assertTrue(OisMetadata.isOisBlockKey("ois:source"));
        assertFalse(OisMetadata.isOisBlockKey("ois:sourceX"), "only the blocks themselves");
        assertFalse(OisMetadata.isOisBlockKey("title"));
    }

    @Test
    void governanceRecordsTheDecision() {
        Map<String, List<String>> redacted = OisMetadata.withGovernance(
                Map.of("ois:governance.policyId", List.of("forged")), OisMetadata.Decision.REDACT,
                List.of("EMAIL"), "pii-masking", NOW);
        assertEquals(List.of("REDACT"), redacted.get(OisMetadata.GOVERNANCE_DECISION));
        assertEquals(List.of("true"), redacted.get(OisMetadata.GOVERNANCE_REDACTED));
        assertEquals(List.of("EMAIL"), redacted.get(OisMetadata.GOVERNANCE_REDACTION_CATEGORIES));
        assertEquals(List.of("pii-masking"), redacted.get(OisMetadata.GOVERNANCE_POLICY_ID));

        Map<String, List<String>> allowed = OisMetadata.withGovernance(Map.of(), OisMetadata.Decision.ALLOW, List.of(),
                "http", NOW);
        assertEquals(List.of("false"), allowed.get(OisMetadata.GOVERNANCE_REDACTED));
        assertFalse(allowed.containsKey(OisMetadata.GOVERNANCE_REDACTION_CATEGORIES));
    }

    @Test
    void instanceUrisNeverCarryCredentials() {
        assertEquals("https://ecm.example.com:8443/alfresco",
                OisMetadata.instanceUri("https://admin:pw@ecm.example.com:8443/alfresco?alf_ticket=T#frag"));
        assertEquals("jdbc:postgresql://db:5432/crm",
                OisMetadata.instanceUri("jdbc:postgresql://db:5432/crm?user=sa&password=pw"));
        assertNull(OisMetadata.instanceUri("jdbc:sqlserver://db:1433;databaseName=crm;user=sa;password=pw"),
                "no parsable host: given out not at all");
        assertNull(OisMetadata.instanceUri("jdbc:oracle:thin:scott/tiger@db:1521/orcl"));
        assertEquals("file:/data/share", OisMetadata.instanceUri("file:///data/share"));
        assertNull(OisMetadata.instanceUri("not a uri"));
        assertNull(OisMetadata.instanceUri("relative/path"));
        assertNull(OisMetadata.instanceUri(null));
        assertEquals("urn:opencrawling:connector:My+Connector", OisMetadata.connectorUrn("My Connector"));
    }

    @Test
    void sourceTypesComeFromConnectorNames() {
        assertEquals("alfresco", OisMetadata.sourceType("AlfrescoConnector"));
        assertEquals("cmis", OisMetadata.sourceType("CmisRepositoryConnector"));
        assertEquals("openarch", OisMetadata.sourceType("OpenArch_Repository"));
        assertEquals("webcrawler", OisMetadata.sourceType("WebCrawler"));
        assertNull(OisMetadata.sourceType(" "));
    }

    @Test
    void namespacedIdentityTypesResolveToUsersAndGroups() {
        assertEquals("group:GROUP_LEGAL", EffectiveAcl.identityOf("GROUP_LEGAL", "cmis-group"));
        assertEquals("group:sales", EffectiveAcl.identityOf("sales", "OAUTH-GROUP"));
        assertEquals("user:bob", EffectiveAcl.identityOf("bob", "cmis-user"));
        assertEquals("user:bob", EffectiveAcl.identityOf("bob", "bpmn-candidate-user"));
        assertEquals("S-1-5-21", EffectiveAcl.identityOf("S-1-5-21", "windows-sid"), "other types stay bare");
        assertEquals("x", EffectiveAcl.identityOf("x", "-group"), "a bare suffix is no namespace");
        assertEquals("group:already", EffectiveAcl.identityOf("group:already", "cmis-group"));
    }
}
