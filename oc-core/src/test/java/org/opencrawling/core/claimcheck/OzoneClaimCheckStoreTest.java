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
package org.opencrawling.core.claimcheck;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OzoneClaimCheckStoreTest {

    @Test
    void testSupportsUriSchemes() {
        ClaimCheckProperties.Ozone ozoneProps = new ClaimCheckProperties.Ozone();
        ozoneProps.setAutoCreateBucket(false);

        // Verify supported URI schemes without calling remote service
        OzoneClaimCheckStore ozoneStore = new OzoneClaimCheckStore(
                null,
                ozoneProps.getBucket(),
                false
        );

        assertThat(ozoneStore.supports(URI.create("s3://claims/doc-123.pdf"))).isTrue();
        assertThat(ozoneStore.supports(URI.create("ofs://s3v/claims/doc-123.pdf"))).isTrue();
        assertThat(ozoneStore.supports(URI.create("o3fs://s3v/claims/doc-123.pdf"))).isTrue();
        assertThat(ozoneStore.supports(URI.create("file:///data/claims/doc-123.pdf"))).isFalse();
        assertThat(ozoneStore.supports(null)).isFalse();
    }

    @Test
    void testNativeOzoneClientStrategyOperations() throws Exception {
        ClaimCheckProperties.Ozone ozoneProps = new ClaimCheckProperties.Ozone();
        ozoneProps.setClientType("NATIVE");
        ozoneProps.setOmHost("localhost");
        ozoneProps.setOmPort(9862);
        ozoneProps.setVolume("s3v");
        ozoneProps.setBucket("claims");

        OzoneClaimCheckStore ozoneStore = new OzoneClaimCheckStore(ozoneProps);

        assertThat(ozoneStore.getPrimaryStrategy()).isInstanceOf(OzoneNativeClientStrategy.class);

        String text = "High-performance native Ozone claim check content";
        ByteArrayInputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));

        URI storedUri = ozoneStore.put("native-doc-1", is, text.length(), "text/plain");

        assertThat(storedUri).isNotNull();
        assertThat(storedUri.getScheme()).isEqualTo("ofs");
        assertThat(storedUri.toString()).contains("s3v/claims/native-doc-1");

        InputStream retrievedStream = ozoneStore.get(storedUri);
        String retrievedText = new String(retrievedStream.readAllBytes(), StandardCharsets.UTF_8);

        assertThat(retrievedText).isEqualTo(text);

        ozoneStore.delete(storedUri);
        // A missing object must fail loudly, never yield empty content (which would be written as a 0-byte document)
        assertThatThrownBy(() -> ozoneStore.get(storedUri))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("native-doc-1");
    }

    @Test
    void testNativeStrategyFailsForObjectWrittenByAnotherProcess() {
        ClaimCheckProperties.Ozone ozoneProps = new ClaimCheckProperties.Ozone();
        ozoneProps.setClientType("NATIVE");
        // Simulates the decoupled writer reading a claim check URI produced by the crawler in another JVM
        OzoneClaimCheckStore writerSideStore = new OzoneClaimCheckStore(ozoneProps);

        assertThatThrownBy(() -> writerSideStore.get(URI.create("ofs://s3v/claims/_crawl_doc.txt_doc.txt")))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("in-process only");
    }
}
