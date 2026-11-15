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
package org.opencrawling.mfiles;

import java.io.IOException;

/**
 * An MFWS call answered with a non-success status. The message carries the method, the path (never a credential) and the
 * start of the response body, which MFWS fills with its own error message.
 */
public class MFilesApiException extends IOException {

    private final int status;

    public MFilesApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    /** 404, 405 or 501: the server does not offer this resource (e.g. an endpoint missing from an older MFWS). */
    public boolean isUnsupported() {
        return status == 404 || status == 405 || status == 501;
    }
}
