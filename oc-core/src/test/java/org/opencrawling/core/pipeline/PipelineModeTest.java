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
package org.opencrawling.core.pipeline;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PipelineModeTest {

    @Test
    void testFromStringDefaultsToRag() {
        assertEquals(PipelineMode.RAG, PipelineMode.fromString(null));
        assertEquals(PipelineMode.RAG, PipelineMode.fromString(""));
        assertEquals(PipelineMode.RAG, PipelineMode.fromString("unknown"));
        assertEquals(PipelineMode.RAG, PipelineMode.fromString("rag"));
        assertEquals(PipelineMode.RAG, PipelineMode.fromString("RAG"));
    }

    @Test
    void testFromStringMigration() {
        assertEquals(PipelineMode.MIGRATION, PipelineMode.fromString("migration"));
        assertEquals(PipelineMode.MIGRATION, PipelineMode.fromString("MIGRATION"));
        assertTrue(PipelineMode.MIGRATION.isMigration());
        assertFalse(PipelineMode.MIGRATION.isRag());
    }

    @Test
    void testPipelineProperties() {
        PipelineProperties props = new PipelineProperties();
        assertEquals(PipelineMode.RAG, props.getMode());

        props.setMode(PipelineMode.MIGRATION);
        assertEquals(PipelineMode.MIGRATION, props.getMode());

        props.setMode(null);
        assertEquals(PipelineMode.RAG, props.getMode());
    }

    @Test
    void testParseStrict() {
        assertNull(PipelineMode.parseStrict(null));
        assertNull(PipelineMode.parseStrict("  "));
        assertEquals(PipelineMode.MIGRATION, PipelineMode.parseStrict(" Migration "));
        assertEquals(PipelineMode.RAG, PipelineMode.parseStrict("rag"));
        assertThrows(IllegalArgumentException.class, () -> PipelineMode.parseStrict("migraton"));
        assertEquals("migration", PipelineMode.MIGRATION.externalName());
    }
}
