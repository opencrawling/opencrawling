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
package org.opencrawling.documentum;

import org.opencrawling.documentum.DocumentumProperties.VersionMode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The DQL statements of a crawl.
 *
 * <p>Every listing selects single-valued attributes only and is ordered by {@code r_modify_date, r_object_id}; it is paged
 * with a keyset cursor ({@code r_modify_date > t OR (r_modify_date = t AND r_object_id > id)}) rather than with REST
 * pages, because a page number re-runs the query and shifts when objects change during a crawl, and because
 * {@code r_modify_date} has one-second precision, so many objects can share it. Date literals are written in the Content
 * Server's time zone, which is how DQL reads them (values are stored in UTC).
 */
final class DocumentumDql {

    /** The attributes of a listing row: enough to decide whether a document changed without fetching it. */
    static final String LISTING = "r_object_id, i_chronicle_id, r_object_type, r_modify_date, i_vstamp, acl_domain, acl_name, "
            + "owner_name, group_name";
    static final String ACL = "r_object_id, owner_name, object_name, i_vstamp, r_accessor_name, r_accessor_permit, "
            + "r_permit_type, r_is_group";
    private static final DateTimeFormatter LITERAL = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    /**
     * At most this many values in an {@code IN} list or OR-ed conditions. The bound is the URL rather than the database:
     * a DQL query travels in a GET request line, which Tomcat under REST Services limits to 8 KB with the headers
     * (maxHttpHeaderSize), and 100 quoted object ids are about 2.5 KB once URL-encoded.
     */
    static final int IN_LIST = 100;
    /** At most this many characters of values (names) in one {@code IN} list, for the same reason. */
    static final int IN_CHARS = 2000;

    private final DocumentumProperties settings;

    DocumentumDql(DocumentumProperties settings) {
        this.settings = settings;
    }

    static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /** A DQL date literal of an instant, in the given server time zone, truncated to the second. */
    static String date(Instant instant, ZoneId zone) {
        return "DATE('" + LITERAL.format(instant.truncatedTo(ChronoUnit.SECONDS).atZone(zone)) + "','yyyy/mm/dd hh:mi:ss')";
    }

    /** {@code FROM <type>}, with {@code (ALL)} when versions other than CURRENT are crawled. */
    String from(String type) {
        return type + (settings.versionMode() == VersionMode.CURRENT ? "" : " (ALL)");
    }

    /**
     * The conditions of the configured scope, without any watermark: the folder, the configured condition, the
     * {@code dql-query} condition, {@code modified-after}.
     *
     * @param folderOverride a folder path that replaces the configured scope, or null
     */
    List<String> scope(String folderOverride, ZoneId zone) {
        List<String> clauses = new ArrayList<>();
        String folder = folderOverride != null ? folderOverride
                : settings.crawlMode() == DocumentumProperties.CrawlMode.FOLDER ? settings.rootCabinetPath() : null;
        if (folder != null && !folder.equals("/")) {
            clauses.add("FOLDER(" + quote(folder) + (settings.includeSubfolders() ? ", DESCEND" : "") + ")");
        }
        if (settings.where() != null) {
            clauses.add("(" + settings.where() + ")");
        }
        Optional<String[]> dql = settings.parsedDql();
        if (dql.isPresent() && dql.get()[1] != null) {
            clauses.add("(" + dql.get()[1] + ")");
        }
        settings.modifiedAfterInstant().ifPresent(i -> clauses.add("r_modify_date >= " + date(i, zone)));
        return clauses;
    }

    /** One listing page: the scope, an optional extra condition and the keyset cursor, in modification order. */
    String listing(String type, List<String> scope, String extra, Cursor after) {
        List<String> clauses = new ArrayList<>(scope);
        if (extra != null) {
            clauses.add(extra);
        }
        if (after != null) {
            String t = date(after.modified(), after.zone());
            clauses.add("(r_modify_date > " + t + " OR (r_modify_date = " + t + " AND r_object_id > "
                    + quote(after.objectId()) + "))");
        }
        return "SELECT " + LISTING + " FROM " + from(type) + where(clauses) + " ORDER BY r_modify_date, r_object_id";
    }

    /** The versions of these chronicles in scope that may be their latest major version. */
    String majorVersions(String type, List<String> scope, List<String> chronicleIds) {
        List<String> clauses = new ArrayList<>(scope);
        clauses.add("i_chronicle_id IN (" + in(chronicleIds) + ")");
        clauses.add("ANY r_version_label LIKE '%.0'");
        return "SELECT " + LISTING + ", r_version_label FROM " + type + " (ALL)" + where(clauses) + " ORDER BY r_object_id";
    }

    /** The ids in scope, in id order with a keyset cursor (for reconciliation). */
    String ids(String type, List<String> scope, String afterObjectId) {
        List<String> clauses = new ArrayList<>(scope);
        if (settings.versionMode() == VersionMode.LATEST_MAJOR) {
            clauses.add("ANY r_version_label LIKE '%.0'");
        }
        if (afterObjectId != null) {
            clauses.add("r_object_id > " + quote(afterObjectId));
        }
        return "SELECT r_object_id, i_chronicle_id FROM " + from(type) + where(clauses) + " ORDER BY r_object_id";
    }

    /** Permission sets by (domain, name). */
    static String acls(List<String[]> domainAndName) {
        List<String> or = new ArrayList<>();
        for (String[] key : domainAndName) {
            or.add("(owner_name = " + quote(key[0]) + " AND object_name = " + quote(key[1]) + ")");
        }
        return "SELECT " + ACL + " FROM dm_acl WHERE " + String.join(" OR ", or) + " ORDER BY r_object_id";
    }

    /** The current {@code i_vstamp} of permission sets, by id. */
    static String aclStamps(List<String> ids) {
        return "SELECT r_object_id, owner_name, object_name, i_vstamp FROM dm_acl WHERE r_object_id IN (" + in(ids) + ")"
                + " ORDER BY r_object_id";
    }

    /** Every user with one of these login names, whatever their login domain. */
    static String usersByLogin(List<String> logins) {
        return "SELECT r_object_id, user_name, user_login_name, user_login_domain, r_is_group FROM dm_user "
                + "WHERE user_login_name IN (" + in(logins) + ") ORDER BY r_object_id";
    }

    static String users(List<String> names) {
        return "SELECT r_object_id, user_name, user_login_name, r_is_group FROM dm_user WHERE user_name IN (" + in(names) + ")"
                + " ORDER BY r_object_id";
    }

    static String folders(List<String> ids) {
        return "SELECT r_object_id, r_folder_path FROM dm_folder WHERE r_object_id IN (" + in(ids) + ")"
                + " ORDER BY r_object_id";
    }

    /** One cabinet and its modification time, to check how the server reads date literals. */
    static final String PROBE = "SELECT r_object_id, r_modify_date FROM dm_cabinet ORDER BY r_object_id ENABLE (RETURN_TOP 1)";

    /** The probed cabinet again, matched on its exact modification time written as a date literal in a zone. */
    static String probe(String objectId, Instant modified, ZoneId zone) {
        return "SELECT r_object_id FROM dm_cabinet WHERE r_object_id = " + quote(objectId) + " AND r_modify_date = "
                + date(modified, zone);
    }

    /** The folders below a path saved since a time (each with its paths). */
    static String changedFolders(String root, Instant since, ZoneId zone) {
        return "SELECT r_object_id, r_folder_path FROM dm_folder WHERE FOLDER(" + quote(root) + ", DESCEND) AND "
                + "r_modify_date >= " + date(since, zone) + " ORDER BY r_object_id";
    }

    static String folderExists(String path) {
        return "SELECT r_object_id FROM dm_folder WHERE ANY r_folder_path = " + quote(path);
    }

    /** Audit events since a time, in time order with a keyset cursor. */
    String audit(Instant since, Cursor after, ZoneId zone) {
        List<String> clauses = new ArrayList<>();
        clauses.add("event_name IN (" + in(settings.auditEvents()) + ")");
        clauses.add("time_stamp >= " + date(since, zone));
        if (after != null) {
            String t = date(after.modified(), after.zone());
            clauses.add("(time_stamp > " + t + " OR (time_stamp = " + t + " AND r_object_id > " + quote(after.objectId())
                    + "))");
        }
        return "SELECT r_object_id, event_name, audited_obj_id, chronicle_id, time_stamp FROM dm_audittrail"
                + where(clauses) + " ORDER BY time_stamp, r_object_id";
    }

    private static String in(List<String> values) {
        return String.join(", ", values.stream().map(DocumentumDql::quote).toList());
    }

    private static String where(List<String> clauses) {
        return clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
    }

    /**
     * A keyset position: the last row's time, the server time zone to write it in (the offset the server returned for
     * that very value, unless a zone is configured, so daylight saving time is right), and its object id.
     */
    record Cursor(Instant modified, ZoneId zone, String objectId) {
    }
}
