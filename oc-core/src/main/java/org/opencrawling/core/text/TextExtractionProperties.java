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
package org.opencrawling.core.text;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Apache Tika text extraction and process isolation.
 */
@ConfigurationProperties(prefix = "opencrawling.tika")
public class TextExtractionProperties {

    /**
     * Whether text extraction is enabled.
     */
    private boolean enabled = true;

    /**
     * Whether to use PipesForkParser for process-isolated text extraction.
     */
    private boolean forkEnabled = true;

    /**
     * Maximum parse timeout in milliseconds per document.
     * When using fork mode, a document exceeding this limit is killed outright.
     * Default: 30000ms (30s).
     */
    private long timeoutMs = 30000;

    /**
     * Maximum extracted character limit per document.
     * Default: 20,000,000 characters (-1 for unlimited).
     */
    private int writeLimit = 20_000_000;

    /**
     * Number of forked worker clients.
     */
    private int numClients = 1;

    /**
     * Restart a forked process after parsing this many files to bound slow leaks.
     * Default: 10,000.
     */
    private int maxFilesPerProcess = 10000;

    /**
     * Whether to extract embedded documents (e.g. zip/tar/attachments).
     */
    private boolean extractEmbedded = false;

    /**
     * JVM arguments for the forked parser process.
     * Default adds -Xmx512m if not specified.
     */
    private List<String> jvmArgs = new ArrayList<>(List.of("-Xmx512m"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isForkEnabled() {
        return forkEnabled;
    }

    public void setForkEnabled(boolean forkEnabled) {
        this.forkEnabled = forkEnabled;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getWriteLimit() {
        return writeLimit;
    }

    public void setWriteLimit(int writeLimit) {
        this.writeLimit = writeLimit;
    }

    public int getNumClients() {
        return numClients;
    }

    public void setNumClients(int numClients) {
        this.numClients = numClients;
    }

    public int getMaxFilesPerProcess() {
        return maxFilesPerProcess;
    }

    public void setMaxFilesPerProcess(int maxFilesPerProcess) {
        this.maxFilesPerProcess = maxFilesPerProcess;
    }

    public boolean isExtractEmbedded() {
        return extractEmbedded;
    }

    public void setExtractEmbedded(boolean extractEmbedded) {
        this.extractEmbedded = extractEmbedded;
    }

    public List<String> getJvmArgs() {
        return jvmArgs;
    }

    public void setJvmArgs(List<String> jvmArgs) {
        this.jvmArgs = jvmArgs;
    }
}
