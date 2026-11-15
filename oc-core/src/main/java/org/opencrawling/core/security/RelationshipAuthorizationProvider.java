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
package org.opencrawling.core.security;

/**
 * An {@link AuthorizationProvider} that decides from a relationship store (for example OpenFGA) rather than
 * from the ACL metadata stored with each document. The runtime combines it with the metadata provider
 * according to {@code opencrawling.security.authorization.mode} ({@code shadow}, {@code intersection} or
 * {@code openfga}).
 */
public interface RelationshipAuthorizationProvider extends AuthorizationProvider {

    @Override
    default boolean metadataPrefilterSafe() {
        return false;
    }
}
