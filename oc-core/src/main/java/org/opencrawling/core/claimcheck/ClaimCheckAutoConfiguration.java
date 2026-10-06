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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;

/**
 * Spring Auto-Configuration for Claim Check Pattern storage.
 */
@Configuration
@EnableConfigurationProperties(ClaimCheckProperties.class)
public class ClaimCheckAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ClaimCheckAutoConfiguration.class);

    @Bean("localFileClaimCheckStore")
    @ConditionalOnMissingBean(LocalFileClaimCheckStore.class)
    public LocalFileClaimCheckStore localFileClaimCheckStore(
            ClaimCheckProperties properties,
            @Value("${opencrawling.shared.dir:#{null}}") String sharedDir) {
        String dir = properties.getLocal().getDir();
        if (sharedDir != null && !sharedDir.isBlank() && ("/data/claims".equals(dir) || "target/claims".equals(dir))) {
            dir = sharedDir + "/claims";
        }
        return new LocalFileClaimCheckStore(dir);
    }

    @Bean("ozoneClaimCheckStore")
    @ConditionalOnMissingBean(OzoneClaimCheckStore.class)
    public OzoneClaimCheckStore ozoneClaimCheckStore(ClaimCheckProperties properties,
            ObjectProvider<OzoneNativeClaimCheckStrategyFactory> nativeStrategyFactory) {
        ClaimCheckProperties.Ozone ozoneProps = properties.getOzone();
        OzoneNativeClaimCheckStrategyFactory factory = nativeStrategyFactory.getIfAvailable();
        if (factory != null) {
            return new OzoneClaimCheckStore(ozoneProps, factory.create(ozoneProps));
        }
        boolean nativeActive = "NATIVE".equalsIgnoreCase(ozoneProps.getClientType())
                && ("ozone".equalsIgnoreCase(properties.getStore()) || "s3".equalsIgnoreCase(properties.getStore()));
        if (nativeActive) {
            log.warn("Claim check uses the NATIVE Ozone transport but no Ozone RPC client is on the classpath: "
                    + "falling back to an IN-PROCESS store. Content is NOT shared across services; use "
                    + "client-type=S3 or add oc-ozone-output-connector for decoupled deployments.");
        }
        return new OzoneClaimCheckStore(ozoneProps);
    }

    @Bean("claimCheckStore")
    @Primary
    public ClaimCheckStore claimCheckStore(
            ClaimCheckProperties properties,
            LocalFileClaimCheckStore localStore,
            OzoneClaimCheckStore ozoneStore) {

        String storeType = properties.getStore();
        ClaimCheckStore primaryStore = ("ozone".equalsIgnoreCase(storeType) || "s3".equalsIgnoreCase(storeType))
                ? ozoneStore
                : localStore;

        return new CompositeClaimCheckStore(primaryStore, List.of(localStore, ozoneStore));
    }
}
