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
package org.opencrawling.doxis.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the Doxis REST response fixtures in {@code src/test/resources/doxis-mock-responses/}, modelled on responses of a
 * Doxis CSB 14.4.1 server.
 */
public final class Fixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    public static String text(String name) {
        try (InputStream is = Fixtures.class.getResourceAsStream("/doxis-mock-responses/" + name)) {
            if (is == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode json(String name) {
        try {
            return MAPPER.readTree(text(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<JsonNode> list(String name) {
        List<JsonNode> items = new ArrayList<>();
        json(name).forEach(items::add);
        return items;
    }
}
