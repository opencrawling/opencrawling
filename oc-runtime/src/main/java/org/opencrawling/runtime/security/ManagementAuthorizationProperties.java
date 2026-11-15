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
package org.opencrawling.runtime.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may do what on the management plane (jobs, connectors, settings, reports, traces):
 * {@code opencrawling.security.authorization.*}.
 *
 * <p>Off by default, so nothing changes for existing deployments. On, it needs a verified caller on every API request:
 * {@code opencrawling.security.identity.mode=jwt} with {@code protect-api=true}.
 */
@ConfigurationProperties(prefix = ManagementAuthorizationProperties.PREFIX)
public class ManagementAuthorizationProperties {

    public static final String PREFIX = "opencrawling.security.management";

    private boolean enabled = false;

    /** Groups (token group claims) whose members may do everything: every job, connectors, settings, reports. */
    private List<String> adminGroups = new ArrayList<>(List.of("opencrawling-admin"));

    /**
     * Groups whose members may create jobs, besides the admins. A creator owns the jobs it creates. Empty by default:
     * only admins create jobs.
     */
    private List<String> jobCreatorGroups = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAdminGroups() {
        return adminGroups;
    }

    public void setAdminGroups(List<String> adminGroups) {
        this.adminGroups = adminGroups != null ? new ArrayList<>(adminGroups) : new ArrayList<>();
    }

    public List<String> getJobCreatorGroups() {
        return jobCreatorGroups;
    }

    public void setJobCreatorGroups(List<String> jobCreatorGroups) {
        this.jobCreatorGroups = jobCreatorGroups != null ? new ArrayList<>(jobCreatorGroups) : new ArrayList<>();
    }
}
