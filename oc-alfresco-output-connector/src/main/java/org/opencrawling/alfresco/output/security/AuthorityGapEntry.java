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
package org.opencrawling.alfresco.output.security;

import java.time.Instant;

/**
 * Audit record capturing an authority gap (missing user or group in target Alfresco instance)
 * encountered during Migration Mode Zero-Trust permission reconciliation.
 */
public record AuthorityGapEntry(
        String authorityId,
        String authorityType,
        String documentId,
        String attemptedRole,
        Instant timestamp,
        String actionTaken,
        String message
) {
    public static AuthorityGapEntry skipped(String authorityId, String authorityType, String documentId, String attemptedRole, String reason) {
        return new AuthorityGapEntry(authorityId, authorityType, documentId, attemptedRole, Instant.now(), "SKIPPED", reason);
    }

    public static AuthorityGapEntry failed(String authorityId, String authorityType, String documentId, String attemptedRole, String reason) {
        return new AuthorityGapEntry(authorityId, authorityType, documentId, attemptedRole, Instant.now(), "FAILED", reason);
    }
}
