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

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneKey;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.OzoneClientStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;

/**
 * Real NATIVE (`ofs` / Ozone RPC) claim-check transport: talks to the Ozone Manager over Hadoop RPC and streams
 * blocks to/from the datanodes (Ratis gRPC), bypassing the S3 Gateway HTTP hop.
 * <p>
 * Unlike the in-process fallback in {@code oc-core}, objects are stored in the Ozone cluster, so the crawler and
 * the decoupled consumers share them. Claim-check URIs keep the existing {@code ofs://<volume>/<bucket>/<key>}
 * format. The client connects lazily on first use and never falls back to memory: failures surface as exceptions
 * so callers can retry instead of losing content.
 */
public class OzoneRpcClaimCheckStrategy implements OzoneClientStrategy, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OzoneRpcClaimCheckStrategy.class);

    private final String volume;
    private final String bucket;
    private final String omHost;
    private final int omPort;
    private final boolean autoCreateBucket;

    private OzoneClient ozoneClient;
    private volatile OzoneBucket ozoneBucket;

    public OzoneRpcClaimCheckStrategy(ClaimCheckProperties.Ozone props) {
        this.volume = props.getVolume() != null && !props.getVolume().isBlank() ? props.getVolume() : "s3v";
        this.bucket = props.getBucket() != null && !props.getBucket().isBlank() ? props.getBucket() : "claims";
        this.omHost = props.getOmHost() != null && !props.getOmHost().isBlank() ? props.getOmHost() : "localhost";
        this.omPort = props.getOmPort() > 0 ? props.getOmPort() : 9862;
        this.autoCreateBucket = props.isAutoCreateBucket();
        log.info("Configured Native Apache Ozone RPC claim-check strategy (ofs://{}:{}/{}/{}), connecting on first use",
                omHost, omPort, volume, bucket);
    }

    /** For tests: uses an already-resolved bucket instead of connecting to an Ozone Manager. */
    OzoneRpcClaimCheckStrategy(String volume, String bucket, OzoneBucket ozoneBucket) {
        this.volume = volume;
        this.bucket = bucket;
        this.omHost = "localhost";
        this.omPort = 9862;
        this.autoCreateBucket = false;
        this.ozoneBucket = ozoneBucket;
    }

    private OzoneBucket bucket() throws IOException {
        OzoneBucket b = ozoneBucket;
        if (b != null) {
            return b;
        }
        synchronized (this) {
            if (ozoneBucket == null) {
                ozoneBucket = connect();
            }
            return ozoneBucket;
        }
    }

    private OzoneBucket connect() throws IOException {
        // Fast reachability check: the Hadoop RPC client otherwise spends a long time in failover/retry loops
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(omHost, omPort), 2000);
        } catch (IOException e) {
            throw new IOException("Ozone OM is not reachable at " + omHost + ":" + omPort + " (" + e.getMessage() + ")", e);
        }

        OzoneConfiguration conf = new OzoneConfiguration();
        conf.set("ozone.om.address", omHost + ":" + omPort);
        conf.setInt("ozone.client.failover.max.attempts", 3);
        conf.setInt("ipc.client.connect.max.retries", 3);
        conf.setInt("ipc.client.connect.timeout", 5000);

        OzoneClient client = OzoneClientFactory.getRpcClient(omHost, omPort, conf);
        try {
            ObjectStore store = client.getObjectStore();
            OzoneVolume vol = getOrCreateVolume(store);
            OzoneBucket b = getOrCreateBucket(vol);
            this.ozoneClient = client;
            log.info("Connected Native Apache Ozone RPC claim-check strategy (ofs://{}:{}/{}/{})", omHost, omPort, volume, bucket);
            return b;
        } catch (IOException | RuntimeException e) {
            try {
                client.close();
            } catch (IOException ignored) {
                // best effort
            }
            throw e;
        }
    }

    private OzoneVolume getOrCreateVolume(ObjectStore store) throws IOException {
        try {
            return store.getVolume(volume);
        } catch (OMException e) {
            if (!autoCreateBucket || e.getResult() != OMException.ResultCodes.VOLUME_NOT_FOUND) {
                throw e;
            }
            try {
                store.createVolume(volume);
                log.info("Auto-created Ozone claim-check volume: {}", volume);
            } catch (OMException createEx) {
                if (createEx.getResult() != OMException.ResultCodes.VOLUME_ALREADY_EXISTS) {
                    throw createEx;
                }
            }
            return store.getVolume(volume);
        }
    }

    private OzoneBucket getOrCreateBucket(OzoneVolume vol) throws IOException {
        try {
            return vol.getBucket(bucket);
        } catch (OMException e) {
            if (!autoCreateBucket || e.getResult() != OMException.ResultCodes.BUCKET_NOT_FOUND) {
                throw e;
            }
            try {
                vol.createBucket(bucket);
                log.info("Auto-created Ozone claim-check bucket: {}/{}", volume, bucket);
            } catch (OMException createEx) {
                if (createEx.getResult() != OMException.ResultCodes.BUCKET_ALREADY_EXISTS) {
                    throw createEx;
                }
            }
            return vol.getBucket(bucket);
        }
    }

    @Override
    public URI put(String id, InputStream content, long contentLength, String contentType) throws Exception {
        String safeKey = id.replaceAll("[^a-zA-Z0-9.-]", "_");
        // The size is only a block pre-allocation hint: unknown lengths (-1, e.g. from the crawler) stream fine.
        try (OzoneOutputStream out = bucket().createKey(safeKey, Math.max(contentLength, 0))) {
            content.transferTo(out);
        }
        URI uri = URI.create("ofs://" + volume + "/" + bucket + "/" + safeKey);
        log.info("Uploaded claim check object via Native Apache Ozone RPC Client (ofs): {}", uri);
        return uri;
    }

    @Override
    public InputStream get(URI claimCheckUri) throws Exception {
        String key = keyOf(claimCheckUri);
        try {
            return bucket().readKey(key);
        } catch (OMException e) {
            if (e.getResult() == OMException.ResultCodes.KEY_NOT_FOUND) {
                throw new NoSuchFileException(claimCheckUri.toString(), null,
                        "Ozone claim check object not found (" + volume + "/" + bucket + "/" + key + ")");
            }
            throw e;
        }
    }

    @Override
    public void delete(URI claimCheckUri) throws Exception {
        String key = keyOf(claimCheckUri);
        try {
            bucket().deleteKey(key);
            log.info("Deleted claim check object via Native Apache Ozone RPC Client (ofs): {}", claimCheckUri);
        } catch (OMException e) {
            if (e.getResult() != OMException.ResultCodes.KEY_NOT_FOUND) {
                throw e;
            }
            log.debug("Claim check object already deleted: {}", claimCheckUri);
        }
    }

    @Override
    public int deleteExpired(Duration maxAge) throws Exception {
        Instant cutoff = Instant.now().minus(maxAge);
        OzoneBucket b = bucket();
        int count = 0;
        Iterator<? extends OzoneKey> keys = b.listKeys(null);
        while (keys.hasNext()) {
            OzoneKey key = keys.next();
            Instant modified = key.getModificationTime();
            if (modified != null && modified.isBefore(cutoff)) {
                try {
                    b.deleteKey(key.getName());
                    count++;
                    log.info("Garbage collector deleted expired Ozone claim check object: ofs://{}/{}/{}", volume, bucket, key.getName());
                } catch (OMException e) {
                    if (e.getResult() != OMException.ResultCodes.KEY_NOT_FOUND) {
                        throw e;
                    }
                }
            }
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

    /**
     * Extracts the key from {@code ofs://<volume>/<bucket>/<key>}. Only this strategy's volume/bucket is served:
     * a URI for another location is rejected rather than silently read from the wrong bucket.
     */
    String keyOf(URI uri) {
        if (uri == null) {
            throw new IllegalArgumentException("Claim check URI cannot be null");
        }
        String host = uri.getHost();
        String path = uri.getPath() != null ? uri.getPath() : "";
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        String uriVolume = host != null && !host.isBlank() ? host : volume;
        String uriBucket = bucket;
        String key = path;
        int slash = path.indexOf('/');
        if (slash > 0) {
            uriBucket = path.substring(0, slash);
            key = path.substring(slash + 1);
        }
        if (!volume.equals(uriVolume) || !bucket.equals(uriBucket) || key.isBlank()) {
            throw new IllegalArgumentException("Claim check URI " + uri + " does not belong to ofs://" + volume + "/" + bucket);
        }
        return key;
    }

    @Override
    public synchronized void close() {
        if (ozoneClient != null) {
            try {
                ozoneClient.close();
            } catch (IOException e) {
                log.debug("Error closing Ozone claim-check client: {}", e.getMessage());
            }
            ozoneClient = null;
            ozoneBucket = null;
        }
    }
}
