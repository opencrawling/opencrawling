/*
 * Copyright © 2026 Piergiorgio Lucidi (piergiorgio@apache.org)
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
package org.opencrawling.runtime.claimcheck;

import org.opencrawling.core.claimcheck.ClaimCheckProperties;
import org.opencrawling.core.claimcheck.ClaimCheckStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Scheduled background Garbage Collector for orphaned Claim-Check payload objects.
 * Sweeps the active ClaimCheckStore (Apache Ozone / Local Disk) to purge payloads older than configured TTL.
 *
 * <p>Claims that a dead-lettered document message still references are kept, whatever their age, so replaying the
 * dead letter can read its content again ({@link DeadLetteredClaims}). If the dead letters cannot be read, the sweep
 * is skipped rather than risk deleting one of those claims.
 */
@Component
public class ClaimCheckGarbageCollector {

    private static final Logger log = LoggerFactory.getLogger(ClaimCheckGarbageCollector.class);

    private final ClaimCheckStore claimCheckStore;
    private final ClaimCheckProperties properties;
    private DeadLetteredClaims deadLetteredClaims;

    public ClaimCheckGarbageCollector(ClaimCheckStore claimCheckStore, ClaimCheckProperties properties) {
        this.claimCheckStore = claimCheckStore;
        this.properties = properties;
    }

    /** The dead letters whose claims are kept; without it (tests, programmatic use) nothing is kept. */
    @Autowired(required = false)
    public void setDeadLetteredClaims(DeadLetteredClaims deadLetteredClaims) {
        this.deadLetteredClaims = deadLetteredClaims;
    }

    /**
     * Periodic background sweep running according to cron schedule (defaults to hourly).
     */
    @Scheduled(cron = "${spring.opencrawling.claim-check.lifecycle.gc-cron:0 0 * * * *}")
    public void runGarbageCollectionSweep() {
        if (properties.getLifecycle() != null && !properties.getLifecycle().isEnableBackgroundGc()) {
            return;
        }

        int ttlHours = (properties.getLifecycle() != null && properties.getLifecycle().getTtlHours() > 0)
                ? properties.getLifecycle().getTtlHours()
                : 24;

        Duration ttl = Duration.ofHours(ttlHours);

        Predicate<URI> retain = uri -> false;
        if (deadLetteredClaims != null) {
            try {
                Set<String> referenced = deadLetteredClaims.referencedClaims();
                retain = DeadLetteredClaims.retaining(referenced);
                if (!referenced.isEmpty()) {
                    log.info("Claim-Check Garbage Collection keeps the {} claims referenced by dead-lettered documents.", referenced.size());
                }
            } catch (Exception e) {
                log.warn("Skipping Claim-Check Garbage Collection: the dead letters that may reference claims could not be read ({}).",
                        e.getMessage());
                return;
            }
        }
        log.info("Starting Claim-Check Garbage Collection sweep (Purging objects older than {} hours)...", ttlHours);

        try {
            int deletedCount = claimCheckStore.deleteExpired(ttl, retain);
            if (deletedCount > 0) {
                log.info("Claim-Check Garbage Collection completed. Purged {} orphaned payload objects.", deletedCount);
            } else {
                log.debug("Claim-Check Garbage Collection completed. No orphaned payload objects found.");
            }
        } catch (Exception e) {
            log.error("Failed to complete Claim-Check Garbage Collection sweep: {}", e.getMessage(), e);
        }
    }
}
