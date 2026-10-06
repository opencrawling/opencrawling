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

import jakarta.annotation.PreDestroy;
import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.OzoneClientStrategy;
import org.opencrawling.core.claimcheck.OzoneNativeClaimCheckStrategyFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Provides the real Ozone RPC client for the NATIVE claim-check transport to {@code oc-core}'s
 * {@code ClaimCheckAutoConfiguration}, replacing the in-process fallback whenever this module is on the classpath.
 */
@Component
public class OzoneRpcClaimCheckStrategyFactory implements OzoneNativeClaimCheckStrategyFactory {

    private final List<OzoneRpcClaimCheckStrategy> created = new CopyOnWriteArrayList<>();

    @Override
    public OzoneClientStrategy create(ClaimCheckProperties.Ozone ozoneProps) {
        OzoneRpcClaimCheckStrategy strategy = new OzoneRpcClaimCheckStrategy(ozoneProps);
        created.add(strategy);
        return strategy;
    }

    @PreDestroy
    public void close() {
        created.forEach(OzoneRpcClaimCheckStrategy::close);
    }
}
