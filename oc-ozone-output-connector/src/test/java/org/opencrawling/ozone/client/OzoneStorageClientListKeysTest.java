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
package org.opencrawling.ozone.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.opencrawling.ozone.config.OzoneOutputProperties;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

/** Listing keys for the OIS sidecar reader, on both Ozone clients. */
class OzoneStorageClientListKeysTest {

    @Test
    void theNativeClientListsInKeyOrderAfterTheGivenKey() throws Exception {
        OzoneOutputProperties properties = new OzoneOutputProperties();
        properties.setOmPort(1); // nothing listens here -> in-memory fallback
        properties.setAllowInMemoryFallback(true);
        OzoneNativeStorageClient client = new OzoneNativeStorageClient(properties);
        client.connect();
        for (String key : List.of("b/2.ois.json", "a/1.ois.json", "b/1.ois.json", "b/3", "c/1.ois.json")) {
            client.putText(key, "{}", "application/json");
        }

        assertThat(client.listKeys("b/", null, 10)).isEqualTo(new OzoneStorageClient.KeyPage(List.of("b/1.ois.json", "b/2.ois.json", "b/3"), false));
        assertThat(client.listKeys("b/", null, 2)).isEqualTo(new OzoneStorageClient.KeyPage(List.of("b/1.ois.json", "b/2.ois.json"), true));
        assertThat(client.listKeys("b/", "b/2.ois.json", 2)).isEqualTo(new OzoneStorageClient.KeyPage(List.of("b/3"), false));
        assertThat(client.listKeys("", "b/3", 5).keys()).containsExactly("c/1.ois.json");
        assertThat(client.listKeys("z/", null, 5)).isEqualTo(new OzoneStorageClient.KeyPage(List.of(), false));
    }

    @Test
    void theGatewayClientListsThroughS3() {
        S3Client s3 = mock(S3Client.class);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("p/a.ois.json").build(), S3Object.builder().key("p/b.ois.json").build())
                .isTruncated(true).build());
        OzoneS3GatewayStorageClient client = new OzoneS3GatewayStorageClient(s3, "migration", false);

        OzoneStorageClient.KeyPage page = client.listKeys("p/", "p/0", 2);

        assertThat(page).isEqualTo(new OzoneStorageClient.KeyPage(List.of("p/a.ois.json", "p/b.ois.json"), true));
        ArgumentCaptor<ListObjectsV2Request> request = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo("migration");
        assertThat(request.getValue().prefix()).isEqualTo("p/");
        assertThat(request.getValue().startAfter()).isEqualTo("p/0");
        assertThat(request.getValue().maxKeys()).isEqualTo(2);
    }
}
