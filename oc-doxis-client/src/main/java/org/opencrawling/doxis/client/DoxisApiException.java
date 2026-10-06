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
package org.opencrawling.doxis.client;

import java.io.IOException;

/**
 * A non-2xx response from the Doxis CSB REST API, carrying its exception envelope
 * ({@code type}, {@code errorCode}, {@code message}), e.g. {@code SECU0014} (missing permission) or
 * {@code INSTANCE0014} (information object type not allowed in the repository).
 */
public class DoxisApiException extends IOException {

    private final int statusCode;
    private final String errorCode;

    public DoxisApiException(String operation, int statusCode, String errorCode, String apiMessage) {
        super(operation + " failed: HTTP " + statusCode
                + (errorCode != null && !errorCode.isBlank() ? " [" + errorCode + "]" : "")
                + (apiMessage != null && !apiMessage.isBlank() ? " - " + apiMessage : ""));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
