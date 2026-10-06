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

/**
 * Extension point for the {@code NATIVE} (ofs / Ozone RPC) claim-check transport.
 * <p>
 * {@code oc-core} deliberately does not depend on the Apache Ozone client (it pulls in a large Hadoop dependency
 * tree). A module that ships the Ozone client registers a bean implementing this interface; when present,
 * {@link ClaimCheckAutoConfiguration} uses it to build the native strategy of {@link OzoneClaimCheckStore}.
 * When absent, the store falls back to the in-process {@link OzoneNativeClientStrategy}, which is only suitable
 * for single-process deployments and tests.
 * <p>
 * Implementations must not connect eagerly: the Ozone claim-check store is created even when another store type
 * is active.
 */
@FunctionalInterface
public interface OzoneNativeClaimCheckStrategyFactory {

    /**
     * Creates the native strategy for the given claim-check Ozone settings.
     */
    OzoneClientStrategy create(ClaimCheckProperties.Ozone ozoneProps);
}
