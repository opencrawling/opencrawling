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
    }

    public OzoneClaimCheckStore(S3Client s3Client, String bucket, boolean autoCreateBucket) {
        OzoneS3GatewayClientStrategy s3Strategy = new OzoneS3GatewayClientStrategy(s3Client, bucket, autoCreateBucket);
        ClaimCheckProperties.Ozone defaultProps = new ClaimCheckProperties.Ozone();
        defaultProps.setBucket(bucket);
        defaultProps.setClientType("NATIVE");
        OzoneNativeClientStrategy nativeStrategy = new OzoneNativeClientStrategy(defaultProps);

        this.primaryStrategy = nativeStrategy;
        this.strategies = List.of(nativeStrategy, s3Strategy);
    }

    public OzoneClaimCheckStore(OzoneClientStrategy primaryStrategy, List<OzoneClientStrategy> strategies) {
        this.primaryStrategy = primaryStrategy;
        this.strategies = strategies != null ? strategies : List.of(primaryStrategy);
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
        int total = 0;
        for (OzoneClientStrategy strategy : strategies) {
            try {
                total += strategy.deleteExpired(maxAge);
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

    public OzoneClientStrategy getPrimaryStrategy() {
        return primaryStrategy;
    }
}
