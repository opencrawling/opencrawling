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
package org.opencrawling.stormcrawler.config;

import java.util.ArrayList;
import java.util.List;

public class StormCrawlerProperties {

    private String nimbusHost = "localhost";
    private int nimbusPort = 6627;
    private String nimbusRestUrl = "http://localhost:8080";
    private String topologyName = "opencrawling-web-crawler";
    private List<String> seeds = new ArrayList<>(List.of("https://docs.example.com"));
    private int concurrency = 8;
    private long delayMs = 1000;
    private boolean ignoreRobotsTxt = false;
    private String customUserAgent = "OpenCrawling-StormCrawler-Bot/1.0";
    private List<String> includePatterns = new ArrayList<>();
    private List<String> excludePatterns = new ArrayList<>(List.of(".*\\.(pdf|zip|gz|exe|tar|dmg)$"));
    private List<String> allowedReadSids = new ArrayList<>(List.of("ROLE_USER"));
    private List<String> deniedReadSids = new ArrayList<>();

    public String getNimbusHost() {
        return nimbusHost;
    }

    public void setNimbusHost(String nimbusHost) {
        this.nimbusHost = nimbusHost;
    }

    public int getNimbusPort() {
        return nimbusPort;
    }

    public void setNimbusPort(int nimbusPort) {
        this.nimbusPort = nimbusPort;
    }

    public String getNimbusRestUrl() {
        return nimbusRestUrl;
    }

    public void setNimbusRestUrl(String nimbusRestUrl) {
        this.nimbusRestUrl = nimbusRestUrl;
    }

    public String getTopologyName() {
        return topologyName;
    }

    public void setTopologyName(String topologyName) {
        this.topologyName = topologyName;
    }

    public List<String> getSeeds() {
        return seeds;
    }

    public void setSeeds(List<String> seeds) {
        this.seeds = seeds;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public long getDelayMs() {
        return delayMs;
    }

    public void setDelayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public boolean isIgnoreRobotsTxt() {
        return ignoreRobotsTxt;
    }

    public void setIgnoreRobotsTxt(boolean ignoreRobotsTxt) {
        this.ignoreRobotsTxt = ignoreRobotsTxt;
    }

    public String getCustomUserAgent() {
        return customUserAgent;
    }

    public void setCustomUserAgent(String customUserAgent) {
        this.customUserAgent = customUserAgent;
    }

    public List<String> getIncludePatterns() {
        return includePatterns;
    }

    public void setIncludePatterns(List<String> includePatterns) {
        this.includePatterns = includePatterns;
    }

    public List<String> getExcludePatterns() {
        return excludePatterns;
    }

    public void setExcludePatterns(List<String> excludePatterns) {
        this.excludePatterns = excludePatterns;
    }

    public List<String> getAllowedReadSids() {
        return allowedReadSids;
    }

    public void setAllowedReadSids(List<String> allowedReadSids) {
        this.allowedReadSids = allowedReadSids;
    }

    public List<String> getDeniedReadSids() {
        return deniedReadSids;
    }

    public void setDeniedReadSids(List<String> deniedReadSids) {
        this.deniedReadSids = deniedReadSids;
    }
}
