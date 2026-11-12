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

import com.fasterxml.jackson.core.type.TypeReference;
import org.opencrawling.sdk.http.HttpTransport;
import org.opencrawling.sdk.models.CallerInfo;
import org.opencrawling.sdk.models.JobAction;

import java.util.List;
import java.util.Map;

public class DefaultAuthClient implements AuthClient {

    private final HttpTransport transport;

    public DefaultAuthClient(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public CallerInfo me() {
        return transport.execute("GET", "/api/auth/me", null, CallerInfo.class);
    }

    @Override
    public Map<String, List<JobAction>> jobPermissions() {
        return transport.execute("GET", "/api/jobs/permissions", null, new TypeReference<Map<String, List<JobAction>>>() {});
    }
}
