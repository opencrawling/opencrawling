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
package org.opencrawling.doxis.output.client;

import java.io.IOException;

/**
 * A non-2xx response from the Doxis AI.dp API, carrying the platform error envelope
 * ({@code result}, {@code code}, {@code sub_code}, {@code message}, {@code request_id}).
 */
public class DoxisApiException extends IOException {

    private final int statusCode;
    private final Integer errorCode;
    private final String requestId;

    public DoxisApiException(String operation, int statusCode, Integer errorCode, String apiMessage, String requestId) {
        super(operation + " failed: HTTP " + statusCode
                + (errorCode != null ? " (code " + errorCode + ")" : "")
                + (apiMessage != null && !apiMessage.isBlank() ? " - " + apiMessage : "")
                + (requestId != null && !requestId.isBlank() ? " [request_id=" + requestId + "]" : ""));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }
}
