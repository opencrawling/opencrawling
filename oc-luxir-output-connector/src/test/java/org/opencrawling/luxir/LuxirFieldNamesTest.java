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
package org.opencrawling.luxir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LuxirFieldNamesTest {

    /** Luxir's own rule (Schema::validFieldName): a letter first, then letters, digits and '_', no "__", <= 127. */
    private static final Pattern LUXIR_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,126}");

    private static void assertValid(String name) {
        assertTrue(LUXIR_FIELD.matcher(name).matches() && !name.contains("__"), "not a valid Luxir field: " + name);
    }

    @Test
    void namespacedAndDottedKeysBecomeValidFieldNames() {
        assertEquals("ois_source_connectorVersion_s", LuxirFieldNames.forMetadata("ois:source.connectorVersion", "1.0"));
        assertEquals("ois_provenance_jobId_ss", LuxirFieldNames.forMetadata("ois:provenance.jobId", List.of("j1")));
        assertEquals("cmis_objectId_s", LuxirFieldNames.forMetadata("cmis:objectId", "abc"));
        assertEquals("x_content_type_s", LuxirFieldNames.forMetadata("x-content--type", "a"));
    }

    @Test
    void plainKeysAndTypedSuffixesAreKept() {
        assertEquals("custom_tags_ss", LuxirFieldNames.forMetadata("custom_tags", List.of("a", "b")));
        assertEquals("custom_tags_ss", LuxirFieldNames.forMetadata("custom_tags", new String[] {"a"}));
        assertEquals("author_s", LuxirFieldNames.forMetadata("author", "x"));
        assertEquals("summary_t", LuxirFieldNames.forMetadata("summary_t", "x"));
        assertEquals("labels_ss", LuxirFieldNames.forMetadata("labels_ss", List.of("x")));
    }

    @Test
    void namesThatCannotStartOrAreTooLongAreFixed() {
        assertEquals("f_2fa_s", LuxirFieldNames.forMetadata("2fa", "x"));
        assertEquals("ois_s", LuxirFieldNames.forMetadata("_ois", "x"));
        assertEquals("f_s", LuxirFieldNames.forMetadata(":::", "x"));
        assertEquals("f_ss", LuxirFieldNames.forMetadata("é", List.of("x")));
        String longName = LuxirFieldNames.forMetadata("a".repeat(130) + ".b", List.of("x"));
        assertEquals(127, longName.length());
        assertTrue(longName.endsWith("_ss"));
        for (String key : List.of("ois:governance.redactionCategories", "a.__.b", "é:ü", "a".repeat(300), "_s", "9_t")) {
            assertValid(LuxirFieldNames.forMetadata(key, "x"));
            assertValid(LuxirFieldNames.forMetadata(key, List.of("x")));
        }
    }
}
