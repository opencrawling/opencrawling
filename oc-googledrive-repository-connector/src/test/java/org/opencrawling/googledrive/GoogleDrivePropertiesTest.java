/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.googledrive;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleDrivePropertiesTest {

    @Test
    void defaultsMatchIssue339AndSupportAssistantRequirements() {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of());

        assertThat(props.apiBaseUrl()).isEqualTo("https://www.googleapis.com/drive/v3");
        assertThat(props.tokenUrl()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(props.authType()).isEqualTo(GoogleDriveProperties.AuthType.SERVICE_ACCOUNT);
        assertThat(props.scope()).isEqualTo("https://www.googleapis.com/auth/drive.readonly");
        assertThat(props.delegatedUser()).isEmpty();
        assertThat(props.crawlMode()).isEqualTo(GoogleDriveProperties.CrawlMode.CHANGES);
        assertThat(props.includeSubfolders()).isTrue();
        assertThat(props.includeSharedDrives()).isTrue();
        assertThat(props.followShortcuts()).isFalse();
        assertThat(props.stateFile()).isEmpty();

        // Docs exported as HTML by default (structure-aware chunking #385), Slides as plain text
        assertThat(props.exportGoogleDocs()).isTrue();
        assertThat(props.exportDocMimeType()).isEqualTo("text/html");
        assertThat(props.exportSlidesMimeType()).isEqualTo("text/plain");
        assertThat(props.exportDrawingMimeType()).isEqualTo("application/pdf");

        // Sheets and Forms excluded by default
        assertThat(props.excludedMimeTypes()).containsExactly(
                GoogleDriveProperties.MIME_GOOGLE_SHEET,
                GoogleDriveProperties.MIME_GOOGLE_FORM
        );
        assertThat(props.acceptsMimeType(GoogleDriveProperties.MIME_GOOGLE_DOC, "text/html")).isTrue();
        assertThat(props.acceptsMimeType(GoogleDriveProperties.MIME_GOOGLE_SHEET, null)).isFalse();
        assertThat(props.acceptsMimeType(GoogleDriveProperties.MIME_GOOGLE_FORM, null)).isFalse();

        assertThat(props.maxFileSizeBytes()).isEqualTo(52_428_800L);
        assertThat(props.includeAcls()).isTrue();
        assertThat(props.allowAnyonePublic()).isFalse();
        assertThat(props.effectiveRootFolderIds()).containsExactly("root");
        assertThat(props.isFolderScoped()).isFalse();
    }

    @Test
    void excludedMimeTypesCanBeExplicitlyCleared() {
        GoogleDriveProperties emptyExcl = GoogleDriveProperties.fromConfiguration(Map.of("excludedMimeTypes", ""));
        assertThat(emptyExcl.excludedMimeTypes()).isEmpty();
        assertThat(emptyExcl.acceptsMimeType(GoogleDriveProperties.MIME_GOOGLE_SHEET, null)).isTrue();

        GoogleDriveProperties noneExcl = GoogleDriveProperties.fromConfiguration(Map.of("excluded-mime-types", "none"));
        assertThat(noneExcl.excludedMimeTypes()).isEmpty();
    }

    @Test
    void folderIdsAndSharedDriveScopingWorkTogether() {
        GoogleDriveProperties multiFolder = GoogleDriveProperties.fromConfiguration(Map.of(
                "rootFolderIds", "folderA, folderB",
                "includeSubfolders", "false"
        ));
        assertThat(multiFolder.effectiveRootFolderIds()).containsExactly("folderA", "folderB");
        assertThat(multiFolder.isFolderScoped()).isTrue();

        GoogleDriveProperties wholeSharedDrive = GoogleDriveProperties.fromConfiguration(Map.of(
                "driveId", "0AO123456"
        ));
        assertThat(wholeSharedDrive.effectiveRootFolderIds()).containsExactly("0AO123456");
        assertThat(wholeSharedDrive.isFolderScoped()).isFalse();
        assertThat(wholeSharedDrive.sourceInstanceUri()).isEqualTo("https://drive.google.com/drive/folders/0AO123456");

        GoogleDriveProperties scopedSharedDrive = GoogleDriveProperties.fromConfiguration(Map.of(
                "driveId", "0AO123456",
                "rootFolderId", "1SubFolder"
        ));
        assertThat(scopedSharedDrive.effectiveRootFolderIds()).containsExactly("1SubFolder");
        assertThat(scopedSharedDrive.isFolderScoped()).isTrue();
    }

    @Test
    void fromEnvironmentReadsKebabCaseAndIndexedLists() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.opencrawling.connector.googledrive.auth-type", "oauth2")
                .withProperty("spring.opencrawling.connector.googledrive.client-id", "cid")
                .withProperty("spring.opencrawling.connector.googledrive.client-secret", "csec")
                .withProperty("spring.opencrawling.connector.googledrive.refresh-token", "rtok")
                .withProperty("spring.opencrawling.connector.googledrive.root-folder-ids[0]", "f1")
                .withProperty("spring.opencrawling.connector.googledrive.root-folder-ids[1]", "f2")
                .withProperty("spring.opencrawling.connector.googledrive.full-scan-interval", "P7D")
                .withProperty("spring.opencrawling.connector.googledrive.full-scan-window", "02:00-04:00")
                .withProperty("spring.opencrawling.connector.googledrive.full-scan-timezone", "UTC");

        GoogleDriveProperties props = GoogleDriveProperties.fromEnvironment(env);
        assertThat(props.validate()).isEmpty();
        assertThat(props.authType()).isEqualTo(GoogleDriveProperties.AuthType.OAUTH2);
        assertThat(props.effectiveRootFolderIds()).containsExactly("f1", "f2");
        assertThat(props.fullScanInterval()).isEqualTo(Duration.ofDays(7));
        assertThat(props.fullScanTimezone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void serviceAccountDoesNotRequireDomainWideDelegationAndSecretsAreNotLogged() {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "authType", "service_account",
                "serviceAccountJson", "{\"client_email\":\"sa@proj.iam.gserviceaccount.com\",\"private_key\":\"SUPER-SECRET-KEY\"}",
                "clientSecret", "TOP-SECRET-CLIENT",
                "refreshToken", "TOP-SECRET-REFRESH"
        ));

        assertThat(props.validate()).isEmpty();
        assertThat(props.toString())
                .doesNotContain("SUPER-SECRET-KEY")
                .doesNotContain("TOP-SECRET-CLIENT")
                .doesNotContain("TOP-SECRET-REFRESH");
    }

    @Test
    void fullScanDueRespectsIntervalAndWrappingWindow() {
        GoogleDriveProperties props = GoogleDriveProperties.fromConfiguration(Map.of(
                "authType", "bearer",
                "accessToken", "tok",
                "fullScanInterval", "PT24H",
                "fullScanWindow", "23:00-02:00",
                "fullScanTimezone", "UTC"
        ));
        assertThat(props.validate()).isEmpty();
        Instant last = Instant.parse("2026-11-19T23:30:00Z");
        // Elapsed > 24h, inside wrapping window
        assertThat(props.fullScanDue(last, Instant.parse("2026-11-21T01:15:00Z"))).isTrue();
        // Elapsed > 24h, outside window
        assertThat(props.fullScanDue(last, Instant.parse("2026-11-21T12:00:00Z"))).isFalse();
        // Not elapsed yet
        assertThat(props.fullScanDue(last, Instant.parse("2026-11-20T01:00:00Z"))).isFalse();
    }
}
