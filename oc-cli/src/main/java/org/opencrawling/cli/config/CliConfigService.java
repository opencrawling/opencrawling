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
package org.opencrawling.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.opencrawling.sdk.OpenCrawlingClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Service for managing local CLI configurations in ~/.oc/config.json
 * and creating OpenCrawlingClient instances.
 */
public class CliConfigService {

    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".oc");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static CliConfig loadConfig() {
        if (!Files.exists(CONFIG_FILE)) {
            return CliConfig.defaultConfig();
        }
        try {
            return OBJECT_MAPPER.readValue(CONFIG_FILE.toFile(), CliConfig.class);
        } catch (IOException e) {
            return CliConfig.defaultConfig();
        }
    }

    public static void saveConfig(CliConfig config) throws IOException {
        if (!Files.exists(CONFIG_DIR)) {
            Files.createDirectories(CONFIG_DIR);
        }
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(CONFIG_FILE.toFile(), config);
    }

    public static OpenCrawlingClient createClient(String overrideUrl, String overrideApiKey) {
        CliConfig config = loadConfig();
        String baseUrl = overrideUrl != null && !overrideUrl.isBlank() ? overrideUrl : config.serverUrl();
        String apiKey = overrideApiKey != null && !overrideApiKey.isBlank() ? overrideApiKey : config.apiKey();

        OpenCrawlingClient.Builder builder = OpenCrawlingClient.builder()
                .baseUrl(baseUrl);

        if (apiKey != null && !apiKey.isBlank()) {
            builder.apiKey(apiKey);
        }

        if (config.bearerToken() != null && !config.bearerToken().isBlank()) {
            builder.bearerToken(config.bearerToken());
        }

        return builder.build();
    }
}
