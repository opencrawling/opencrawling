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
package org.opencrawling.migrator.mcf.ois;

import java.util.List;
import java.util.Map;

/**
 * One job's OIS document plus the notes describing what {@link OisJobRenderer} had to default or
 * omit to produce it (schedule, pipeline, unresolved connector types) — separate from the job's
 * own {@code FieldNote}s, since these are specific to the OIS export step, not the underlying
 * ManifoldCF-to-OpenCrawling mapping.
 */
public record OisConversionResult(String fileBaseName, Map<String, Object> document, List<String> notes) {
}
