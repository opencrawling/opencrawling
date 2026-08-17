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
package org.opencrawling.migrator.mcf.mcf.parse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts ManifoldCF's native combined configuration XML export (as produced by its
 * {@code ExportConfiguration} tool) into the exact same {@code _type_}/{@code _value_}/
 * {@code _children_}/{@code _attribute_*} {@link com.fasterxml.jackson.databind.JsonNode} shape
 * {@link McfJsonNodes} already navigates — so {@link McfEntityParser}'s parsing logic runs
 * completely unchanged regardless of whether a connection/job came from the live REST API, a saved
 * JSON snapshot, or this XML export.
 *
 * <p>ManifoldCF's {@code Configuration#toJSON()} (which the REST API and this project's JSON
 * fixtures are built from and verified against) and {@code Configuration#toXML()} both serialize
 * the same internal {@code ConfigurationNode} tree, just to different wire formats — the node-type
 * vocabulary (element/key names like {@code class_name}, {@code repository_connection},
 * {@code document_specification}, ...) is the same either way. This adapter has <b>not</b> been
 * verified against a real ManifoldCF XML export file (only against the JSON encoding, captured
 * from a real instance) — it's built on that structural-parity assumption. Spot-check against your
 * own ManifoldCF's actual {@code ExportConfiguration} output before relying on this in production.
 *
 * <p>Every non-empty XML element becomes exactly one JSON node: its attributes become
 * {@code _attribute_<name>} keys; if it has child elements, they become an order-preserving
 * {@code _children_} array (each tagged with its own element name as {@code _type_}); otherwise
 * its trimmed text content (if any) becomes {@code _value_}. Always emits the "alternate form"
 * {@code McfJsonNodes} describes — never the collapsed form — since alternate form is
 * unambiguous and every existing parsing method already handles it.
 */
public final class McfXmlToJsonAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private McfXmlToJsonAdapter() {
    }

    /**
     * Parses the whole export document and groups its top-level entries (one JSON item node per
     * XML child of the document root) by tag name — e.g. {@code "repositoryconnection"},
     * {@code "outputconnection"}, {@code "authorityconnection"}, {@code "transformationconnection"},
     * {@code "job"} — matching the wrapper keys the live REST API uses for the same entity kinds.
     */
    public static Map<String, List<com.fasterxml.jackson.databind.JsonNode>> parseExport(InputStream xml) throws IOException {
        Document document = parseDocument(xml);
        Element root = document.getDocumentElement();

        Map<String, List<com.fasterxml.jackson.databind.JsonNode>> itemsByTag = new LinkedHashMap<>();
        for (Element child : childElements(root)) {
            itemsByTag.computeIfAbsent(child.getTagName(), key -> new ArrayList<>()).add(elementToNode(child));
        }
        return itemsByTag;
    }

    private static Document parseDocument(InputStream xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(xml);
        } catch (Exception e) {
            throw new IOException("Failed to parse ManifoldCF XML export: " + e.getMessage(), e);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode elementToNode(Element element) {
        ObjectNode node = OBJECT_MAPPER.createObjectNode();

        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Attr attribute = (Attr) attributes.item(i);
            node.put("_attribute_" + attribute.getName(), attribute.getValue());
        }

        List<Element> children = childElements(element);
        if (!children.isEmpty()) {
            ArrayNode childArray = OBJECT_MAPPER.createArrayNode();
            for (Element child : children) {
                ObjectNode childNode = (ObjectNode) elementToNode(child);
                childNode.put("_type_", child.getTagName());
                childArray.add(childNode);
            }
            node.set("_children_", childArray);
        } else {
            String text = element.getTextContent();
            if (text != null && !text.isBlank()) {
                node.put("_value_", text.trim());
            }
        }
        return node;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) child);
            }
        }
        return result;
    }
}
