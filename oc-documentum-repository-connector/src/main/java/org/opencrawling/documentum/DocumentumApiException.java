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
package org.opencrawling.documentum;

import java.io.IOException;

/**
 * A Documentum REST call that failed: the HTTP status and, when the body is a REST error ({@code {status, code, message,
 * details, id}}), its code (e.g. {@code E_RESOURCE_NOT_FOUND}). Status 0 is a failure found by the client itself. The message
 * names the request, never a credential.
 */
public class DocumentumApiException extends IOException {

    private final int status;
    private final String code;

    public DocumentumApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** The REST error code, or null. */
    public String code() {
        return code;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
