/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.core.connector.discovery;

/**
 * Why a discovery or dry-run result stopped before covering everything in scope.
 */
public enum TruncationReason {
    /** The most nodes a request may return was reached. */
    MAX_NODES,
    /** Folders below the deepest level asked for were not opened. */
    MAX_DEPTH,
    /** The most items a dry run may look at was reached. */
    MAX_SCAN,
    /** The request ran out of time. */
    TIMEOUT
}
