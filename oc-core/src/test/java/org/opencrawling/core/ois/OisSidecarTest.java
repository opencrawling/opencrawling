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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.security.PermissionRule;
import org.opencrawling.core.security.SecurityConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OisSidecarTest {

    @Test
    void declaredKeysAreTypedAndOthersKeepTheirLists() {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("title", List.of("Report"));
        metadata.put("author", List.of("a", "b"));
        metadata.put("lastModified", List.of("2026-01-02T03:04:05+01:00"));
        metadata.put("cmis:creationDate", List.of("2026-01-02T03:04:05"));
        metadata.put("bpmn:startDate", List.of("1767225600000"));
        metadata.put("bpmn:endDate", List.of("soon"));
        metadata.put("cmis:isLatestVersion", List.of("TRUE"));
        metadata.put("bpmn:status", List.of("RUNNING"));
        metadata.put("bpmn:variables", List.of("{a=1}"));
        metadata.put("custom", List.of("one"));
        metadata.put("empty", List.of());

        Map<String, Object> typed = OisSidecar.metadata(metadata);

        assertEquals("Report", typed.get("title"));
        assertEquals(List.of("a", "b"), typed.get("author"));
        assertEquals("2026-01-02T02:04:05Z", typed.get("lastModified"));
        assertEquals("2026-01-02T03:04:05Z", typed.get("cmis:creationDate"), "no zone: UTC");
        assertEquals("2026-01-01T00:00:00Z", typed.get("bpmn:startDate"), "epoch milliseconds");
        assertFalse(typed.containsKey("bpmn:endDate"));
        assertEquals(List.of("soon"), typed.get("bpmn:endDate" + OisSidecar.RAW_SUFFIX), "kept, not lost");
        assertEquals(Boolean.TRUE, typed.get("cmis:isLatestVersion"));
        assertEquals(List.of("RUNNING"), typed.get("bpmn:status.raw"), "not one of the schema's states");
        assertEquals(List.of("{a=1}"), typed.get("bpmn:variables.raw"), "a string list is no object");
        assertEquals(List.of("one"), typed.get("custom"));
        assertEquals(List.of(), typed.get("empty"));
    }

    @Test
    void oisBlocksBecomeNestedObjectsAndTheSourceBlockLeavesMetadata() {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put(OisMetadata.SOURCE_TYPE, List.of("cmis"));
        metadata.put(OisMetadata.SOURCE_INSTANCE, List.of("https://ecm"));
        metadata.put(OisMetadata.PROVENANCE_JOB_ID, List.of("job-1"));
        metadata.put(OisMetadata.GOVERNANCE_REDACTED, List.of("true"));
        metadata.put(OisMetadata.GOVERNANCE_REDACTION_CATEGORIES, List.of("EMAIL"));

        Map<String, Object> typed = OisSidecar.metadata(metadata);
        assertEquals(Map.of("jobId", "job-1"), typed.get(OisMetadata.PROVENANCE));
        assertFalse(typed.containsKey(OisMetadata.GOVERNANCE), "no decision, no governance block");
        assertTrue(typed.keySet().stream().noneMatch(key -> key.startsWith("ois:source")));

        metadata.put(OisMetadata.GOVERNANCE_DECISION, List.of("REDACT"));
        Map<?, ?> governance = (Map<?, ?>) OisSidecar.metadata(metadata).get(OisMetadata.GOVERNANCE);
        assertEquals("REDACT", governance.get("decision"));
        assertEquals(Boolean.TRUE, governance.get("redacted"));
        assertEquals(List.of("EMAIL"), governance.get("redactionCategories"));

        OisSidecar.Source source = OisSidecar.source(metadata, "fallback", "urn:fallback", "s3://c/1", "");
        assertEquals("cmis", source.type());
        assertEquals("https://ecm", source.instance());
        assertNull(source.originalPath());
        assertEquals("urn:fallback", OisSidecar.source(Map.of(), "t", "urn:fallback", null, null).instance());
    }

    @Test
    void jsonOmitsNullsAndTombstonesDenyEveryone() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OisSidecar upsert = OisSidecar.upsert("d", "s3://b/d", "2026-01-01T00:00:00Z",
                new OisSidecar.Source("cmis", "https://ecm", null, null, null),
                new OisSidecar.Content("text/plain", null, null, null, 1L, null, null, null, null, null, null, null),
                Map.of(), new SecurityConfig(false, List.of(new PermissionRule("g", "group", null, "read"))), " ");
        String json = mapper.writeValueAsString(upsert);
        assertFalse(json.contains("null"), json);
        assertFalse(json.contains("\"acl\""), "a blank acl is left out");
        assertTrue(json.startsWith("{\"$schema\":\"" + OisSidecar.SCHEMA_ID + "\""));
        assertTrue(json.contains("\"evaluationPolicy\":\"FAIL_CLOSED\""));

        assertEquals(List.of(), OisSidecar.upsert("d", null, null, null, null, null, null, null).security().permissions(),
                "no permissions known: nobody");

        OisSidecar tombstone = OisSidecar.tombstone("d", "s3://b/d", "2026-01-01T00:00:00Z",
                new OisSidecar.Source("cmis", "https://ecm", null, null, null));
        assertEquals("DELETE", tombstone.action());
        assertTrue(tombstone.security().permissions().isEmpty());
        assertNull(tombstone.content());
        assertEquals(tombstone, mapper.readValue(mapper.writeValueAsString(tombstone), OisSidecar.class));
    }
}
