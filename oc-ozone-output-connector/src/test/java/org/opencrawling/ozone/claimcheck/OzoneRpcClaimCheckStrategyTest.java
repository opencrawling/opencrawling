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
package org.opencrawling.ozone.claimcheck;

import org.apache.hadoop.fs.Syncable;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneKey;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.OzoneClaimCheckStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OzoneRpcClaimCheckStrategyTest {

    private OzoneBucket bucket;
    private OzoneRpcClaimCheckStrategy strategy;

    @BeforeEach
    void setUp() {
        bucket = mock(OzoneBucket.class);
        strategy = new OzoneRpcClaimCheckStrategy("s3v", "claims", bucket);
    }

    @Test
    void putStreamsContentWithUnknownLengthAndReturnsOfsUri() throws Exception {
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        when(bucket.createKey(anyString(), anyLong())).thenReturn(new OzoneOutputStream(written, (Syncable) null));

        byte[] content = "claim check payload".getBytes(StandardCharsets.UTF_8);
        URI uri = strategy.put("/crawl/doc 1.txt", new ByteArrayInputStream(content), -1, "text/plain");

        assertThat(uri).hasToString("ofs://s3v/claims/_crawl_doc_1.txt");
        // -1 (unknown length, as passed by the crawler) must become a 0 pre-allocation hint, not an error
        verify(bucket).createKey("_crawl_doc_1.txt", 0L);
        assertThat(written.toByteArray()).isEqualTo(content);
    }

    @Test
    void getReadsKeyFromOzone() throws Exception {
        when(bucket.readKey("_crawl_doc.txt"))
                .thenReturn(new OzoneInputStream(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))));

        try (InputStream in = strategy.get(URI.create("ofs://s3v/claims/_crawl_doc.txt"))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
    }

    @Test
    void getMissingKeyFailsInsteadOfReturningEmptyContent() throws Exception {
        when(bucket.readKey("gone.txt")).thenThrow(new OMException("missing", OMException.ResultCodes.KEY_NOT_FOUND));

        assertThatThrownBy(() -> strategy.get(URI.create("ofs://s3v/claims/gone.txt")))
                .isInstanceOf(NoSuchFileException.class)
                .hasMessageContaining("s3v/claims/gone.txt");
    }

    @Test
    void getPropagatesOtherOzoneErrors() throws Exception {
        when(bucket.readKey("doc.txt")).thenThrow(new OMException("denied", OMException.ResultCodes.PERMISSION_DENIED));

        assertThatThrownBy(() -> strategy.get(URI.create("ofs://s3v/claims/doc.txt")))
                .isInstanceOf(OMException.class);
    }

    @Test
    void rejectsUrisOfAnotherVolumeOrBucket() {
        assertThatThrownBy(() -> strategy.get(URI.create("ofs://other/claims/doc.txt")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> strategy.get(URI.create("ofs://s3v/other/doc.txt")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteIgnoresAlreadyDeletedKeys() throws Exception {
        doThrow(new OMException("missing", OMException.ResultCodes.KEY_NOT_FOUND)).when(bucket).deleteKey("doc.txt");

        strategy.delete(URI.create("ofs://s3v/claims/doc.txt"));

        verify(bucket).deleteKey("doc.txt");
    }

    @Test
    void deleteExpiredRemovesOnlyKeysOlderThanMaxAge() throws Exception {
        OzoneKey old = key("old.txt", Instant.now().minus(Duration.ofHours(2)));
        OzoneKey fresh = key("fresh.txt", Instant.now());
        when(bucket.listKeys(null)).thenAnswer(inv -> List.of(old, fresh).iterator());

        int deleted = strategy.deleteExpired(Duration.ofHours(1));

        assertThat(deleted).isEqualTo(1);
        verify(bucket).deleteKey("old.txt");
        verify(bucket, never()).deleteKey("fresh.txt");
    }

    @Test
    void supportsOnlyOfsSchemes() {
        assertThat(strategy.supports(URI.create("ofs://s3v/claims/a"))).isTrue();
        assertThat(strategy.supports(URI.create("o3fs://claims.s3v/a"))).isTrue();
        assertThat(strategy.supports(URI.create("s3://claims/a"))).isFalse();
        assertThat(strategy.supports(null)).isFalse();
    }

    @Test
    void unreachableOmFailsWithoutInMemoryFallback() {
        ClaimCheckProperties.Ozone props = new ClaimCheckProperties.Ozone();
        props.setOmHost("localhost");
        props.setOmPort(1);
        OzoneRpcClaimCheckStrategy offline = new OzoneRpcClaimCheckStrategy(props);

        assertThatThrownBy(() -> offline.put("doc", new ByteArrayInputStream(new byte[]{1}), 1, null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("not reachable");
    }

    @Test
    void factoryPlugsTheRpcStrategyIntoTheOzoneClaimCheckStore() {
        ClaimCheckProperties.Ozone props = new ClaimCheckProperties.Ozone();
        props.setClientType("NATIVE");
        OzoneRpcClaimCheckStrategyFactory factory = new OzoneRpcClaimCheckStrategyFactory();

        OzoneClaimCheckStore store = new OzoneClaimCheckStore(props, factory.create(props));

        assertThat(store.getPrimaryStrategy()).isInstanceOf(OzoneRpcClaimCheckStrategy.class);
        factory.close();
    }

    private static OzoneKey key(String name, Instant modified) {
        OzoneKey key = mock(OzoneKey.class);
        when(key.getName()).thenReturn(name);
        when(key.getModificationTime()).thenReturn(modified);
        return key;
    }
}
