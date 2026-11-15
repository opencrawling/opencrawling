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

import org.opencrawling.ozone.client.OzoneStorageClient;

import java.io.InputStream;
import java.util.Objects;

/** A {@link SidecarStore} on an Apache Ozone bucket, through the native client or the S3 gateway. */
final class OzoneSidecarStore implements SidecarStore {

    private final OzoneStorageClient client;

    OzoneSidecarStore(OzoneStorageClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Page list(String prefix, String startAfter, int maxKeys) throws Exception {
        OzoneStorageClient.KeyPage page = client.listKeys(prefix, startAfter, Math.max(1, Math.min(maxKeys, 1000)));
        return new Page(page.keys(), page.truncated());
    }

    @Override
    public byte[] read(String key, int maxBytes) throws Exception {
        try (InputStream in = client.getObject(key)) {
            return in != null ? in.readNBytes(maxBytes + 1) : null;
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
            // The S3 gateway: the key went away after it was listed.
            return null;
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
            // nothing more to do
        }
    }
}
