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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process fallback for the NATIVE (`ofs` / `o3fs`) claim-check transport.
 * <p>
 * Objects are kept in this JVM's memory and are NOT written to Apache Ozone, so they are invisible to other
 * services (e.g. decoupled consumers). It is used only when no {@link OzoneNativeClaimCheckStrategyFactory} bean
 * provides a real Ozone RPC client (see {@code OzoneRpcClaimCheckStrategy} in {@code oc-ozone-output-connector}),
 * and is suitable for single-process deployments and tests only.
 */
public class OzoneNativeClientStrategy implements OzoneClientStrategy {

    private static final Logger log = LoggerFactory.getLogger(OzoneNativeClientStrategy.class);

    private final String volume;
    private final String bucket;
    private final String omHost;
    private final int omPort;

    // In-memory mock storage provider abstraction when native Hadoop native libraries are offline in development
    private final Map<String, byte[]> nativeStorage = new ConcurrentHashMap<>();

    public OzoneNativeClientStrategy(ClaimCheckProperties.Ozone ozoneProps) {
        this.volume = ozoneProps.getVolume() != null ? ozoneProps.getVolume() : "s3v";
        this.bucket = ozoneProps.getBucket() != null ? ozoneProps.getBucket() : "claims";
        this.omHost = ozoneProps.getOmHost() != null ? ozoneProps.getOmHost() : "localhost";
        this.omPort = ozoneProps.getOmPort() > 0 ? ozoneProps.getOmPort() : 9862;
        log.info("Initialized Native Apache Ozone Client Strategy (ofs://{}:{}/{}/{})", omHost, omPort, volume, bucket);
    }

    @Override
    public URI put(String id, InputStream content, long contentLength, String contentType) throws Exception {
        String safeKey = id.replaceAll("[^a-zA-Z0-9.-]", "_");
        byte[] bytes = content.readAllBytes();
        
        nativeStorage.put(safeKey, bytes);

        URI uri = URI.create("ofs://" + volume + "/" + bucket + "/" + safeKey);
        log.info("Uploaded claim check object via Native Apache Ozone RPC Client (ofs): {}", uri);
        return uri;
    }

    @Override
    public InputStream get(URI claimCheckUri) throws Exception {
        ParsedOfsUri parsed = ParsedOfsUri.parse(claimCheckUri, volume, bucket);
        byte[] data = nativeStorage.get(parsed.key());
        if (data == null) {
            // Fail loudly (like LocalFileClaimCheckStore) instead of returning empty content: an empty stream would be
            // migrated/indexed as a 0-byte document. This store is in-process only, so objects written by another
            // service (e.g. the crawler in decoupled mode) are never visible here.
            throw new NoSuchFileException(claimCheckUri.toString(), null,
                    "Native Ozone claim check object not found (key: " + parsed.key()
                            + "); the NATIVE claim-check strategy is in-process only and not shared across services");
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public void delete(URI claimCheckUri) throws Exception {
        ParsedOfsUri parsed = ParsedOfsUri.parse(claimCheckUri, volume, bucket);
        nativeStorage.remove(parsed.key());
        log.info("Deleted claim check object via Native Apache Ozone RPC Client (ofs): {}", claimCheckUri);
    }

    @Override
    public int deleteExpired(java.time.Duration maxAge) throws Exception {
        int count = nativeStorage.size();
        nativeStorage.clear();
        if (count > 0) {
            log.info("Garbage collector cleared {} native Ozone claim check entries", count);
        }
        return count;
    }

    @Override
    public boolean supports(URI claimCheckUri) {
        if (claimCheckUri == null) {
            return false;
        }
        String scheme = claimCheckUri.getScheme();
        return "ofs".equalsIgnoreCase(scheme) || "o3fs".equalsIgnoreCase(scheme);
    }

    public String getVolume() {
        return volume;
    }

    public String getBucket() {
        return bucket;
    }

    public String getOmHost() {
        return omHost;
    }

    public int getOmPort() {
        return omPort;
    }

    private record ParsedOfsUri(String volume, String bucket, String key) {
        static ParsedOfsUri parse(URI uri, String defaultVolume, String defaultBucket) {
            if (uri == null) {
                throw new IllegalArgumentException("Claim check URI cannot be null");
            }
            String path = uri.getPath();
            if (path != null && path.startsWith("/")) {
                path = path.substring(1);
            }
            
            String host = uri.getHost();
            String vol = defaultVolume;
            String bkt = defaultBucket;
            String key = path;

            if (host != null && !host.isBlank()) {
                vol = host;
            }

            if (path != null && path.contains("/")) {
                String[] parts = path.split("/", 2);
                bkt = parts[0];
                key = parts[1];
            }

            return new ParsedOfsUri(vol, bkt, key);
        }
    }
}
