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
package org.opencrawling.migrator.mcf.mcf.parse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.opencrawling.migrator.mcf.mcf.model.McfFileSystemSpec;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentSpecificationParserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String FILE_CONNECTOR_CLASS =
        "org.apache.manifoldcf.crawler.connectors.filesystem.FileConnector";

    @Test
    void realWorldSpec_mixedIncludeExcludeOrder_parsesPathAndAllFilters() throws Exception {
        // Exactly this project's real "Migration From SharePoint to Alfresco" / "SharePoint drive
        // to Vespa" job specification: <startpoint path="/mnt/drive-a/files">
        //   <include match=".*\.pdf" type="file"/><exclude match=".*\.metadata\..*" type="file"/>
        //   <include match="*" type="file"/><include match="*" type="directory"/></startpoint>
        // The include/exclude/include/include ordering is mixed-type, so ManifoldCF's JSON API
        // serializes startpoint's children in the "_children_"/"_type_" alternate form.
        JsonNode documentSpecification = mapper.readTree("""
            {"startpoint": {
                "_attribute_path": "/mnt/drive-a/files",
                "_attribute_converttouri": "false",
                "_children_": [
                    {"_type_": "include", "_attribute_match": ".*\\\\.pdf", "_attribute_type": "file"},
                    {"_type_": "exclude", "_attribute_match": ".*\\\\.metadata\\\\..*", "_attribute_type": "file"},
                    {"_type_": "include", "_attribute_match": "*", "_attribute_type": "file"},
                    {"_type_": "include", "_attribute_match": "*", "_attribute_type": "directory"}
                ]
            }}
            """);

        Optional<McfFileSystemSpec> result = DocumentSpecificationParser.parse(documentSpecification, FILE_CONNECTOR_CLASS);

        assertThat(result).isPresent();
        McfFileSystemSpec spec = result.get();
        assertThat(spec.startPoints()).hasSize(1);
        McfFileSystemSpec.StartPoint startPoint = spec.startPoints().get(0);
        assertThat(startPoint.path()).isEqualTo("/mnt/drive-a/files");
        assertThat(startPoint.filters()).hasSize(4);
        assertThat(startPoint.filters()).filteredOn(McfFileSystemSpec.IncludeExcludeFilter::included).hasSize(3);
        assertThat(startPoint.filters()).filteredOn(f -> !f.included()).hasSize(1)
            .first().extracting(McfFileSystemSpec.IncludeExcludeFilter::matchPattern).isEqualTo(".*\\.metadata\\..*");
    }

    @Test
    void singleStartpoint_noFilters_collapsedForm() throws Exception {
        JsonNode documentSpecification = mapper.readTree("""
            {"startpoint": {"_attribute_path": "/data"}}
            """);

        Optional<McfFileSystemSpec> result = DocumentSpecificationParser.parse(documentSpecification, FILE_CONNECTOR_CLASS);

        assertThat(result).isPresent();
        assertThat(result.get().startPoints()).hasSize(1);
        assertThat(result.get().startPoints().get(0).path()).isEqualTo("/data");
        assertThat(result.get().startPoints().get(0).filters()).isEmpty();
    }

    @Test
    void nonFileConnectorClass_alwaysEmpty_regardlessOfSpecShape() throws Exception {
        JsonNode documentSpecification = mapper.readTree("""
            {"startpoint": {"_attribute_path": "/data"}}
            """);
        Optional<McfFileSystemSpec> result = DocumentSpecificationParser.parse(
            documentSpecification, "org.apache.manifoldcf.crawler.connectors.alfrescowebscript.AlfrescoConnector");
        assertThat(result).isEmpty();
    }

    @Test
    void nullDocumentSpecification_isEmpty() {
        assertThat(DocumentSpecificationParser.parse(null, FILE_CONNECTOR_CLASS)).isEmpty();
    }

    @Test
    void enabledocumentprocessingOnly_noStartpoint_isEmpty() throws Exception {
        // Real shape of the "HR Documents to Vespa" job's spec on the Alfresco side, included here
        // to document that an all-enabledocumentprocessing spec with no startpoint yields nothing —
        // moot in practice since the Alfresco connector never reaches this parser (class mismatch),
        // but confirms the parser doesn't crash or fabricate a start point from unrelated content.
        JsonNode documentSpecification = mapper.readTree("""
            {"enabledocumentprocessing": {"_attribute_value": "true"}}
            """);
        assertThat(DocumentSpecificationParser.parse(documentSpecification, FILE_CONNECTOR_CLASS)).isEmpty();
    }
}
