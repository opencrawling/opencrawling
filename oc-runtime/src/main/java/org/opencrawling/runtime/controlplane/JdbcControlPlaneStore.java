/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.runtime.controlplane;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Definitions in the runtime's database (#315), in the tables of {@code db/migration/V1__control_plane.sql}.
 *
 * <p>Jobs and connectors are one row each, keyed by job id and connector name, in list order. Other names are single
 * objects in {@code oc_setting}. A row's {@code version} increases whenever its body changes.
 *
 * <p>On the first read of a name that was never saved here, the matching file in {@code data/} is imported when it
 * exists, so an existing deployment keeps its definitions; the file is left as it was. Failures are thrown, never
 * swallowed: a definition that was not saved must not look saved.
 */
public final class JdbcControlPlaneStore implements ControlPlaneStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcControlPlaneStore.class);

    /** A list kept one row per element, keyed by a field of the element. */
    private record ListTable(String table, String keyColumn, String keyField) {
    }

    private static final Map<String, ListTable> LISTS = Map.of(
            "jobs.json", new ListTable("oc_job", "id", "id"),
            "connectors.json", new ListTable("oc_connector", "name", "name"));

    /** Marks, in {@code oc_setting}, that a list was saved here at least once (it may be empty since). */
    private static final String SAVED_MARKER = "list-saved:";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Path dataDir;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public JdbcControlPlaneStore(JdbcTemplate jdbc, TransactionTemplate transactions, Path dataDir) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.dataDir = dataDir;
    }

    @Override
    public synchronized <T> List<T> loadList(String name, Class<T> type, List<T> defaults) {
        ListTable table = listTable(name);
        if (!exists("oc_setting", "name", SAVED_MARKER + name)) {
            List<T> initial = importedFile(name, mapper.getTypeFactory().constructCollectionType(List.class, type), defaults);
            save(name, initial);
            return new ArrayList<>(initial);
        }
        List<String> bodies = jdbc.queryForList("SELECT body FROM " + table.table() + " ORDER BY position", String.class);
        List<T> items = new ArrayList<>(bodies.size());
        for (String body : bodies) {
            items.add(mapper.readValue(body, type));
        }
        return items;
    }

    @Override
    public synchronized <T> T loadObject(String name, Class<T> type, T defaultValue) {
        List<String> body = jdbc.queryForList("SELECT body FROM oc_setting WHERE name = ?", String.class, name);
        if (!body.isEmpty()) {
            return mapper.readValue(body.getFirst(), type);
        }
        T initial = importedFile(name, mapper.getTypeFactory().constructType(type), defaultValue);
        save(name, initial);
        return initial;
    }

    @Override
    public synchronized void save(String name, Object data) {
        transactions.executeWithoutResult(status -> {
            ListTable table = LISTS.get(name);
            if (table == null) {
                upsertSetting(name, mapper.writeValueAsString(data));
            } else {
                saveList(name, table, data == null ? List.of() : (List<?>) data);
            }
        });
    }

    private void saveList(String name, ListTable table, List<?> items) {
        Map<String, StoredRow> existing = new HashMap<>();
        jdbc.query("SELECT " + table.keyColumn() + ", body, position FROM " + table.table(), rs -> {
            existing.put(rs.getString(1), new StoredRow(rs.getString(2), rs.getInt(3)));
        });
        Timestamp now = Timestamp.from(Instant.now());
        Set<String> kept = new HashSet<>();
        int position = 0;
        for (Object item : items) {
            JsonNode node = mapper.valueToTree(item);
            JsonNode keyNode = node.get(table.keyField());
            String key = keyNode == null || keyNode.isNull() ? null : keyNode.asString();
            if (key == null || key.isBlank() || !kept.add(key)) {
                log.warn("Not saving an element of {} without a unique {}: {}", name, table.keyField(), key);
                continue;
            }
            String body = mapper.writeValueAsString(node);
            StoredRow stored = existing.get(key);
            if (stored == null) {
                jdbc.update("INSERT INTO " + table.table() + " (" + table.keyColumn()
                        + ", position, body, version, updated_at) VALUES (?, ?, ?, 1, ?)", key, position, body, now);
            } else if (!stored.body().equals(body)) {
                jdbc.update("UPDATE " + table.table() + " SET position = ?, body = ?, version = version + 1, updated_at = ? WHERE "
                        + table.keyColumn() + " = ?", position, body, now, key);
            } else if (stored.position() != position) {
                jdbc.update("UPDATE " + table.table() + " SET position = ? WHERE " + table.keyColumn() + " = ?", position, key);
            }
            position++;
        }
        for (String key : existing.keySet()) {
            if (!kept.contains(key)) {
                jdbc.update("DELETE FROM " + table.table() + " WHERE " + table.keyColumn() + " = ?", key);
            }
        }
        upsertSetting(SAVED_MARKER + name, "true");
    }

    private void upsertSetting(String name, String body) {
        // Compared here, not in SQL: H2 keeps TEXT as a CLOB, which it cannot compare.
        Timestamp now = Timestamp.from(Instant.now());
        List<String> current = jdbc.queryForList("SELECT body FROM oc_setting WHERE name = ?", String.class, name);
        if (current.isEmpty()) {
            jdbc.update("INSERT INTO oc_setting (name, body, version, updated_at) VALUES (?, ?, 1, ?)", name, body, now);
        } else if (!current.getFirst().equals(body)) {
            jdbc.update("UPDATE oc_setting SET body = ?, version = version + 1, updated_at = ? WHERE name = ?", body, now, name);
        }
    }

    private boolean exists(String table, String column, String key) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, key);
        return count != null && count > 0;
    }

    /** The content of {@code data/<name>} when an earlier file-based runtime left one; otherwise the fallback. */
    private <T> T importedFile(String name, tools.jackson.databind.JavaType type, T fallback) {
        Path file = dataDir.resolve(name);
        if (!Files.isRegularFile(file)) {
            return fallback;
        }
        try {
            T imported = mapper.readValue(file.toFile(), type);
            log.info("Imported {} from {} into the control-plane database; the file is left unchanged.", name, file);
            return imported;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Cannot import " + file + " into the control-plane database: " + e.getMessage(), e);
        }
    }

    private static ListTable listTable(String name) {
        ListTable table = LISTS.get(name);
        if (table == null) {
            throw new IllegalArgumentException(name + " is not a list in the control-plane store");
        }
        return table;
    }

    private record StoredRow(String body, int position) {
    }
}
