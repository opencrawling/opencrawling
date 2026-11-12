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

import org.opencrawling.core.reporting.ExecutionProvenance;
import org.opencrawling.core.security.Caller;
import org.opencrawling.runtime.api.JobAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides what the caller of the current request may do on the management plane, when
 * {@code opencrawling.security.management.enabled} is on. Off, every check passes and the runtime behaves as before.
 *
 * <p>The caller is always the verified one from the request's bearer token; an asserted identity never counts. A
 * request without one is refused everything. Admins (members of {@code admin-groups}) may do everything. Other callers
 * see and act on a job according to its {@link JobAccess}; a job without one is for admins only.
 */
@Component
@EnableConfigurationProperties(ManagementAuthorizationProperties.class)
public class ManagementAuthorization {

    private static final Logger log = LoggerFactory.getLogger(ManagementAuthorization.class);

    /** What a caller wants to do with a job. */
    public enum JobAction {
        /** See the job, its runs, reports, traces and errors. */
        READ,
        /** Start, stop or pause it. */
        EXECUTE,
        /** Edit it, delete it, or change its access. */
        MANAGE
    }

    private final ManagementAuthorizationProperties properties;
    private final CallerResolver callerResolver;

    @Autowired
    public ManagementAuthorization(ManagementAuthorizationProperties properties,
                                   ObjectProvider<CallerResolver> callerResolver,
                                   ObjectProvider<IdentityProperties> identityProperties) {
        this(properties, callerResolver.getIfAvailable(), identityProperties.getIfAvailable());
    }

    /**
     * @param callerResolver     the runtime's caller resolver; {@code null} where no web API is served
     * @param identityProperties the identity settings; {@code null} where no web API is served
     * @throws IllegalStateException when authorization is on but API requests carry no verified caller
     */
    public ManagementAuthorization(ManagementAuthorizationProperties properties, CallerResolver callerResolver,
                                   IdentityProperties identityProperties) {
        this.properties = properties;
        this.callerResolver = callerResolver;
        if (properties.isEnabled()) {
            checkPrerequisites(this.callerResolver, identityProperties);
            log.info("Management authorization is on: admin groups {}, job creator groups {}.",
                    properties.getAdminGroups(), properties.getJobCreatorGroups());
        }
    }

    /**
     * Authorization decides on the verified caller of every API request, so it needs both. Only checked where the
     * runtime serves the API (servlet web apps): the decoupled worker containers have no caller resolver and no API.
     */
    private static void checkPrerequisites(CallerResolver resolver, IdentityProperties identity) {
        if (resolver == null) {
            log.info("{}.enabled is on, but this runtime serves no web API; nothing to authorize here.",
                    ManagementAuthorizationProperties.PREFIX);
            return;
        }
        if (!resolver.isVerified() || identity == null || !identity.isProtectApi()) {
            throw new IllegalStateException(ManagementAuthorizationProperties.PREFIX + ".enabled needs a verified caller on every "
                    + "API request: set " + IdentityProperties.PREFIX + ".mode=jwt and " + IdentityProperties.PREFIX
                    + ".protect-api=true.");
        }
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /** The verified caller of the current request, if any. */
    public Optional<Caller> currentCaller() {
        return callerResolver == null ? Optional.empty() : callerResolver.verifiedCaller();
    }

    /** Whether the current caller is an admin. Always true when authorization is off. */
    public boolean isAdmin() {
        return !isEnabled() || currentCaller().map(this::isAdmin).orElse(false);
    }

    public boolean isAdmin(Caller caller) {
        return caller != null && containsAny(caller.groups(), properties.getAdminGroups());
    }

    /** Whether the current caller may create jobs: an admin or a member of {@code job-creator-groups}. */
    public boolean canCreateJobs() {
        if (!isEnabled()) {
            return true;
        }
        return currentCaller()
                .map(caller -> isAdmin(caller) || containsAny(caller.groups(), properties.getJobCreatorGroups()))
                .orElse(false);
    }

    /** Whether the current caller may do {@code action} on a job with this access. */
    public boolean can(JobAccess access, JobAction action) {
        if (!isEnabled()) {
            return true;
        }
        return currentCaller().map(caller -> permits(caller, access, action)).orElse(false);
    }

    /** The decision for a given caller, independent of the request: admins, then tenant, owner and the lists. */
    public boolean permits(Caller caller, JobAccess access, JobAction action) {
        Objects.requireNonNull(action, "action");
        if (caller == null) {
            return false;
        }
        if (isAdmin(caller)) {
            return true;
        }
        if (access == null) {
            return false; // created before authorization was on: admins only, until one assigns it
        }
        if (access.tenantId() != null && !access.tenantId().equals(caller.tenant())) {
            return false;
        }
        if (access.owner() != null && access.owner().equalsIgnoreCase(caller.principal())) {
            return true;
        }
        if (action == JobAction.MANAGE) {
            return false;
        }
        boolean mayRun = containsIgnoreCase(access.allowedUsers(), caller.principal())
                || containsAny(caller.groups(), access.allowedGroups());
        if (mayRun || action == JobAction.EXECUTE) {
            return mayRun;
        }
        return containsIgnoreCase(access.viewerUsers(), caller.principal())
                || containsAny(caller.groups(), access.viewerGroups());
    }

    /**
     * Whether the current caller ran (or had run for it) the execution with this provenance: the user it ran for, or,
     * without one, who triggered it, in the same tenant. Used for runs of jobs that are not in the job list, such as
     * dynamic jobs started by agents.
     */
    public boolean ranFor(ExecutionProvenance provenance) {
        if (!isEnabled()) {
            return true;
        }
        if (provenance == null) {
            return false;
        }
        String user = provenance.onBehalfOf() != null ? provenance.onBehalfOf() : provenance.triggeredBy();
        return currentCaller()
                .filter(caller -> user != null && user.equalsIgnoreCase(caller.principal()))
                .filter(caller -> Objects.equals(blankToNull(provenance.callerTenant()), blankToNull(caller.tenant())))
                .isPresent();
    }

    /** Refuses the request with {@code 403} unless the current caller is an admin. */
    public void requireAdmin() {
        if (!isAdmin()) {
            throw forbidden("Only members of the admin groups may do this.");
        }
    }

    public static ResponseStatusException forbidden(String reason) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
    }

    private static boolean containsAny(Collection<String> callerGroups, List<String> allowed) {
        if (callerGroups == null || allowed == null) {
            return false;
        }
        for (String group : callerGroups) {
            if (containsIgnoreCase(allowed, group)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(Collection<String> values, String value) {
        if (values == null || value == null || value.isBlank()) {
            return false;
        }
        for (String candidate : values) {
            if (candidate != null && candidate.trim().equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
