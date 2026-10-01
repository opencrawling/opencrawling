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
package org.opencrawling.doxis.output.content;

import org.opencrawling.core.document.RepositoryDocument;

import java.util.Optional;

/**
 * Derives the Doxis {@code predefinedLocator} for a binary that already resides in (or has been placed into) a Doxis data
 * store, so the document can be registered without transferring its bytes. Implementations must be cheap: they are called
 * once per document and must never read the content.
 */
@FunctionalInterface
public interface LocatorResolver {

    Optional<String> resolve(RepositoryDocument document);
}
