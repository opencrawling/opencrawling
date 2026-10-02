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
package org.opencrawling.doxis.output.config;

import org.junit.jupiter.api.Test;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ConflictResolution;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.ContentStrategy;
import org.opencrawling.doxis.output.config.DoxisOutputProperties.DeleteMode;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DoxisConnectorSettingsTest {

    @Test
    void parsesAdminUiConfiguration() {
        DoxisOutputProperties props = DoxisConnectorSettings.fromConfiguration(Map.ofEntries(
                Map.entry("doxisBaseUrl", "http://texter.exploredoxis.com:8080/restws/publicws/rest/api/v1"),
                Map.entry("doxisCustomerName", "faststarter"),
                Map.entry("doxisUsername", "Supervisor"),
                Map.entry("doxisPassword", "pw"),
                Map.entry("doxisRole", "admins"),
                Map.entry("doxisRepository", "D_TEXTER"),
                Map.entry("doxisAttributeMapping", "author=ObjectAuthors, lastModified=ObjectDate"),
                Map.entry("doxisContentStrategy", "predefined_locator"),
                Map.entry("doxisUploadMaxBytes", "1048576"),
                Map.entry("doxisLocatorUriPrefix", "file:///mnt/doxis-store/"),
                Map.entry("doxisConflictResolution", "UPDATE_METADATA"),
                Map.entry("doxisDeleteMode", "PHYSICAL"),
                Map.entry("doxisApplySecurityAcls", "false"),
                Map.entry("doxisFilingRecordId", "efile-4711"),
                Map.entry("doxisFilingFolderNodeId", "node-contracts"),
                Map.entry("doxisContentLinkClientLibDir", "/opt/doxis-client-lib"),
                Map.entry("doxisContentLinkUriPrefix", "file:///mnt/archive/"),
                Map.entry("doxisContentLinkPrefix", "\\\\fs\\archive\\"),
                Map.entry("doxisContentLinkType", "url"),
                Map.entry("doxisFilingMode", "source_folder"),
                Map.entry("doxisFilingRecordClass", "TX_SourceFolder"),
                Map.entry("doxisSecurityMode", "RECORD"),
                Map.entry("doxisSecurityStrict", "true"),
                Map.entry("doxisSecurityRecordAclSync", "ADDITIVE")));

        assertEquals("faststarter", props.customerName());
        assertEquals("admins", props.role());
        assertEquals("D_TEXTER", props.repository());
        assertEquals("BaseDocument", props.documentType());
        assertEquals("ObjectNumber", props.externalIdAttribute());
        assertEquals(Map.of("author", "ObjectAuthors", "lastModified", "ObjectDate"), props.attributeMapping());
        assertEquals(ContentStrategy.PREDEFINED_LOCATOR, props.content().strategy());
        assertEquals(1_048_576L, props.content().uploadMaxBytes());
        assertEquals("file:///mnt/doxis-store/", props.locator().uriPrefix());
        assertEquals("doxisLocator", props.locator().metadataKey());
        assertEquals(ConflictResolution.UPDATE_METADATA, props.conflictResolution());
        assertEquals(DeleteMode.PHYSICAL, props.deleteMode());
        assertFalse(props.applySecurityAcls());
        assertEquals("efile-4711", props.filing().recordId());
        assertEquals("node-contracts", props.filing().folderNodeId());
        assertNull(props.filing().recordRepository());
        assertEquals("doxisRecordId", props.filing().recordIdMetadataKey());
        assertEquals("/opt/doxis-client-lib", props.contentLink().clientLibDir());
        assertEquals("file:///mnt/archive/", props.contentLink().uriPrefix());
        assertEquals(org.opencrawling.doxis.output.content.ContentLinkWriter.LinkType.URL, props.contentLink().linkType());
        assertEquals("doxisContentLink", props.contentLink().metadataKey());
        assertEquals(DoxisOutputProperties.FilingMode.SOURCE_FOLDER, props.filing().mode());
        assertEquals("TX_SourceFolder", props.filing().recordClass());
        assertEquals(DoxisOutputProperties.FilingMethod.PRIMARY_PARENT, props.filing().method());
        // apply-security-acls=false (set above) wins over the mode
        assertEquals(DoxisOutputProperties.SecurityMode.NONE, props.security().mode());
        assertTrue(props.security().strict());
        assertEquals(DoxisOutputProperties.RecordAclSync.ADDITIVE, props.security().recordAclSync());
    }

    @Test
    void emptyConfigurationFallsBackToDefaults() {
        DoxisOutputProperties props = DoxisConnectorSettings.fromConfiguration(Map.of("doxisRole", ""));

        assertNull(props.role());
        assertEquals(ContentStrategy.AUTO, props.content().strategy());
        assertEquals(ContentStrategy.REFERENCE_ONLY, props.content().fallback());
        assertEquals(ConflictResolution.NEW_VERSION, props.conflictResolution());
        assertEquals(DeleteMode.LOGICAL, props.deleteMode());
        assertTrue(props.applySecurityAcls());
        assertEquals(DoxisOutputProperties.SecurityMode.DOCUMENT, props.security().mode());
        assertEquals(DoxisOutputProperties.FilingMode.METADATA, props.filing().mode());
    }
}
