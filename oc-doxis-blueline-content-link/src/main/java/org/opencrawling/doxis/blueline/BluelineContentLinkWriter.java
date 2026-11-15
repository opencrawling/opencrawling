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
package org.opencrawling.doxis.blueline;

import com.ser.blueline.ContentLinkType;
import com.ser.blueline.IDatabase;
import com.ser.blueline.IDocument;
import com.ser.blueline.IDocumentServer;
import com.ser.blueline.ISERFactory;
import com.ser.blueline.ISerClassFactory;
import com.ser.blueline.ISession;
import com.ser.blueline.ISystem;
import com.ser.blueline.metaDataComponents.IArchiveClass;
import org.opencrawling.doxis.output.content.ContentLinkWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ContentLinkWriter} built on the SER Doxis Java (Blueline) API, verified against CSB 14.4.1 with client 14.4.1-1:
 * creates a document in a document class that allows content links ("Allow linking contents"), sets its descriptors and adds
 * an external UNC/URL link with {@code IDocument.addContentLinkPartDocument(0, type, link)} — the binary is never transferred
 * to or stored by Doxis. The client speaks SOAP to {@code http://<host>:<port>/sedna-transfer-service-xf/services/}.
 *
 * <p>One Blueline session is kept per writer and calls are serialized; an invalid session triggers one re-login.
 */
public class BluelineContentLinkWriter implements ContentLinkWriter {

    private static final Logger log = LoggerFactory.getLogger(BluelineContentLinkWriter.class);
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Map<String, String> settings;
    private ISerClassFactory factory;
    private IDocumentServer server;
    private ISession session;

    @Override
    public synchronized void configure(Map<String, String> settings) {
        this.settings = Map.copyOf(settings);
    }

    @Override
    public synchronized String createLinkedDocument(Request request) throws Exception {
        try {
            return create(request);
        } catch (Exception e) {
            if (!isSessionProblem(e)) {
                throw e;
            }
            log.info("Doxis Blueline session invalid ({}), logging in again.", e.getMessage());
            close();
            return create(request);
        }
    }

    private String create(Request request) throws Exception {
        ISession s = session();
        IArchiveClass documentClass = server.getArchiveClass(request.documentTypeId(), s);
        if (documentClass == null) {
            throw new IllegalArgumentException("Doxis document class " + request.documentTypeId() + " not found");
        }
        if (!documentClass.areContentLinksAllowed()) {
            throw new IllegalStateException("Doxis document class '" + documentClass.getName()
                    + "' does not allow content links (enable \"Allow linking contents\" in Doxis Designer)");
        }
        IDatabase database = s.getDatabaseByName(request.repository());
        if (database == null) {
            throw new IllegalArgumentException("Doxis repository (database) '" + request.repository() + "' not found");
        }
        IDocument document = factory.getDocumentInstance(s, documentClass, database, new Date());
        for (Descriptor descriptor : request.descriptors()) {
            setDescriptor(document, descriptor);
        }
        document.addContentLinkPartDocument(0, ContentLinkType.valueOf(request.linkType().name()), request.link());
        server.archiveDocument(document, s);
        String documentId = documentUuid(document.getID());
        log.debug("Created Doxis content-link document {} -> {} {}", documentId, request.linkType(), request.link());
        return documentId;
    }

    private static void setDescriptor(IDocument document, Descriptor descriptor) {
        List<Object> values = new ArrayList<>();
        for (Object value : descriptor.values()) {
            values.add(typed(descriptor.dataType(), value));
        }
        if (values.isEmpty()) {
            return;
        }
        if (values.size() == 1) {
            Object value = values.getFirst();
            if (value instanceof String text) {
                document.setDescriptorValue(descriptor.attributeDefinitionUuid(), text);
            } else {
                document.setDescriptorValueTyped(descriptor.attributeDefinitionUuid(), value);
            }
        } else {
            document.setDescriptorValues(descriptor.attributeDefinitionUuid(), values);
        }
    }

    /** REST-style values (dates as epoch millis) to Blueline types. */
    static Object typed(String dataType, Object value) {
        if (value == null) {
            return null;
        }
        return switch (dataType == null ? "STRING" : dataType) {
            case "DATE", "DATETIME" -> value instanceof Number n ? new Date(n.longValue()) : value;
            case "INTEGER", "LONGINTEGER", "FLOATINGPOINT", "BOOL" -> value;
            default -> String.valueOf(value);
        };
    }

    /**
     * Blueline returns a compound id such as {@code SD08D_TEXTER24<uuid>18<instance date>011}; the REST API addresses the
     * document by the embedded UUID.
     */
    static String documentUuid(String bluelineId) {
        if (bluelineId == null) {
            throw new IllegalStateException("Doxis returned no document id");
        }
        Matcher matcher = UUID.matcher(bluelineId);
        if (!matcher.find()) {
            throw new IllegalStateException("No document UUID in Doxis id " + bluelineId);
        }
        return matcher.group().toLowerCase();
    }

    private ISession session() throws Exception {
        if (session != null) {
            return session;
        }
        String host = require("host");
        String port = settings.getOrDefault("port", "8080");
        StringBuilder ini = new StringBuilder();
        for (String section : new String[]{"Global", "SEDNA", "Doxis4"}) {
            ini.append('[').append(section).append("]\n")
                    .append("SeratioServerName=").append(host).append('\n').append("SeratioPort=").append(port).append('\n')
                    .append("ArchivServerName=").append(host).append('\n').append("ArchivPort=").append(port).append('\n');
        }
        factory = ISERFactory.getCSBFactoryInstance();
        server = factory.getDocumentServerInstance(new ByteArrayInputStream(ini.toString().getBytes(StandardCharsets.UTF_8)));
        ISystem system = null;
        for (ISystem candidate : server.getSystems()) {
            if (require("customer").equalsIgnoreCase(candidate.getName())) {
                system = candidate;
            }
        }
        if (system == null) {
            throw new IllegalArgumentException("Doxis customer '" + require("customer") + "' not found on " + host + ":" + port);
        }
        session = server.createSession(server.login(system, require("username"), require("password").toCharArray()));
        log.info("Doxis Blueline session opened on {}:{} as {} (customer {}).", host, port, require("username"), require("customer"));
        return session;
    }

    private String require(String key) {
        String value = settings == null ? null : settings.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Doxis content-link writer setting '" + key + "' is missing");
        }
        return value;
    }

    private static boolean isSessionProblem(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getSimpleName();
            String message = String.valueOf(t.getMessage()).toLowerCase();
            if (name.contains("Authentication") || message.contains("session") && (message.contains("invalid") || message.contains("expired"))
                    || message.contains("ticket is not valid")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void close() {
        if (server != null && session != null) {
            try {
                server.logout(session);
                log.info("Doxis Blueline session closed.");
            } catch (Exception e) {
                log.debug("Doxis Blueline logout failed: {}", e.getMessage());
            }
        }
        session = null;
    }
}
