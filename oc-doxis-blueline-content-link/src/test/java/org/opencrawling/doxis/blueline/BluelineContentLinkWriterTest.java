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
package org.opencrawling.doxis.blueline;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class BluelineContentLinkWriterTest {

    @Test
    void extractsRestUuidFromCompoundBluelineId() {
        assertEquals("08f0e13d-0ce0-44ac-a4aa-06ca98016e0a",
                BluelineContentLinkWriter.documentUuid("SD08D_TEXTER2408f0e13d-0ce0-44ac-a4aa-06ca98016e0a182026-10-02T14:15:36.166Z011"));
        assertThrows(IllegalStateException.class, () -> BluelineContentLinkWriter.documentUuid("no-uuid-here"));
    }

    @Test
    void convertsRestStyleValuesToBluelineTypes() {
        assertEquals(new Date(1790949717379L), BluelineContentLinkWriter.typed("DATE", 1790949717379L));
        assertEquals("abc", BluelineContentLinkWriter.typed("STRING", "abc"));
        assertEquals(42, BluelineContentLinkWriter.typed("INTEGER", 42));
    }

    @Test
    void writerIsDiscoverableThroughServiceLoaderFile() throws Exception {
        try (var in = getClass().getResourceAsStream("/META-INF/services/org.opencrawling.doxis.output.content.ContentLinkWriter")) {
            assertNotNull(in);
            assertEquals(BluelineContentLinkWriter.class.getName(), new String(in.readAllBytes()).strip());
        }
    }
}
