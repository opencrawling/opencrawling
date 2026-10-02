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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.tika.Tika;
import org.apache.tika.config.TimeoutLimits;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.pipes.api.ParseMode;
import org.apache.tika.pipes.api.PipesResult;
import org.apache.tika.pipes.fork.PipesForkParser;
import org.apache.tika.pipes.fork.PipesForkParserConfig;
import org.apache.tika.pipes.fork.PipesForkResult;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Text extraction service utilizing Apache Tika 4.x.
 * When fork mode is enabled, it leverages {@link PipesForkParser} to isolate parsing
 * into a watchdog-monitored child JVM, enforcing hard timeouts, memory bounds,
 * and crash resilience without risking main worker process stability.
 */
public class PipesForkTextExtractor implements TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PipesForkTextExtractor.class);
    private static final String DEFAULT_FORK_HEAP = "-Xmx512m";

    private final TextExtractionProperties properties;
    private PipesForkParser pipesForkParser;
    private final Tika embeddedTika;
    private boolean useEmbeddedFallback = false;

    public PipesForkTextExtractor() {
        this(new TextExtractionProperties());
    }

    public PipesForkTextExtractor(TextExtractionProperties properties) {
        this.properties = properties != null ? properties : new TextExtractionProperties();
        this.embeddedTika = new Tika();

        if (this.properties.isEnabled() && this.properties.isForkEnabled() && this.properties.getTimeoutMs() > 0) {
            try {
                this.pipesForkParser = buildPipesForkParser(this.properties);
                startFork();
                log.info("Initialized process-isolated Tika PipesForkParser (timeout: {}ms, maxFiles: {}, jvmArgs: {})",
                        this.properties.getTimeoutMs(), this.properties.getMaxFilesPerProcess(),
                        this.properties.getJvmArgs());
            } catch (Exception e) {
                log.warn("Failed to initialize Tika PipesForkParser, falling back to embedded in-process Tika: {}",
                        e.getMessage(), e);
                this.useEmbeddedFallback = true;
                if (this.pipesForkParser != null) {
                    try {
                        this.pipesForkParser.close();
                    } catch (Exception ignored) {
                    }
                    this.pipesForkParser = null;
                }
            }
        } else {
            this.useEmbeddedFallback = true;
            log.info("Process-isolated fork parsing disabled. Using embedded in-process Apache Tika.");
        }
    }

    private PipesForkParser buildPipesForkParser(TextExtractionProperties conf) throws Exception {
        if (System.getProperty("tika.pipes.server.stdio") == null) {
            System.setProperty("tika.pipes.server.stdio", "discard");
        }

        PipesForkParserConfig config = new PipesForkParserConfig();
        config.setHandlerType(BasicContentHandlerFactory.HANDLER_TYPE.TEXT);
        config.setParseMode(ParseMode.CONCATENATE);
        config.setTimeoutLimits(new TimeoutLimits(conf.getTimeoutMs(), conf.getTimeoutMs()));
        config.setWriteLimit(conf.getWriteLimit() > 0 ? conf.getWriteLimit() : 20_000_000);
        config.setMaxEmbeddedCount(conf.isExtractEmbedded() ? -1 : 0);
        config.setNumClients(Math.max(1, conf.getNumClients()));

        String javaBinary = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        config.setJavaPath(javaBinary);

        List<String> jvmArgs = new ArrayList<>(conf.getJvmArgs() != null ? conf.getJvmArgs() : List.of());
        if (jvmArgs.stream().noneMatch(this::setsHeap)) {
            jvmArgs.add(DEFAULT_FORK_HEAP);
        }
        config.setJvmArgs(jvmArgs);

        if (conf.getMaxFilesPerProcess() > 0) {
            config.setMaxFilesPerProcess(conf.getMaxFilesPerProcess());
        }

        return new PipesForkParser(config);
    }

    private boolean setsHeap(String arg) {
        return arg != null && (arg.startsWith("-Xmx")
                || arg.startsWith("-XX:MaxHeapSize")
                || arg.startsWith("-XX:MaxRAM"));
    }

    private void startFork() throws Exception {
        Metadata warmupMetadata = new Metadata();
        warmupMetadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "warmup.txt");
        warmupMetadata.set(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE, "text/plain");

        try (TikaInputStream tis = TikaInputStream.get("OpenCrawling Warmup".getBytes(StandardCharsets.UTF_8))) {
            PipesForkResult result = pipesForkParser.parse(tis, warmupMetadata);
            if (!result.isSuccess()) {
                throw new IllegalStateException("Fork test parse failed: " + result.getStatus()
                        + (result.getMessage() != null ? " - " + result.getMessage() : ""));
            }
        }
    }

    @Override
    public TextExtractionResult extractText(InputStream inputStream, Map<String, List<String>> initialMetadata) {
        if (inputStream == null) {
            return TextExtractionResult.success("", initialMetadata, false);
        }

        try {
            byte[] contentBytes = inputStream.readAllBytes();
            return extractText(contentBytes, initialMetadata);
        } catch (IOException e) {
            log.error("Failed to read input stream for text extraction: {}", e.getMessage(), e);
            return TextExtractionResult.failure("IO error reading document stream: " + e.getMessage(), initialMetadata);
        }
    }

    @Override
    public TextExtractionResult extractText(byte[] contentBytes, Map<String, List<String>> initialMetadata) {
        if (contentBytes == null || contentBytes.length == 0) {
            return TextExtractionResult.success("", initialMetadata, false);
        }

        Map<String, List<String>> mergedMetadata = new HashMap<>();
        if (initialMetadata != null) {
            initialMetadata.forEach((k, v) -> mergedMetadata.put(k, new ArrayList<>(v)));
        }

        if (pipesForkParser != null && !useEmbeddedFallback) {
            try {
                return parseWithPipes(contentBytes, mergedMetadata);
            } catch (Exception e) {
                log.warn("PipesForkParser failed: {}. Falling back to embedded Tika.", e.getMessage());
            }
        }

        return parseWithEmbedded(contentBytes, mergedMetadata);
    }

    private TextExtractionResult parseWithPipes(byte[] contentBytes, Map<String, List<String>> metadata)
            throws Exception {
        Metadata tikaMd = new Metadata();
        populateTikaMetadata(tikaMd, metadata);

        PipesForkResult result;
        try (TikaInputStream tis = TikaInputStream.get(contentBytes)) {
            result = pipesForkParser.parse(tis, tikaMd, new ParseContext());
        }

        if (result.isProcessCrash()) {
            if (result.getStatus() == PipesResult.RESULT_STATUS.TIMEOUT) {
                log.warn("Tika parse timed out after {}ms", properties.getTimeoutMs());
                return TextExtractionResult.failure("Tika parse timeout (" + properties.getTimeoutMs() + "ms)", metadata);
            }
            log.error("Tika Pipes child process crashed during parsing: {}", result.getMessage());
            return TextExtractionResult.failure("Tika Pipes child process crashed: " + result.getMessage(), metadata);
        }

        if (!result.isSuccess()) {
            log.warn("Tika Pipes parse was unsuccessful: status={}, message={}", result.getStatus(), result.getMessage());
            return TextExtractionResult.failure("Tika parse failed: " + result.getStatus(), metadata);
        }

        String text = result.getContent();
        if (text == null) {
            text = "";
        }

        // Clean up null bytes to prevent database UTF-8 crashes
        text = text.replace("\u0000", "");

        // Fallback for plain text if Tika extracted empty content
        if (text.isBlank()) {
            text = fallbackPlainText(contentBytes, metadata);
        }

        boolean trimmed = result.getStatus() == PipesResult.RESULT_STATUS.PARSE_SUCCESS_WITH_EXCEPTION
                || result.getStatus() == PipesResult.RESULT_STATUS.PARTIAL_TIMEOUT
                || isWriteLimitReached(result.getMetadata());

        // Merge extracted metadata
        if (result.getMetadata() != null) {
            for (String name : result.getMetadata().names()) {
                mergedTikaMetadata(metadata, name, result.getMetadata().getValues(name));
            }
        }

        return TextExtractionResult.success(text, metadata, trimmed);
    }

    private TextExtractionResult parseWithEmbedded(byte[] contentBytes, Map<String, List<String>> metadata) {
        String text = "";
        Metadata tikaMd = new Metadata();
        populateTikaMetadata(tikaMd, metadata);

        try (InputStream is = new ByteArrayInputStream(contentBytes)) {
            text = embeddedTika.parseToString(is, tikaMd);
        } catch (Exception e) {
            log.warn("Embedded Tika failed to parse document: {}", e.getMessage());
        }

        if (text == null) {
            text = "";
        }
        text = text.replace("\u0000", "");

        if (text.isBlank()) {
            text = fallbackPlainText(contentBytes, metadata);
        }

        for (String name : tikaMd.names()) {
            mergedTikaMetadata(metadata, name, tikaMd.getValues(name));
        }

        return TextExtractionResult.success(text, metadata, false);
    }

    private void populateTikaMetadata(Metadata tikaMd, Map<String, List<String>> metadata) {
        if (metadata == null) return;
        List<String> mimeType = metadata.get("mimeType");
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = metadata.get("mimetype");
        }
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = metadata.get("Content-Type");
        }
        if (mimeType == null || mimeType.isEmpty()) {
            mimeType = metadata.get("content-type");
        }
        if (mimeType != null && !mimeType.isEmpty() && mimeType.get(0) != null) {
            tikaMd.set(org.apache.tika.metadata.HttpHeaders.CONTENT_TYPE, mimeType.get(0));
            tikaMd.set(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, mimeType.get(0));
        }

        List<String> filename = metadata.get("filename");
        if (filename == null || filename.isEmpty()) {
            filename = metadata.get("fileName");
        }
        if (filename == null || filename.isEmpty()) {
            filename = metadata.get("resourceName");
        }
        if (filename != null && !filename.isEmpty() && filename.get(0) != null) {
            tikaMd.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename.get(0));
        }
    }

    private void mergedTikaMetadata(Map<String, List<String>> metadata, String key, String[] values) {
        if (key == null || values == null || values.length == 0) return;
        if (TikaCoreProperties.TIKA_CONTENT.getName().equals(key)
                || TikaCoreProperties.TIKA_CONTENT_HANDLER_TYPE.getName().equals(key)) {
            return;
        }
        List<String> existing = metadata.computeIfAbsent("tk:" + key, k -> new ArrayList<>());
        for (String v : values) {
            if (v != null) {
                existing.add(v.replace("\u0000", ""));
            }
        }
    }

    private boolean isWriteLimitReached(Metadata metadata) {
        return metadata != null
                && "true".equalsIgnoreCase(metadata.get(TikaCoreProperties.WRITE_LIMIT_REACHED));
    }

    private String fallbackPlainText(byte[] contentBytes, Map<String, List<String>> metadata) {
        String mimeType = String.valueOf(metadata.getOrDefault("mimeType", List.of("text/plain")));
        if (mimeType.contains("text") || mimeType.contains("json") || mimeType.contains("xml") || mimeType.contains("csv")) {
            return new String(contentBytes, StandardCharsets.UTF_8).replace("\u0000", "");
        }
        return "";
    }

    @Override
    public void close() {
        if (pipesForkParser != null) {
            try {
                pipesForkParser.close();
                log.info("Closed Tika PipesForkParser.");
            } catch (Exception e) {
                log.warn("Error closing Tika PipesForkParser: {}", e.getMessage(), e);
            } finally {
                pipesForkParser = null;
            }
        }
    }
}
