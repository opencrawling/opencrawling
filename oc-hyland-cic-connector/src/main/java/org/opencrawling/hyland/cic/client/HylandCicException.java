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
package org.opencrawling.hyland.cic.client;

/**
 * Exception thrown when a Hyland Content Innovation Cloud (HXP) REST API interaction fails.
 */
public class HylandCicException extends RuntimeException {

    private final int statusCode;
    private final boolean retryable;

    public HylandCicException(String message) {
        this(message, 0, false, null);
    }

    public HylandCicException(String message, Throwable cause) {
        this(message, 0, false, cause);
    }

    public HylandCicException(String message, int statusCode) {
        this(message, statusCode, isRetryableStatus(statusCode), null);
    }

    public HylandCicException(String message, int statusCode, Throwable cause) {
        this(message, statusCode, isRetryableStatus(statusCode), cause);
    }

    public HylandCicException(String message, int statusCode, boolean retryable, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public boolean isNotFound() {
        return statusCode == 404;
    }

    public boolean isUnauthorized() {
        return statusCode == 401 || statusCode == 403;
    }

    public boolean isRateLimited() {
        return statusCode == 429;
    }

    private static boolean isRetryableStatus(int code) {
        return code == 429 || code == 502 || code == 503 || code == 504;
    }
}
