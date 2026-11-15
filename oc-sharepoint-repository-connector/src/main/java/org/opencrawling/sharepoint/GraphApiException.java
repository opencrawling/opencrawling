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
package org.opencrawling.sharepoint;

import java.io.IOException;

/**
 * A Microsoft Graph call answered with a non-success status. The message carries the method, the path (never a token or
 * secret) and Graph's error code and message. A 410 Gone from a delta request carries the restart link in {@link #location()}.
 */
public class GraphApiException extends IOException {

    private final int status;
    private final String code;
    private final String location;

    public GraphApiException(int status, String code, String message, String location) {
        super(message);
        this.status = status;
        this.code = code;
        this.location = location;
    }

    public int status() {
        return status;
    }

    /** Graph's {@code error.code}, e.g. {@code itemNotFound} or {@code resyncChangesApplyDifferences}; may be null. */
    public String code() {
        return code;
    }

    /** The {@code Location} header, set by Graph on a 410 Gone to restart a delta enumeration. */
    public String location() {
        return location;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    /** A delta token Graph no longer accepts: the enumeration must start again from the returned location. */
    public boolean isResyncRequired() {
        return status == 410;
    }
}
