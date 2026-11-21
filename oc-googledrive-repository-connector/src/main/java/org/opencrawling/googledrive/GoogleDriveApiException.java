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
package org.opencrawling.googledrive;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * Error returned by the Google Drive REST API v3 or Google OAuth2 token endpoint.
 */
public class GoogleDriveApiException extends IOException {

    private static final Set<String> RATE_LIMIT_REASONS = Set.of(
            "userratelimitexceeded",
            "ratelimitexceeded",
            "sharingratelimitexceeded",
            "backenderror",
            "internalerror"
    );

    private final int status;
    private final String reason;
    private final String domain;

    public GoogleDriveApiException(int status, String reason, String domain, String message) {
        super(message);
        this.status = status;
        this.reason = reason;
        this.domain = domain;
    }

    public int status() {
        return status;
    }

    public String reason() {
        return reason;
    }

    public String domain() {
        return domain;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    public boolean isRateLimited() {
        if (status == 429) {
            return true;
        }
        return status == 403 && reason != null
                && RATE_LIMIT_REASONS.contains(reason.toLowerCase(Locale.ROOT));
    }

    public boolean isTransientServerError() {
        return status == 500 || status == 502 || status == 503 || status == 504;
    }

    public boolean isExportSizeLimitExceeded() {
        return status == 403 && reason != null
                && "exportsizelimitexceeded".equalsIgnoreCase(reason);
    }

    public boolean isInvalidPageToken() {
        if (status != 400 && status != 410) {
            return false;
        }
        String r = reason != null ? reason.toLowerCase(Locale.ROOT) : "";
        String m = getMessage() != null ? getMessage().toLowerCase(Locale.ROOT) : "";
        return status == 410
                || r.contains("pagetoken")
                || r.equals("invalid")
                || m.contains("pagetoken")
                || m.contains("page token");
    }
}
