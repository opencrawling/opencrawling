/*
 * Copyright © 2026 Piergiorgio Lucidi (plucidi@ziaconsulting.com)
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
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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

    @Test
    void aClaimReferencedUnderItsNativeNameIsKeptByTheS3GatewaySweep() throws Exception {
        // Both transports reach the bucket of the S3 volume: ofs://s3v/claims/x and s3://claims/x are one object.
        OzoneClientStrategy s3Gateway = mock(OzoneClientStrategy.class);
        OzoneClientStrategy nativeRpc = mock(OzoneClientStrategy.class);
        OzoneClaimCheckStore store = new OzoneClaimCheckStore(nativeRpc, List.of(s3Gateway, nativeRpc));
        Predicate<URI> deadLettered = URI.create("ofs://s3v/claims/doc-1_report.pdf")::equals;

        store.deleteExpired(Duration.ofHours(24), deadLettered);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<URI>> s3Retain = ArgumentCaptor.forClass(Predicate.class);
        verify(s3Gateway).deleteExpired(eq(Duration.ofHours(24)), s3Retain.capture());
        assertThat(s3Retain.getValue().test(URI.create("s3://claims/doc-1_report.pdf"))).isTrue();
        assertThat(s3Retain.getValue().test(URI.create("s3://claims/orphan.pdf"))).isFalse();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<URI>> nativeRetain = ArgumentCaptor.forClass(Predicate.class);
        verify(nativeRpc).deleteExpired(eq(Duration.ofHours(24)), nativeRetain.capture());
        assertThat(nativeRetain.getValue().test(URI.create("ofs://s3v/claims/doc-1_report.pdf"))).isTrue();
    }

    @Test
    void theOtherNamesOfAnObjectAreItsNamesOnTheOtherTransports() {
        assertThat(OzoneClaimCheckStore.aliases(URI.create("s3://claims/a.pdf"), "s3v")).containsExactlyInAnyOrder(
                URI.create("ofs://s3v/claims/a.pdf"), URI.create("o3fs://claims.s3v/a.pdf"));
        assertThat(OzoneClaimCheckStore.aliases(URI.create("ofs://s3v/claims/a.pdf"), "s3v")).containsExactlyInAnyOrder(
                URI.create("s3://claims/a.pdf"), URI.create("o3fs://claims.s3v/a.pdf"));
        assertThat(OzoneClaimCheckStore.aliases(URI.create("o3fs://claims.s3v/a.pdf"), "s3v")).containsExactlyInAnyOrder(
                URI.create("s3://claims/a.pdf"), URI.create("ofs://s3v/claims/a.pdf"));
        // Outside the S3 volume an object has no S3 name, so nothing else names it.
        assertThat(OzoneClaimCheckStore.aliases(URI.create("ofs://archive/claims/a.pdf"), "s3v")).isEmpty();
        assertThat(OzoneClaimCheckStore.aliases(URI.create("file:///data/claims/a.pdf"), "s3v")).isEmpty();
        assertThat(OzoneClaimCheckStore.aliases(URI.create("s3://claims/"), "s3v")).isEmpty();
    }

    @Test
    void aClusterWhoseS3GatewayServesAnotherVolumeUsesThatVolume() throws Exception {
        // ozone.s3g.volume.name=custom: s3://claims/doc-1.pdf is ofs://custom/claims/doc-1.pdf.
        assertThat(OzoneClaimCheckStore.aliases(URI.create("s3://claims/doc-1.pdf"), "custom")).containsExactlyInAnyOrder(
                URI.create("ofs://custom/claims/doc-1.pdf"), URI.create("o3fs://claims.custom/doc-1.pdf"));
        assertThat(OzoneClaimCheckStore.aliases(URI.create("ofs://s3v/claims/doc-1.pdf"), "custom")).isEmpty();

        OzoneClientStrategy s3Gateway = mock(OzoneClientStrategy.class);
        OzoneClientStrategy nativeRpc = mock(OzoneClientStrategy.class);
        ClaimCheckProperties.Ozone props = new ClaimCheckProperties.Ozone();
        props.setS3GatewayVolume("custom");
        OzoneClaimCheckStore store = new OzoneClaimCheckStore(nativeRpc, List.of(s3Gateway, nativeRpc), props.getS3GatewayVolume());

        store.deleteExpired(Duration.ofHours(24), URI.create("ofs://custom/claims/doc-1.pdf")::equals);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<URI>> s3Retain = ArgumentCaptor.forClass(Predicate.class);
        verify(s3Gateway).deleteExpired(eq(Duration.ofHours(24)), s3Retain.capture());
        assertThat(s3Retain.getValue().test(URI.create("s3://claims/doc-1.pdf"))).isTrue();
    }

    @Test
    void theGatewayVolumeComesFromTheSettings() {
        ClaimCheckProperties.Ozone props = new ClaimCheckProperties.Ozone();
        assertThat(props.getS3GatewayVolume()).as("Ozone's default").isEqualTo("s3v");
    }
}
