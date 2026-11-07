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
package org.opencrawling.runtime.sidecar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.ozone.client.OzoneStorageClient;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

class SidecarStoresTest {

    private static ResponseInputStream<GetObjectResponse> object(String content) {
        return new ResponseInputStream<>(GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(content.getBytes())));
    }

    @Test
    void theS3StoreListsPagesAndReadsWithinTheLimit() throws Exception {
        S3Client client = mock(S3Client.class);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("m/a.ois.json").build()).isTruncated(false).build());
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(invocation -> {
            GetObjectRequest request = invocation.getArgument(0);
            if (request.key().equals("gone")) {
                throw NoSuchKeyException.builder().message("gone").build();
            }
            return object("0123456789");
        });
        S3SidecarStore store = new S3SidecarStore(client, "lake");

        assertThat(store.list("m/", "m/0", 5000)).isEqualTo(new SidecarStore.Page(List.of("m/a.ois.json"), false));
        ArgumentCaptor<ListObjectsV2Request> request = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(client).listObjectsV2(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("lake");
        assertThat(request.getValue().startAfter()).isEqualTo("m/0");
        assertThat(request.getValue().maxKeys()).isEqualTo(1000);
        assertThat(store.read("x", 100)).hasSize(10);
        assertThat(store.read("x", 4)).hasSize(5);
        assertThat(store.read("gone", 100)).isNull();
        store.close();
        verify(client).close();
    }

    @Test
    void theOzoneStoreUsesTheClientsListingAndReads() throws Exception {
        OzoneStorageClient client = mock(OzoneStorageClient.class);
        when(client.listKeys("p/", "p/0", 1000)).thenReturn(new OzoneStorageClient.KeyPage(List.of("p/a"), true));
        when(client.getObject("p/a")).thenReturn(new ByteArrayInputStream("0123456789".getBytes()));
        when(client.getObject("missing")).thenReturn(null);
        when(client.getObject("gone")).thenThrow(NoSuchKeyException.builder().message("gone").build());
        OzoneSidecarStore store = new OzoneSidecarStore(client);

        assertThat(store.list("p/", "p/0", 5000)).isEqualTo(new SidecarStore.Page(List.of("p/a"), true));
        assertThat(store.read("p/a", 3)).hasSize(4);
        assertThat(store.read("missing", 3)).isNull();
        assertThat(store.read("gone", 3)).isNull();
        store.close();
        verify(client).close();
    }
}
