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
package org.opencrawling.doxis.output;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DoxisRecordFilerTest {

    @Test
    void sourceFolderIsTheParentOfTheDocumentUri() {
        assertEquals("file:///share/customers/4711", DoxisRecordFiler.sourceFolder("file:///share/customers/4711/contract.pdf"));
        assertEquals("s3://bucket/invoices", DoxisRecordFiler.sourceFolder("s3://bucket/invoices/a.pdf?versionId=1"));
    }

    @Test
    void filesDirectlyUnderTheRootHaveNoSourceFolder() {
        assertNull(DoxisRecordFiler.sourceFolder("file:///c.pdf"));
        assertNull(DoxisRecordFiler.sourceFolder("s3://bucket"));
        assertNull(DoxisRecordFiler.sourceFolder("c.pdf"));
        assertNull(DoxisRecordFiler.sourceFolder(null));
    }

    @Test
    void folderTitleIsTheDecodedLastSegment() {
        assertEquals("Customer 4711", DoxisRecordFiler.folderTitle("file:///share/Customer%204711"));
    }
}
