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
package org.opencrawling.sdk;

import org.opencrawling.sdk.models.CallerInfo;
import org.opencrawling.sdk.models.JobAction;

import java.util.List;
import java.util.Map;

/**
 * Who the client is to the runtime, and what it may do.
 */
public interface AuthClient {

    /** The caller as the runtime sees it ({@code GET /api/auth/me}). */
    CallerInfo me();

    /** What the caller may do with each job it can see ({@code GET /api/jobs/permissions}). */
    Map<String, List<JobAction>> jobPermissions();
}
