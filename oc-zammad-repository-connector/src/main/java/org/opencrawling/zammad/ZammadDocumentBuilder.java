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
package org.opencrawling.zammad;

import com.fasterxml.jackson.databind.JsonNode;
import org.opencrawling.core.document.DocumentAction;
import org.opencrawling.core.document.RepositoryDocument;
import org.opencrawling.core.security.SecurityConfig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds OIS {@link RepositoryDocument} instances from Zammad tickets, conversation articles, binary attachments,
 * and Knowledge Base localized answers.
 */
public final class ZammadDocumentBuilder {

    /**
     * Resolved context for one ticket across lookup tables (groups, states, priorities, users, organizations).
     *
     * <p>{@code customerLabel} and {@code ownerLabel} are already rendered according to the connector's identity
     * rendering (e-mail, name, id or none, #414); they are used in headers and metadata, never in permissions.
     * {@code participants} are the display names (and ids) of the customer, the owner and the article authors.
     */
    public record TicketContext(
            String instanceKey,
            String baseUrl,
            long ticketId,
            String ticketNumber,
            String title,
            String groupId,
            String groupName,
            String stateName,
            String priorityName,
            String customerLabel,
            String organizationName,
            String ownerLabel,
            List<String> tags,
            String createdAt,
            String updatedAt,
            String closeAt,
            List<Participant> participants,
            ZammadProperties.ParticipantMetadata participantMetadata
    ) {
        public TicketContext {
            tags = tags == null ? List.of() : List.copyOf(tags);
            participants = participants == null ? List.of() : List.copyOf(participants);
            participantMetadata = participantMetadata == null
                    ? ZammadProperties.ParticipantMetadata.NONE : participantMetadata;
        }

        public String webUrl() {
            return baseUrl + "/#ticket/zoom/" + ticketId;
        }

        public TicketContext withParticipants(List<Participant> nextParticipants) {
            return new TicketContext(instanceKey, baseUrl, ticketId, ticketNumber, title, groupId, groupName, stateName,
                    priorityName, customerLabel, organizationName, ownerLabel, tags, createdAt, updatedAt, closeAt,
                    nextParticipants, participantMetadata);
        }
    }

    /** A person taking part in a ticket: Zammad user id ({@code <= 0} when unknown) and display name. */
    public record Participant(long userId, String displayName) {
        public String identity() {
            return userId > 0 ? "zammad-user-" + userId : null;
        }
    }

    /**
     * One ticket article prepared for rendering: its role ({@code customer}, {@code agent}, {@code internal} or
     * {@code system}), the role label shown in the heading, and the author already rendered per identity settings.
     */
    public record ArticleView(JsonNode article, String role, String roleLabel, String author) {
        public long id() {
            return article.path("id").asLong();
        }

        public boolean internal() {
            return article.path("internal").asBoolean(false);
        }
    }

    /** Metadata keys whose value changes without any change visible to readers; left out of content hashes. */
    private static final Set<String> VOLATILE_METADATA = Set.of("zammad.updatedAt");

    /** Metadata for one binary attachment on a Zammad ticket article. */
    public record AttachmentRef(
            long articleId,
            long attachmentId,
            String filename,
            String mimeType,
            long sizeBytes,
            boolean internal
    ) {
    }

    private static final Map<String, String> EXTENSION_MIME_TYPES = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("log", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("html", "text/html"),
            Map.entry("htm", "text/html"),
            Map.entry("md", "text/markdown"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("zip", "application/zip")
    );

    private ZammadDocumentBuilder() {
    }

    public static String publicTicketId(String instanceKey, long ticketId) {
        return "zammad://" + instanceKey + "/tickets/" + ticketId + "/public";
    }

    public static String internalTicketId(String instanceKey, long ticketId) {
        return "zammad://" + instanceKey + "/tickets/" + ticketId + "/internal";
    }

    /** Ticket-level conversation document ({@code TICKET} granularity, #411). */
    public static String ticketDocumentId(String instanceKey, long ticketId) {
        return "zammad://" + instanceKey + "/tickets/" + ticketId;
    }

    /**
     * Article role: {@code internal} for internal notes, otherwise {@code customer}, {@code agent} or {@code system}
     * from the Zammad sender type.
     */
    public static String articleRole(JsonNode article) {
        if (article.path("internal").asBoolean(false)) {
            return "internal";
        }
        String sender = text(article, "sender");
        if (sender == null) {
            return "agent";
        }
        return switch (sender.toLowerCase(Locale.ROOT)) {
            case "customer" -> "customer";
            case "system" -> "system";
            default -> "agent";
        };
    }

    /**
     * Whether Zammad marked the article as an automatic reply (its auto-response check sets
     * {@code preferences.is-auto-response} from headers such as {@code Auto-Submitted} or {@code X-Autoreply}), or it
     * is a delivery failure notice from a mailer daemon (#412).
     */
    public static boolean isAutoReply(JsonNode article) {
        JsonNode prefs = article.path("preferences");
        JsonNode flag = prefs.path("is-auto-response");
        if (flag.asBoolean(false) || "true".equalsIgnoreCase(flag.asText(""))) {
            return true;
        }
        String from = text(article, "from");
        if (from == null) {
            return false;
        }
        String lower = from.toLowerCase(Locale.ROOT);
        return lower.contains("mailer-daemon@") || lower.contains("postmaster@");
    }

    /**
     * Display name in an e-mail style address ({@code "Jane Doe <jane@example.com>"} gives {@code Jane Doe}); {@code null}
     * when the value is only an address.
     */
    public static String displayNameFromAddress(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        String value = from.strip();
        int lt = value.indexOf('<');
        String name = lt > 0 ? value.substring(0, lt) : (value.contains("@") ? "" : value);
        name = name.replace("\"", "").strip();
        return name.isEmpty() ? null : name;
    }

    /**
     * SHA-256 of what a reader of the document sees: content bytes, non-volatile metadata and the flat ACL. Used to
     * skip re-emitting a rendered document that did not change, for example when only a filtered system article was
     * added to its ticket (#412). The document's in-memory content stream is reset after reading.
     */
    public static String contentHash(RepositoryDocument document) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            InputStream in = document.contentStream();
            if (in != null) {
                if (!in.markSupported()) {
                    return null;
                }
                in.mark(Integer.MAX_VALUE);
                digest.update(in.readAllBytes());
                in.reset();
            }
            Map<String, List<String>> sorted = new TreeMap<>(document.metadata());
            VOLATILE_METADATA.forEach(sorted::remove);
            digest.update(sorted.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(("|acl:" + document.acl()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String articleDocumentId(String instanceKey, long ticketId, long articleId) {
        return "zammad://" + instanceKey + "/tickets/" + ticketId + "/articles/" + articleId;
    }

    public static String attachmentDocumentId(String instanceKey, long ticketId, long articleId, long attachmentId) {
        return "zammad://" + instanceKey + "/tickets/" + ticketId + "/articles/" + articleId + "/attachments/" + attachmentId;
    }

    public static String kbAnswerDocumentId(String instanceKey, long kbId, long answerId, String locale) {
        String loc = (locale == null || locale.isBlank()) ? "default" : locale.strip();
        return "zammad://" + instanceKey + "/kb/" + kbId + "/answers/" + answerId + "/" + loc;
    }

    /**
     * Extracts non-inline file attachments from an article JSON payload. Synthetic {@code content-alternative} HTML
     * body attachments that Zammad creates for email bodies are skipped so the article body is not indexed twice.
     */
    public static List<AttachmentRef> attachments(JsonNode article) {
        long articleId = article.path("id").asLong(-1L);
        if (articleId <= 0 || !article.path("attachments").isArray()) {
            return List.of();
        }
        boolean internal = article.path("internal").asBoolean(false);
        List<AttachmentRef> refs = new ArrayList<>();
        for (JsonNode att : article.path("attachments")) {
            long attId = att.path("id").asLong(-1L);
            if (attId <= 0) {
                continue;
            }
            JsonNode prefs = att.path("preferences");
            if (prefs.path("content-alternative").asBoolean(false)
                    || "true".equalsIgnoreCase(prefs.path("content-alternative").asText(""))) {
                continue;
            }
            String filename = textOrDefault(att, "filename", "attachment-" + attId);
            String mimeType = firstNonBlank(
                    text(prefs, "Mime-Type"),
                    text(prefs, "Content-Type"),
                    text(att, "mime_type"),
                    guessMimeType(filename),
                    "application/octet-stream");
            long size = att.path("size").asLong(-1L);
            refs.add(new AttachmentRef(articleId, attId, filename, mimeType, size, internal));
        }
        return List.copyOf(refs);
    }

    /**
     * Builds an aggregated OIS {@link RepositoryDocument} for either the public thread ({@code internal == false})
     * or the internal agent notes ({@code internal == true}) of a Zammad ticket.
     */
    public static RepositoryDocument buildTicketThread(
            TicketContext ctx,
            List<ArticleView> articles,
            boolean internal,
            ZammadSecurityMapper.Mapped security
    ) {
        String docId = internal
                ? internalTicketId(ctx.instanceKey(), ctx.ticketId())
                : publicTicketId(ctx.instanceKey(), ctx.ticketId());
        String displayTitle = internal ? ctx.title() + " [Internal Notes]" : ctx.title();
        String suffix = internal ? "internal" : "public";
        String groupSegment = sanitizePathSegment(ctx.groupName(), "Unassigned");
        String numberSegment = (ctx.ticketNumber() == null || ctx.ticketNumber().isBlank())
                ? Long.toString(ctx.ticketId())
                : ctx.ticketNumber();
        String path = "/Tickets/" + groupSegment + "/" + numberSegment + "_" + suffix + ".html";

        String html = renderTicketThreadHtml(ctx, articles, internal,
                internal ? "Internal Agent Notes" : "Public Customer Thread", displayTitle);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        Map<String, List<String>> metadata = baseTicketMetadata(ctx, internal);
        metadata.put("name", List.of(numberSegment + "_" + suffix + ".html"));
        metadata.put("title", List.of(displayTitle));
        metadata.put("mimeType", List.of("text/html"));
        metadata.put("path", List.of(path));
        metadata.put("zammad.entityType", List.of(internal ? "ticket_internal" : "ticket"));
        metadata.put("zammad.title", List.of(displayTitle));
        metadata.put("zammad.articleCount", List.of(Integer.toString(articles.size())));
        addSecurityMetadata(metadata, security);

        Instant lastModified = parseInstant(ctx.updatedAt()).orElseGet(Instant::now);
        return new RepositoryDocument(
                docId,
                docId,
                new ByteArrayInputStream(bytes),
                metadata,
                security.acl(),
                security.security(),
                lastModified,
                DocumentAction.UPSERT
        );
    }

    /**
     * Builds an OIS {@link RepositoryDocument} for a single ticket article ({@code PER_ARTICLE} granularity).
     */
    public static RepositoryDocument buildArticleDocument(
            TicketContext ctx,
            ArticleView view,
            ZammadSecurityMapper.Mapped security
    ) {
        JsonNode article = view.article();
        long articleId = article.path("id").asLong();
        boolean internal = article.path("internal").asBoolean(false);
        String docId = articleDocumentId(ctx.instanceKey(), ctx.ticketId(), articleId);
        String subject = firstNonBlank(text(article, "subject"), ctx.title(), "Ticket #" + ctx.ticketNumber());
        String displayTitle = internal ? subject + " [Internal Note #" + articleId + "]" : subject + " [Article #" + articleId + "]";
        String groupSegment = sanitizePathSegment(ctx.groupName(), "Unassigned");
        String numberSegment = (ctx.ticketNumber() == null || ctx.ticketNumber().isBlank())
                ? Long.toString(ctx.ticketId())
                : ctx.ticketNumber();
        String path = "/Tickets/" + groupSegment + "/" + numberSegment + "/articles/" + articleId + ".html";

        String html = renderTicketThreadHtml(ctx, List.of(view), internal,
                internal ? "Internal Agent Note" : "Public Article", displayTitle);
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        Map<String, List<String>> metadata = baseTicketMetadata(ctx, internal);
        metadata.put("name", List.of("article-" + articleId + ".html"));
        metadata.put("title", List.of(displayTitle));
        metadata.put("mimeType", List.of("text/html"));
        metadata.put("path", List.of(path));
        metadata.put("zammad.entityType", List.of("ticket_article"));
        metadata.put("zammad.articleId", List.of(Long.toString(articleId)));
        metadata.put("zammad.title", List.of(displayTitle));
        put(metadata, "zammad.sender", text(article, "sender"));
        put(metadata, "zammad.articleRole", view.role());
        put(metadata, "zammad.articleType", text(article, "type"));
        put(metadata, "zammad.from", view.author());
        addSecurityMetadata(metadata, security);

        Instant lastModified = parseInstant(firstNonBlank(text(article, "updated_at"), text(article, "created_at"), ctx.updatedAt()))
                .orElseGet(Instant::now);
        return new RepositoryDocument(
                docId,
                docId,
                new ByteArrayInputStream(bytes),
                metadata,
                security.acl(),
                security.security(),
                lastModified,
                DocumentAction.UPSERT
        );
    }

    /**
     * Builds the ticket-level conversation document ({@code TICKET} granularity, #411): every given article in
     * chronological order, public replies and internal notes alike, each marked with its role. The security must be
     * the group-only security of internal notes, because internal content is included.
     */
    public static RepositoryDocument buildTicketConversation(
            TicketContext ctx,
            List<ArticleView> articles,
            ZammadSecurityMapper.Mapped security
    ) {
        String docId = ticketDocumentId(ctx.instanceKey(), ctx.ticketId());
        String groupSegment = sanitizePathSegment(ctx.groupName(), "Unassigned");
        String numberSegment = (ctx.ticketNumber() == null || ctx.ticketNumber().isBlank())
                ? Long.toString(ctx.ticketId())
                : ctx.ticketNumber();
        String path = "/Tickets/" + groupSegment + "/" + numberSegment + "_conversation.html";
        boolean hasInternal = articles.stream().anyMatch(ArticleView::internal);

        String html = renderTicketThreadHtml(ctx, articles, false, "Full Conversation", ctx.title());
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        Map<String, List<String>> metadata = baseTicketMetadata(ctx, false);
        metadata.put("zammad.internal", List.of(Boolean.toString(hasInternal)));
        metadata.put("name", List.of(numberSegment + "_conversation.html"));
        metadata.put("title", List.of(ctx.title()));
        metadata.put("mimeType", List.of("text/html"));
        metadata.put("path", List.of(path));
        metadata.put("zammad.entityType", List.of("ticket_conversation"));
        metadata.put("zammad.title", List.of(ctx.title()));
        metadata.put("zammad.articleCount", List.of(Integer.toString(articles.size())));
        metadata.put("zammad.hasInternalNotes", List.of(Boolean.toString(hasInternal)));
        addSecurityMetadata(metadata, security);

        Instant lastModified = parseInstant(ctx.updatedAt()).orElseGet(Instant::now);
        return new RepositoryDocument(
                docId,
                docId,
                new ByteArrayInputStream(bytes),
                metadata,
                security.acl(),
                security.security(),
                lastModified,
                DocumentAction.UPSERT
        );
    }

    /**
     * Builds an OIS {@link RepositoryDocument} for a binary attachment on a ticket article.
     */
    public static RepositoryDocument buildAttachmentDocument(
            TicketContext ctx,
            AttachmentRef attachment,
            String parentDocId,
            InputStream stream,
            long contentLength,
            ZammadSecurityMapper.Mapped security
    ) {
        String docId = attachmentDocumentId(
                ctx.instanceKey(),
                ctx.ticketId(),
                attachment.articleId(),
                attachment.attachmentId());
        String groupSegment = sanitizePathSegment(ctx.groupName(), "Unassigned");
        String numberSegment = (ctx.ticketNumber() == null || ctx.ticketNumber().isBlank())
                ? Long.toString(ctx.ticketId())
                : ctx.ticketNumber();
        String path = "/Tickets/" + groupSegment + "/" + numberSegment + "/attachments/"
                + attachment.attachmentId() + "_" + sanitizePathSegment(attachment.filename(), "attachment");

        Map<String, List<String>> metadata = baseTicketMetadata(ctx, attachment.internal());
        metadata.put("name", List.of(attachment.filename()));
        metadata.put("title", List.of(attachment.filename()));
        metadata.put("mimeType", List.of(attachment.mimeType()));
        metadata.put("path", List.of(path));
        metadata.put("zammad.entityType", List.of("ticket_attachment"));
        metadata.put("zammad.articleId", List.of(Long.toString(attachment.articleId())));
        metadata.put("zammad.attachmentId", List.of(Long.toString(attachment.attachmentId())));
        metadata.put("zammad.fileName", List.of(attachment.filename()));
        long size = contentLength >= 0 ? contentLength : attachment.sizeBytes();
        if (size >= 0) {
            metadata.put("zammad.fileSize", List.of(Long.toString(size)));
        }
        put(metadata, "zammad.parentId", parentDocId);
        addSecurityMetadata(metadata, security);

        Instant lastModified = parseInstant(ctx.updatedAt()).orElseGet(Instant::now);
        return new RepositoryDocument(
                docId,
                docId,
                stream,
                metadata,
                security.acl(),
                security.security(),
                lastModified,
                DocumentAction.UPSERT
        );
    }

    /**
     * Builds an OIS {@link RepositoryDocument} for a localized Knowledge Base answer.
     */
    public static RepositoryDocument buildKnowledgeBaseAnswer(
            String instanceKey,
            String baseUrl,
            long kbId,
            long answerId,
            long categoryId,
            String categoryTitle,
            String locale,
            String title,
            String bodyHtml,
            boolean internal,
            String updatedAt,
            ZammadSecurityMapper.Mapped security
    ) {
        String cleanLocale = (locale == null || locale.isBlank()) ? "en" : locale.strip();
        String docId = kbAnswerDocumentId(instanceKey, kbId, answerId, cleanLocale);
        String cleanCategory = (categoryTitle == null || categoryTitle.isBlank()) ? "Category-" + categoryId : categoryTitle;
        String cleanTitle = (title == null || title.isBlank()) ? "KB Answer #" + answerId : title;
        String path = "/KnowledgeBase/" + sanitizePathSegment(cleanCategory, "General")
                + "/" + answerId + "_" + cleanLocale + ".html";

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>")
                .append(escapeHtml(cleanTitle))
                .append("</title></head><body>");
        html.append("<article data-kb-id=\"").append(kbId)
                .append("\" data-answer-id=\"").append(answerId)
                .append("\" data-locale=\"").append(escapeHtml(cleanLocale)).append("\">");
        html.append("<h1>").append(escapeHtml(cleanTitle)).append("</h1>");
        html.append("<p><strong>Category:</strong> ").append(escapeHtml(cleanCategory))
                .append(" | <strong>Visibility:</strong> ").append(internal ? "internal" : "published").append("</p>");
        html.append("<div class=\"kb-body\">").append(bodyHtml == null ? "" : bodyHtml).append("</div>");
        html.append("</article></body></html>");

        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("name", List.of(answerId + "_" + cleanLocale + ".html"));
        metadata.put("title", List.of(cleanTitle));
        metadata.put("mimeType", List.of("text/html"));
        metadata.put("path", List.of(path));
        metadata.put("zammad.entityType", List.of("kb_answer"));
        metadata.put("zammad.kbId", List.of(Long.toString(kbId)));
        metadata.put("zammad.answerId", List.of(Long.toString(answerId)));
        metadata.put("zammad.categoryId", List.of(Long.toString(categoryId)));
        metadata.put("zammad.categoryTitle", List.of(cleanCategory));
        metadata.put("zammad.locale", List.of(cleanLocale));
        metadata.put("zammad.title", List.of(cleanTitle));
        metadata.put("zammad.visibility", List.of(internal ? "internal" : "published"));
        metadata.put("zammad.internal", List.of(Boolean.toString(internal)));
        put(metadata, "zammad.updatedAt", updatedAt);
        metadata.put("zammad.webUrl", List.of(baseUrl + "/#knowledge_base/" + kbId + "/locale/" + cleanLocale + "/answer/" + answerId));
        addSecurityMetadata(metadata, security);

        Instant lastModified = parseInstant(updatedAt).orElseGet(Instant::now);
        return new RepositoryDocument(
                docId,
                docId,
                new ByteArrayInputStream(html.toString().getBytes(StandardCharsets.UTF_8)),
                metadata,
                security.acl(),
                security.security(),
                lastModified,
                DocumentAction.UPSERT
        );
    }

    /**
     * Builds an OIS {@link DocumentAction#DELETE} tombstone document.
     */
    public static RepositoryDocument tombstone(String docId, String ticketOrAnswerId, String entityType) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        if (ticketOrAnswerId != null && !ticketOrAnswerId.isBlank()) {
            if ("kb_answer".equals(entityType)) {
                metadata.put("zammad.answerId", List.of(ticketOrAnswerId));
            } else {
                metadata.put("zammad.ticketId", List.of(ticketOrAnswerId));
            }
        }
        if (entityType != null && !entityType.isBlank()) {
            metadata.put("zammad.entityType", List.of(entityType));
        }
        metadata.put("zammad.status", List.of("DELETED"));
        return new RepositoryDocument(
                docId,
                docId,
                null,
                metadata,
                "",
                SecurityConfig.createPublic(),
                Instant.now(),
                DocumentAction.DELETE
        );
    }

    private static Map<String, List<String>> baseTicketMetadata(TicketContext ctx, boolean internal) {
        Map<String, List<String>> metadata = new LinkedHashMap<>();
        metadata.put("zammad.ticketId", List.of(Long.toString(ctx.ticketId())));
        put(metadata, "zammad.ticketNumber", ctx.ticketNumber());
        put(metadata, "zammad.group", ctx.groupName());
        put(metadata, "zammad.groupId", ctx.groupId());
        put(metadata, "zammad.state", ctx.stateName());
        put(metadata, "zammad.priority", ctx.priorityName());
        if (!internal) {
            put(metadata, "zammad.customer", ctx.customerLabel());
            put(metadata, "zammad.organization", ctx.organizationName());
        }
        put(metadata, "zammad.owner", ctx.ownerLabel());
        if (ctx.tags() != null && !ctx.tags().isEmpty()) {
            metadata.put("zammad.tags", List.of(String.join(",", ctx.tags())));
        }
        metadata.put("zammad.internal", List.of(Boolean.toString(internal)));
        put(metadata, "zammad.createdAt", ctx.createdAt());
        put(metadata, "zammad.updatedAt", ctx.updatedAt());
        put(metadata, "zammad.closeAt", ctx.closeAt());
        metadata.put("zammad.webUrl", List.of(ctx.webUrl()));
        addParticipantMetadata(metadata, ctx);
        return metadata;
    }

    /** {@code zammad.participants} (display names) and, when asked, {@code zammad.participantIds} (#414). */
    private static void addParticipantMetadata(Map<String, List<String>> metadata, TicketContext ctx) {
        if (ctx.participantMetadata() == ZammadProperties.ParticipantMetadata.NONE || ctx.participants().isEmpty()) {
            return;
        }
        List<String> names = ctx.participants().stream()
                .map(Participant::displayName)
                .filter(n -> n != null && !n.isBlank())
                .distinct()
                .toList();
        if (!names.isEmpty()) {
            metadata.put("zammad.participants", names);
        }
        if (ctx.participantMetadata() == ZammadProperties.ParticipantMetadata.NAMES_AND_IDS) {
            List<String> ids = ctx.participants().stream()
                    .map(Participant::identity)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
            if (!ids.isEmpty()) {
                metadata.put("zammad.participantIds", ids);
            }
        }
    }

    private static void addSecurityMetadata(Map<String, List<String>> metadata, ZammadSecurityMapper.Mapped security) {
        if (!security.users().isEmpty()) {
            metadata.put("zammad_identity_users", List.of(String.join(",", security.users())));
        }
        if (!security.groups().isEmpty()) {
            metadata.put("zammad_identity_groups", List.of(String.join(",", security.groups())));
        }
    }

    private static String renderTicketThreadHtml(
            TicketContext ctx,
            List<ArticleView> articles,
            boolean internal,
            String visibilityLabel,
            String displayTitle
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>")
                .append(escapeHtml(displayTitle))
                .append("</title></head><body>");
        sb.append("<header>");
        sb.append("<h1>Ticket #").append(escapeHtml(ctx.ticketNumber() != null ? ctx.ticketNumber() : Long.toString(ctx.ticketId())))
                .append(": ").append(escapeHtml(displayTitle)).append("</h1>");
        sb.append("<ul>");
        if (ctx.groupName() != null) {
            sb.append("<li><strong>Group:</strong> ").append(escapeHtml(ctx.groupName())).append("</li>");
        }
        if (ctx.stateName() != null) {
            sb.append("<li><strong>State:</strong> ").append(escapeHtml(ctx.stateName())).append("</li>");
        }
        if (ctx.priorityName() != null) {
            sb.append("<li><strong>Priority:</strong> ").append(escapeHtml(ctx.priorityName())).append("</li>");
        }
        if (!internal && ctx.customerLabel() != null) {
            sb.append("<li><strong>Customer:</strong> ").append(escapeHtml(ctx.customerLabel())).append("</li>");
        }
        if (!internal && ctx.organizationName() != null) {
            sb.append("<li><strong>Organization:</strong> ").append(escapeHtml(ctx.organizationName())).append("</li>");
        }
        if (ctx.ownerLabel() != null) {
            sb.append("<li><strong>Owner:</strong> ").append(escapeHtml(ctx.ownerLabel())).append("</li>");
        }
        if (ctx.tags() != null && !ctx.tags().isEmpty()) {
            sb.append("<li><strong>Tags:</strong> ").append(escapeHtml(String.join(", ", ctx.tags()))).append("</li>");
        }
        sb.append("<li><strong>Visibility:</strong> ").append(escapeHtml(visibilityLabel)).append("</li>");
        sb.append("</ul></header><hr/>");

        for (ArticleView view : articles) {
            JsonNode article = view.article();
            long articleId = article.path("id").asLong();
            String sender = textOrDefault(article, "sender", "Agent");
            String author = view.author();
            String subject = text(article, "subject");
            String createdAt = textOrDefault(article, "created_at", "");
            String contentType = textOrDefault(article, "content_type", "text/plain");
            String body = textOrDefault(article, "body", "");

            sb.append("<section data-article-id=\"").append(articleId)
                    .append("\" data-internal=\"").append(view.internal())
                    .append("\" data-role=\"").append(escapeHtml(view.role()))
                    .append("\" data-visibility=\"").append(view.internal() ? "internal" : "public")
                    .append("\" data-sender=\"").append(escapeHtml(sender)).append("\"");
            String type = text(article, "type");
            if (type != null) {
                sb.append(" data-type=\"").append(escapeHtml(type)).append("\"");
            }
            if (author != null && !author.isBlank()) {
                sb.append(" data-author=\"").append(escapeHtml(author)).append("\"");
            }
            if (!createdAt.isEmpty()) {
                sb.append(" data-created=\"").append(escapeHtml(createdAt)).append("\"");
            }
            sb.append(">");
            sb.append("<h2>[").append(escapeHtml(view.roleLabel())).append("]");
            if (author != null && !author.isBlank()) {
                sb.append(" ").append(escapeHtml(author));
            }
            if (!createdAt.isEmpty()) {
                sb.append(" (").append(escapeHtml(createdAt)).append(")");
            }
            sb.append("</h2>");
            if (subject != null && !subject.isBlank()) {
                sb.append("<h3>").append(escapeHtml(subject)).append("</h3>");
            }
            if (contentType.toLowerCase(Locale.ROOT).contains("html")) {
                sb.append("<div class=\"article-body\">").append(body).append("</div>");
            } else {
                sb.append("<pre class=\"article-body\">").append(escapeHtml(body)).append("</pre>");
            }
            sb.append("</section><hr/>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    public static Optional<Instant> parseInstant(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(timestamp.strip()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    public static String guessMimeType(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return EXTENSION_MIME_TYPES.get(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    static String sanitizePathSegment(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String s = value.strip().replaceAll("[/\\\\]+", "_");
        return s.isEmpty() ? fallback : s;
    }

    static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String text(JsonNode node, String field) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        JsonNode val = node.path(field);
        if (val.isMissingNode() || val.isNull()) {
            return null;
        }
        String text = val.asText("");
        return text.isBlank() ? null : text.strip();
    }

    static String textOrDefault(JsonNode node, String field, String fallback) {
        String val = text(node, field);
        return val != null ? val : fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static void put(Map<String, List<String>> metadata, String key, String value) {
        if (value != null && !value.isBlank()) {
            metadata.put(key, List.of(value));
        }
    }
}
