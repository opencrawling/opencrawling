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
package org.opencrawling.core.result;

public sealed interface ScanResult permits ScanResult.Success, ScanResult.Failure, ScanResult.Ignored {
    record Success(String documentId, String version) implements ScanResult {}
    record Failure(String documentId, Throwable error) implements ScanResult {}
    record Ignored(String documentId, String reason) implements ScanResult {}
    
    default String summarize() {
        return switch (this) {
            case Success s -> "Successfully scanned: " + s.documentId() + " (v: " + s.version() + ")";
            case Failure f -> "Failed to scan: " + f.documentId() + " due to " + f.error().getMessage();
            case Ignored i -> "Ignored: " + i.documentId() + " Reason: " + i.reason();
        };
    }
}
