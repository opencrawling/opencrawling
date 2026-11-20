/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
import software.amazon.awssdk.services.s3.S3Client;

import java.io.InputStream;
import java.net.URI;
import java.util.List;

/**
 * Composite Apache Ozone ClaimCheckStore implementation supporting dual client strategies:
 * 1. S3 Gateway (`s3g` / HTTP) via AWS S3 SDK for maximum versatility & cloud compatibility.
 * 2. Native RPC Client (`ofs` / `o3fs`) via direct Ozone RPC connection for high performance.
 */
public class OzoneClaimCheckStore implements ClaimCheckStore {

    private static final Logger log = LoggerFactory.getLogger(OzoneClaimCheckStore.class);

    private final OzoneClientStrategy primaryStrategy;
    private final List<OzoneClientStrategy> strategies;
    /** The volume the S3 gateway serves; see {@link ClaimCheckProperties.Ozone#getS3GatewayVolume()}. */
    private final String s3GatewayVolume;

    public OzoneClaimCheckStore(ClaimCheckProperties.Ozone ozoneProps) {
        this(ozoneProps, new OzoneNativeClientStrategy(ozoneProps));
    }

    /**
     * @param nativeStrategy strategy for the NATIVE (ofs) transport, e.g. a real Ozone RPC client provided through
     *                       {@link OzoneNativeClaimCheckStrategyFactory}, or the in-process {@link OzoneNativeClientStrategy}
     */
    public OzoneClaimCheckStore(ClaimCheckProperties.Ozone ozoneProps, OzoneClientStrategy nativeStrategy) {
        OzoneS3GatewayClientStrategy s3Strategy = new OzoneS3GatewayClientStrategy(ozoneProps);

        if ("NATIVE".equalsIgnoreCase(ozoneProps.getClientType())) {
            this.primaryStrategy = nativeStrategy;
            log.info("OzoneClaimCheckStore configured with Primary Strategy: NATIVE (ofs/o3fs RPC) via {}",
                    nativeStrategy.getClass().getSimpleName());
        } else {
            this.primaryStrategy = s3Strategy;
            log.info("OzoneClaimCheckStore configured with Primary Strategy: S3 Gateway (s3/s3g HTTP)");
        }

        this.strategies = List.of(s3Strategy, nativeStrategy);
        this.s3GatewayVolume = gatewayVolume(ozoneProps.getS3GatewayVolume());
    }

    public OzoneClaimCheckStore(S3Client s3Client, String bucket, boolean autoCreateBucket) {
        OzoneS3GatewayClientStrategy s3Strategy = new OzoneS3GatewayClientStrategy(s3Client, bucket, autoCreateBucket);
        ClaimCheckProperties.Ozone defaultProps = new ClaimCheckProperties.Ozone();
        defaultProps.setBucket(bucket);
        defaultProps.setClientType("NATIVE");
        OzoneNativeClientStrategy nativeStrategy = new OzoneNativeClientStrategy(defaultProps);

        this.primaryStrategy = nativeStrategy;
        this.strategies = List.of(nativeStrategy, s3Strategy);
        this.s3GatewayVolume = gatewayVolume(defaultProps.getS3GatewayVolume());
    }

    public OzoneClaimCheckStore(OzoneClientStrategy primaryStrategy, List<OzoneClientStrategy> strategies) {
        this(primaryStrategy, strategies, DEFAULT_S3_GATEWAY_VOLUME);
    }

    public OzoneClaimCheckStore(OzoneClientStrategy primaryStrategy, List<OzoneClientStrategy> strategies,
                                String s3GatewayVolume) {
        this.primaryStrategy = primaryStrategy;
        this.strategies = strategies != null ? strategies : List.of(primaryStrategy);
        this.s3GatewayVolume = gatewayVolume(s3GatewayVolume);
    }

    private static String gatewayVolume(String configured) {
        return configured != null && !configured.isBlank() ? configured : DEFAULT_S3_GATEWAY_VOLUME;
    }

    @Override
    public URI put(String id, InputStream content, long contentLength, String contentType) throws Exception {
        return primaryStrategy.put(id, content, contentLength, contentType);
    }

    @Override
    public InputStream get(URI claimCheckUri) throws Exception {
        for (OzoneClientStrategy strategy : strategies) {
            if (strategy.supports(claimCheckUri)) {
                return strategy.get(claimCheckUri);
            }
        }
        return primaryStrategy.get(claimCheckUri);
    }

    @Override
    public void delete(URI claimCheckUri) throws Exception {
        for (OzoneClientStrategy strategy : strategies) {
            if (strategy.supports(claimCheckUri)) {
                strategy.delete(claimCheckUri);
                return;
            }
        }
        primaryStrategy.delete(claimCheckUri);
    }

    @Override
    public int deleteExpired(java.time.Duration maxAge) throws Exception {
        return deleteExpired(maxAge, uri -> false);
    }

    /**
     * Sweeps every transport, keeping what {@code retain} keeps under any of an object's names: the S3 gateway and
     * the native client can reach the same object, and a claim referenced under one name must not be deleted by
     * the sweep of the other.
     */
    @Override
    public int deleteExpired(java.time.Duration maxAge, java.util.function.Predicate<URI> retain) throws Exception {
        java.util.function.Predicate<URI> retainUnderAnyName = acrossTransports(retain, s3GatewayVolume);
        int total = 0;
        for (OzoneClientStrategy strategy : strategies) {
            try {
                total += strategy.deleteExpired(maxAge, retainUnderAnyName);
            } catch (Exception e) {
                log.warn("Failed deleteExpired sweep for strategy {}: {}", strategy.getClass().getSimpleName(), e.getMessage());
            }
        }
        return total;
    }

    @Override
    public boolean supports(URI claimCheckUri) {
        if (claimCheckUri == null) {
            return false;
        }
        return strategies.stream().anyMatch(s -> s.supports(claimCheckUri));
    }

    /** Ozone's default {@code ozone.s3g.volume.name}. */
    static final String DEFAULT_S3_GATEWAY_VOLUME = "s3v";

    /** {@code retain}, also applied to the other names of the same object ({@link #aliases}). */
    static java.util.function.Predicate<URI> acrossTransports(java.util.function.Predicate<URI> retain,
                                                              String s3GatewayVolume) {
        return uri -> retain.test(uri) || aliases(uri, s3GatewayVolume).stream().anyMatch(retain);
    }

    /**
     * The other names of the object {@code uri} names, where {@code s3GatewayVolume} is the volume the S3 gateway
     * serves: {@code s3://<bucket>/<key>} is {@code ofs://<s3GatewayVolume>/<bucket>/<key>} and
     * {@code o3fs://<bucket>.<s3GatewayVolume>/<key>}, and the other way round. An {@code ofs}/{@code o3fs} object
     * in another volume has no S3 name.
     */
    static List<URI> aliases(URI uri, String s3GatewayVolume) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return List.of();
        }
        String path = uri.getPath() != null ? uri.getPath().replaceFirst("^/+", "") : "";
        String bucket;
        String key;
        String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        switch (scheme) {
            case "s3", "s3a" -> {
                bucket = uri.getHost();
                key = path;
            }
            case "ofs" -> {
                int slash = path.indexOf('/');
                if (!s3GatewayVolume.equals(uri.getHost()) || slash <= 0) {
                    return List.of();
                }
                bucket = path.substring(0, slash);
                key = path.substring(slash + 1);
            }
            case "o3fs" -> {
                int dot = uri.getHost().indexOf('.');
                if (dot <= 0 || !s3GatewayVolume.equals(uri.getHost().substring(dot + 1))) {
                    return List.of();
                }
                bucket = uri.getHost().substring(0, dot);
                key = path;
            }
            default -> {
                return List.of();
            }
        }
        if (bucket.isBlank() || key.isBlank()) {
            return List.of();
        }
        return java.util.stream.Stream.of(
                        "s3://" + bucket + "/" + key,
                        "ofs://" + s3GatewayVolume + "/" + bucket + "/" + key,
                        "o3fs://" + bucket + "." + s3GatewayVolume + "/" + key)
                .map(URI::create)
                .filter(alias -> !alias.getScheme().equalsIgnoreCase(scheme))
                .toList();
    }

    public OzoneClientStrategy getPrimaryStrategy() {
        return primaryStrategy;
    }
}
