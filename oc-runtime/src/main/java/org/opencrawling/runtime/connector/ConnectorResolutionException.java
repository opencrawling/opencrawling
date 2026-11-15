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
package org.opencrawling.runtime.connector;

/**
 * A job's connector could not be built: its class is unknown, it cannot run from the job API, or its
 * configuration was rejected. The job does not start; nothing falls back to another connector.
 */
public class ConnectorResolutionException extends RuntimeException {

    public ConnectorResolutionException(String message) {
        super(message);
    }

    public ConnectorResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
