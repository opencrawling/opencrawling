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
package org.opencrawling.runtime.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Configuration properties for the built-in native job scheduler (#197). */
@ConfigurationProperties(prefix = "opencrawling.scheduler")
public class SchedulerProperties {

    private boolean enabled = true;
    private Duration pollInterval = Duration.ofSeconds(5);
    private Duration minInterval = Duration.ofMinutes(1);
    private Duration misfireGracePeriod = Duration.ofMinutes(15);
    private String defaultTimezone = "UTC";
    private int maxBatchPerTick = 25;
    private Duration dispatchRetention = Duration.ofDays(90);
    private Duration stopPreviousWait = Duration.ofSeconds(2);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval != null ? pollInterval : Duration.ofSeconds(5);
    }

    public Duration getMinInterval() {
        return minInterval;
    }

    public void setMinInterval(Duration minInterval) {
        this.minInterval = minInterval != null ? minInterval : Duration.ofMinutes(1);
    }

    public Duration getMisfireGracePeriod() {
        return misfireGracePeriod;
    }

    public void setMisfireGracePeriod(Duration misfireGracePeriod) {
        this.misfireGracePeriod = misfireGracePeriod != null ? misfireGracePeriod : Duration.ofMinutes(15);
    }

    public String getDefaultTimezone() {
        return defaultTimezone;
    }

    public void setDefaultTimezone(String defaultTimezone) {
        this.defaultTimezone = defaultTimezone != null && !defaultTimezone.isBlank() ? defaultTimezone.strip() : "UTC";
    }

    public int getMaxBatchPerTick() {
        return maxBatchPerTick;
    }

    public void setMaxBatchPerTick(int maxBatchPerTick) {
        this.maxBatchPerTick = Math.max(1, maxBatchPerTick);
    }

    public Duration getDispatchRetention() {
        return dispatchRetention;
    }

    public void setDispatchRetention(Duration dispatchRetention) {
        this.dispatchRetention = dispatchRetention != null ? dispatchRetention : Duration.ofDays(90);
    }

    public Duration getStopPreviousWait() {
        return stopPreviousWait;
    }

    public void setStopPreviousWait(Duration stopPreviousWait) {
        this.stopPreviousWait = stopPreviousWait != null ? stopPreviousWait : Duration.ofSeconds(2);
    }
}
