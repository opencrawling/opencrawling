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
package org.opencrawling.zammad;

import java.io.IOException;

/**
 * Thrown when a Zammad REST API v1 request returns a non-2xx HTTP status code.
 *
 * <p>The exception message includes the HTTP method, path, status code, and a bounded excerpt of the response body,
 * never echoing credentials or authorization headers.
 */
public class ZammadApiException extends IOException {

    private final int status;

    public ZammadApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    public boolean isUnauthorized() {
        return status == 401 || status == 403;
    }
}
