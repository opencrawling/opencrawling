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
package org.opencrawling.filesystem;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opencrawling.core.document.RepositoryDocument;

import reactor.test.StepVerifier;

class FileSystemRepositoryConnectorTest {

    @TempDir
    Path tempDir;

    private FileSystemRepositoryConnector connector;

    @BeforeEach
    void setUp() {
        connector = new FileSystemRepositoryConnector();
    }

    @Test
    void testScanExtractsCollocatedMetadataAndSecurity() throws IOException {
        Path subDir = Files.createDirectory(tempDir.resolve("subfolder"));
        Path testFile = subDir.resolve("test-document.txt");
        Files.writeString(testFile, "Hello OpenCrawling Zero-Trust Filesystem!", StandardCharsets.UTF_8);

        StepVerifier.create(connector.scan(tempDir.toString()))
                .assertNext(doc -> {
                    assertThat(doc.id()).isEqualTo(testFile.toAbsolutePath().toString());
                    assertThat(doc.uri()).isEqualTo(testFile.toUri().toString());

                    // Collocated entity references
                    assertThat(doc.metadata()).containsKey("file_name");
                    assertThat(doc.metadata().get("file_name")).containsExactly("test-document.txt");
                    assertThat(doc.metadata()).containsKey("file_path");
                    assertThat(doc.metadata().get("file_path")).containsExactly(testFile.toAbsolutePath().toString());
                    assertThat(doc.metadata()).containsKey("file_size");
                    assertThat(doc.metadata()).containsKey("extension");
                    assertThat(doc.metadata().get("extension")).containsExactly("txt");

                    // Zero-trust security config
                    assertThat(doc.security()).isNotNull();
                    assertThat(doc.security().permissions()).isNotEmpty();
                    assertThat(doc.security().permissions())
                            .anyMatch(rule -> "read".equalsIgnoreCase(rule.access()));
                })
                .verifyComplete();
    }

    @Test
    void testScanWithoutAcls() throws IOException {
        FileSystemRepositoryConnector noAclConnector = new FileSystemRepositoryConnector(false);
        Path testFile = tempDir.resolve("public-doc.txt");
        Files.writeString(testFile, "Public content", StandardCharsets.UTF_8);

        StepVerifier.create(noAclConnector.scan(tempDir.toString()))
                .assertNext(doc -> {
                    assertThat(doc.security()).isNotNull();
                    assertThat(doc.acl()).isEqualTo("public");
                    assertThat(doc.metadata()).doesNotContainKey("file_identity_users");
                    assertThat(doc.metadata()).doesNotContainKey("file_identity_groups");
                })
                .verifyComplete();
    }

    @Test
    void testConnectorLifecycle() throws Exception {
        assertThat(connector.getName()).isEqualTo("FileSystemConnector");
        assertThat(connector.isIncludeAcls()).isTrue();
        connector.connect();
        connector.disconnect();
    }
}
