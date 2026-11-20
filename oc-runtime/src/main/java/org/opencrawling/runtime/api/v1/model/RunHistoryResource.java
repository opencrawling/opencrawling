/*
 * Copyright © 2026 textercabaceira (piergiorgio@apache.org)
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
package org.opencrawling.runtime.api.v1.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** A run of any kind with the history of its states; empty while a dynamic job waits for approval. */
@Schema(description = "A run of any kind and the history of its states")
public record RunHistoryResource(
        @Schema(description = "The run") RunResource run,
        @Schema(description = "The changes of its state, in order") List<JobRunStepResource> steps) {
}
