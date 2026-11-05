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
package org.opencrawling.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Minimal HTML parsing with jsoup (spec §5.5): metadata, outlinks and robots directives.
 * Full text extraction stays in the IngestionConsumer (Tika).
 */
public final class HtmlPageParser {

    /** An outlink found on a page. {@code url} is absolute but not yet normalized. */
    public record Link(String url, String anchorText, boolean nofollow) {}

    public record ParsedPage(
        String title,
        String description,
        String language,
        String keywords,
        String canonicalUrl,
        String modifiedTime,
        boolean noindex,
        boolean nofollow,
        String text,
        List<Link> links
    ) {}

    private final String robotsAgentName;
    private final boolean followFrames;

    public HtmlPageParser(String robotsAgentName, boolean followFrames) {
        this.robotsAgentName = robotsAgentName.toLowerCase(Locale.ROOT);
        this.followFrames = followFrames;
    }

    /**
     * @param charset charset from the Content-Type header, or null to let jsoup detect it
     * @param xRobotsTag value of the X-Robots-Tag header, or null
     */
    public ParsedPage parse(byte[] body, String charset, String url, String xRobotsTag) throws IOException {
        Document doc = Jsoup.parse(new ByteArrayInputStream(body), charset, url);

        String title = doc.title();
        if (title.isBlank()) {
            title = meta(doc, "meta[property=og:title]");
        }
        String description = meta(doc, "meta[name=description]");
        if (description == null) {
            description = meta(doc, "meta[property=og:description]");
        }
        String language = doc.selectFirst("html[lang]") != null ? doc.selectFirst("html[lang]").attr("lang").trim() : null;
        Element canonical = doc.selectFirst("link[rel=canonical][href]");

        String agentMeta = robotsAgentName.matches("[a-z0-9_-]+") ? meta(doc, "meta[name=" + robotsAgentName + "]") : null;
        Set<String> directives = new HashSet<>(Arrays.asList(String.join(",",
                nullToEmpty(meta(doc, "meta[name=robots]")), nullToEmpty(agentMeta), xRobotsDirectives(xRobotsTag))
            .toLowerCase(Locale.ROOT).split("[,\\s]+")));
        boolean none = directives.contains("none");

        String linkSelector = followFrames ? "a[href], area[href], frame[src], iframe[src]" : "a[href], area[href]";
        List<Link> links = new ArrayList<>();
        for (Element element : doc.select(linkSelector)) {
            String attribute = element.hasAttr("href") ? "href" : "src";
            String absolute = element.absUrl(attribute);
            if (absolute.isEmpty()) {
                continue;
            }
            boolean nofollow = element.attr("rel").toLowerCase(Locale.ROOT).contains("nofollow");
            links.add(new Link(absolute, element.text().trim(), nofollow));
        }

        return new ParsedPage(
            blankToNull(title),
            description,
            blankToNull(language),
            meta(doc, "meta[name=keywords]"),
            canonical != null ? blankToNull(canonical.absUrl("href")) : null,
            meta(doc, "meta[property=article:modified_time]"),
            none || directives.contains("noindex"),
            none || directives.contains("nofollow"),
            doc.text(),
            links);
    }

    /**
     * X-Robots-Tag may be scoped to a user agent ("otherbot: noindex"); only unscoped values and values for
     * this crawler apply.
     */
    private String xRobotsDirectives(String header) {
        if (header == null) {
            return "";
        }
        String value = header.toLowerCase(Locale.ROOT).trim();
        int colon = value.indexOf(':');
        if (colon > 0 && !value.substring(0, colon).contains(",")) {
            String agent = value.substring(0, colon).trim();
            return agent.equals(robotsAgentName) ? value.substring(colon + 1) : "";
        }
        return value;
    }

    private static String meta(Document doc, String selector) {
        Element element = doc.selectFirst(selector);
        return element != null ? blankToNull(element.attr("content")) : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
