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
package org.opencrawling.authorization.openfga;

/**
 * One relationship tuple on a document: {@code <object>#<relation>@<user>}.
 *
 * @param object   the document object, {@code document:<hash>}
 * @param relation {@link OpenFgaIdentifiers#GRANTEE} or {@link OpenFgaIdentifiers#BLOCKED}
 * @param user     {@code user:*} or a {@code group:<id>#member} userset
 */
public record DocumentTuple(String object, String relation, String user) {
}
