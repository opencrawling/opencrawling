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

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.Objects;

/** A {@link SidecarStore} on an S3 bucket (AWS S3, or any S3-compatible store). */
final class S3SidecarStore implements SidecarStore {

    private final S3Client client;
    private final String bucket;

    S3SidecarStore(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    @Override
    public Page list(String prefix, String startAfter, int maxKeys) {
        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder().bucket(bucket)
                .prefix(prefix != null ? prefix : "").maxKeys(Math.max(1, Math.min(maxKeys, 1000)));
        if (startAfter != null && !startAfter.isEmpty()) {
            request.startAfter(startAfter);
        }
        ListObjectsV2Response response = client.listObjectsV2(request.build());
        return new Page(response.contents().stream().map(S3Object::key).toList(), Boolean.TRUE.equals(response.isTruncated()));
    }

    @Override
    public byte[] read(String key, int maxBytes) throws Exception {
        try (ResponseInputStream<GetObjectResponse> in = client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                in.abort();
            }
            return bytes;
        } catch (NoSuchKeyException e) {
            return null;
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
