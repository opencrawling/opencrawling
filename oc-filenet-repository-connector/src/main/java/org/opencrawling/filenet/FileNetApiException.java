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
package org.opencrawling.filenet;

import java.io.IOException;
import java.util.List;

/**
 * A Content Services GraphQL call that failed: an HTTP status other than 200 (status set, {@code errors} empty), or GraphQL
 * errors in a 200 response (status 200, the error messages, which carry the CE exception code such as
 * {@code E_OBJECT_NOT_FOUND}). The message names the operation, never a credential.
 */
public class FileNetApiException extends IOException {

    private final int status;
    private final List<String> errors;

    public FileNetApiException(int status, String message) {
        this(status, message, List.of());
    }

    public FileNetApiException(int status, String message, List<String> errors) {
        super(message);
        this.status = status;
        this.errors = List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    /** The GraphQL error messages; empty for an HTTP failure. */
    public List<String> errors() {
        return errors;
    }

    public boolean isGraphQl() {
        return !errors.isEmpty();
    }

    /** A CE "object not found" error, or an HTTP 404. */
    public boolean isNotFound() {
        return status == 404 || errors.stream().anyMatch(e -> e.contains("E_OBJECT_NOT_FOUND"));
    }
}
