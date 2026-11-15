/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.hyland.cic.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HylandCicPropertiesTest {

    @Test
    void defaultValuesAreConsistent() {
        HylandCicProperties props = new HylandCicProperties();
        assertThat(props.getBaseUrl()).isEqualTo("https://api.content.hylandcloud.com");
        assertThat(props.getIdpTokenUrl()).isEqualTo("https://identity.hylandcloud.com/connect/token");
        assertThat(props.getClientId()).isEmpty();
        assertThat(props.getClientSecret()).isEmpty();
        assertThat(props.getScopes()).contains("hxp", "hxp.integrations", "hxp.content");
        assertThat(props.getRootFolderPath()).isEqualTo("/");
        assertThat(props.isIncludeSubfolders()).isTrue();
        assertThat(props.isIncludeAcls()).isTrue();
        assertThat(props.isIncludeContentStream()).isTrue();
        assertThat(props.getBatchSize()).isEqualTo(100);
        assertThat(props.getMaxContentSizeBytes()).isEqualTo(52428800L);
        assertThat(props.getTargetFolderPath()).isEqualTo("/Enterprise_Ingestion/Automated_Archive");
        assertThat(props.getDefaultContentType()).isEqualTo("ArchivedDocument");
        assertThat(props.getConflictResolution()).isEqualTo("create-version");
    }

    @Test
    void fromConfigurationPopulatesFields() {
        Map<String, String> config = Map.ofEntries(
                Map.entry("baseUrl", "https://custom.hxp.cloud"),
                Map.entry("idpTokenUrl", "https://idp.custom.cloud/token"),
                Map.entry("clientId", "test-client"),
                Map.entry("clientSecret", "test-secret"),
                Map.entry("scopes", "hxp custom.scope"),
                Map.entry("tenantId", "finance-corp"),
                Map.entry("rootFolderPath", "/Corporate/Finance"),
                Map.entry("rootFolderId", "f-1234"),
                Map.entry("contentTypes", "Invoice, TaxForm"),
                Map.entry("includeSubfolders", "false"),
                Map.entry("excludedFolderPaths", "/Corporate/Finance/Archive, /Corporate/Finance/Trash"),
                Map.entry("query", "name eq 'Q4'"),
                Map.entry("batchSize", "50"),
                Map.entry("includeAcls", "false"),
                Map.entry("includeContentStream", "false"),
                Map.entry("maxContentSizeBytes", "10485760"),
                Map.entry("connectionTimeoutSeconds", "45"),
                Map.entry("maxRetries", "3"),
                Map.entry("targetFolderPath", "/Archived"),
                Map.entry("targetFolderId", "target-456"),
                Map.entry("defaultContentType", "FinancialArchive"),
                Map.entry("conflictResolution", "overwrite")
        );

        HylandCicProperties props = HylandCicProperties.fromConfiguration(config);

        assertThat(props.getBaseUrl()).isEqualTo("https://custom.hxp.cloud");
        assertThat(props.getIdpTokenUrl()).isEqualTo("https://idp.custom.cloud/token");
        assertThat(props.getClientId()).isEqualTo("test-client");
        assertThat(props.getClientSecret()).isEqualTo("test-secret");
        assertThat(props.getScopes()).containsExactly("hxp", "custom.scope");
        assertThat(props.getTenantId()).isEqualTo("finance-corp");
        assertThat(props.getRootFolderPath()).isEqualTo("/Corporate/Finance");
        assertThat(props.getRootFolderId()).isEqualTo("f-1234");
        assertThat(props.getContentTypes()).containsExactly("Invoice", "TaxForm");
        assertThat(props.isIncludeSubfolders()).isFalse();
        assertThat(props.getExcludedFolderPaths()).containsExactlyInAnyOrder("/Corporate/Finance/Archive", "/Corporate/Finance/Trash");
        assertThat(props.getQuery()).isEqualTo("name eq 'Q4'");
        assertThat(props.getBatchSize()).isEqualTo(50);
        assertThat(props.isIncludeAcls()).isFalse();
        assertThat(props.isIncludeContentStream()).isFalse();
        assertThat(props.getMaxContentSizeBytes()).isEqualTo(10485760L);
        assertThat(props.getConnectionTimeoutSeconds()).isEqualTo(45);
        assertThat(props.getMaxRetries()).isEqualTo(3);
        assertThat(props.getTargetFolderPath()).isEqualTo("/Archived");
        assertThat(props.getTargetFolderId()).isEqualTo("target-456");
        assertThat(props.getDefaultContentType()).isEqualTo("FinancialArchive");
        assertThat(props.getConflictResolution()).isEqualTo("overwrite");
    }

    @Test
    void fromEnvironmentReadsSpringProperties() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.opencrawling.connector.hyland-cic.base-url", "https://env.hxp.cloud");
        env.setProperty("spring.opencrawling.connector.hyland-cic.client-id", "env-client");
        env.setProperty("spring.opencrawling.connector.hyland-cic.tenant-id", "env-tenant");
        env.setProperty("spring.opencrawling.connector.hyland-cic.batch-size", "25");

        HylandCicProperties props = HylandCicProperties.fromEnvironment(env);
        assertThat(props.getBaseUrl()).isEqualTo("https://env.hxp.cloud");
        assertThat(props.getClientId()).isEqualTo("env-client");
        assertThat(props.getTenantId()).isEqualTo("env-tenant");
        assertThat(props.getBatchSize()).isEqualTo(25);
    }
}
