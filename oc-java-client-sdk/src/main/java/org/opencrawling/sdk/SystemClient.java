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

import org.opencrawling.sdk.models.SystemSettings;
import org.opencrawling.sdk.models.SystemStatus;

import java.util.List;
import java.util.Map;

/**
 * Client for retrieving OpenCrawling system health, throughput metrics, logs, and settings.
 */
public interface SystemClient {

    /**
     * Gets system component health status.
     */
    SystemStatus getStatus();

    /**
     * Gets document throughput metrics over time.
     */
    List<Map<String, Object>> getThroughput();

    /**
     * Gets in-memory system log entries.
     */
    List<String> getLogs();

    /**
     * Gets current system settings.
     */
    SystemSettings getSettings();

    /**
     * Updates system settings configuration.
     */
    void updateSettings(SystemSettings settings);
}
