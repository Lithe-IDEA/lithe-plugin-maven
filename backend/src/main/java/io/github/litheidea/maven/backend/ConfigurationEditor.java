package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Cancellation;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.helpers.DefaultHandler;

/** Maven owns settings syntax; host-owned staging keeps private documents off RPC. */
final class ConfigurationEditor {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static Map<String, Object> edit(Inputs inputs, Path state, Map<String, Object> params, Cancellation cancellation) throws Exception {
        Map<String, Object> snapshot = Json.object(params.get("snapshot"));
        Map<String, Object> edits = Json.object(params.get("edits"));
        String sourceID = Json.string(edits, "sourceID"), revision = Json.string(edits, "revision");
        Map<String, Object> selected = source(snapshot, sourceID);
        Map<String, Object> current = source(ConfigurationProbe.read(inputs, state, cancellation), sourceID);
        if (!selected.equals(current) || !revision.equals(current.get("revision"))) throw NativeSources.changed();
        if (!Boolean.TRUE.equals(current.get("writable")) || "installation".equals(current.get("scope"))) throw invalid();
        Path source = Paths.get(new URI(Json.string(current, "uri")));
        if (!NativeSources.current(Arrays.asList(current))) throw NativeSources.changed();
        byte[] original = "missing".equals(revision) ? null : Files.readAllBytes(source);
        if (original != null && original.length > MAX_BYTES) throw invalid();
        Map<String, Object> values = Json.object(edits.get("values"));
        if (values.size() > 2) throw invalid();
        boolean changed = false;
        for (Map.Entry<String, Object> edit : values.entrySet()) {
            Map<String, Object> field = field(current, edit.getKey());
            Object value = edit.getValue();
            if ("localRepository".equals(edit.getKey())) {
                if (value != null && (!(value instanceof String) || ((String) value).length() > 8192
                        || ((String) value).indexOf('\0') >= 0 || ((String) value).indexOf('\n') >= 0 || ((String) value).indexOf('\r') >= 0)) throw invalid();
            } else if ("activeProfiles".equals(edit.getKey())) {
                if (value != null) {
                    if (!(value instanceof List) || ((List<?>) value).size() > 64) throw invalid();
                    for (Object profile : (List<?>) value) {
                        if (!(profile instanceof String) || ((String) profile).length() > 8192
                                || ((String) profile).indexOf('\0') >= 0 || ((String) profile).indexOf('\n') >= 0 || ((String) profile).indexOf('\r') >= 0) throw invalid();
                    }
                }
            } else throw invalid();
            changed |= value == null ? field.get("value") != null : !value.equals(field.get("value"));
        }
        if (!changed) return receipt(sourceID, revision, false);
        byte[] replacement;
        try { replacement = replace(original, values); }
        catch (Exception error) { throw new PluginError("maven.configurationEdit", "Maven settings cannot be edited safely. The original file was preserved."); }
        cancellation.throwIfCancelled();
        if (!NativeSources.current(Arrays.asList(current))) throw NativeSources.changed();
        Path output = Paths.get(Json.string(params, "outputFile"));
        if (!output.isAbsolute() || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                || source.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())) throw invalid();
        // No CREATE: a cancelled host may have removed this staging file. A late
        // operation must fail rather than recreate a file containing secrets.
        try (SeekableByteChannel channel = Files.newByteChannel(output, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != 0) throw invalid();
            ByteBuffer bytes = ByteBuffer.wrap(replacement);
            while (bytes.hasRemaining()) { cancellation.throwIfCancelled(); channel.write(bytes); }
        }
        cancellation.throwIfCancelled();
        return receipt(sourceID, digest(replacement), true);
    }

    static byte[] replace(byte[] original, Map<String, Object> values) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "64");
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler());
        Document document = original == null ? builder.newDocument() : builder.parse(new ByteArrayInputStream(original));
        if (original == null) document.appendChild(document.createElementNS("http://maven.apache.org/SETTINGS/1.0.0", "settings"));
        Element root = document.getDocumentElement();
        if (!"settings".equals(local(root))) throw invalid();
        for (Map.Entry<String, Object> edit : values.entrySet()) {
            Element field = direct(root, edit.getKey());
            Object value = edit.getValue();
            if (value == null) {
                if (field != null) {
                    // Preserve comments; refuse unknown content rather than deleting it.
                    for (Node child : children(field)) {
                        if (child.getNodeType() == Node.COMMENT_NODE) root.insertBefore(child, field);
                        else if (child.getNodeType() == Node.ELEMENT_NODE && !("activeProfiles".equals(edit.getKey()) && "activeProfile".equals(local(child)))) throw invalid();
                    }
                    root.removeChild(field);
                }
                continue;
            }
            if (field == null) { field = element(document, root, edit.getKey()); root.appendChild(field); }
            if ("localRepository".equals(edit.getKey())) {
                for (Node child : children(field)) {
                    if (child.getNodeType() == Node.ELEMENT_NODE) throw invalid();
                    if (child.getNodeType() != Node.COMMENT_NODE) field.removeChild(child);
                }
                field.appendChild(document.createTextNode((String) value));
            } else if ("activeProfiles".equals(edit.getKey())) {
                for (Node child : children(field)) {
                    if (child.getNodeType() == Node.ELEMENT_NODE) {
                        if (!"activeProfile".equals(local(child)) || !sameNamespace(root, child)) throw invalid();
                        field.removeChild(child);
                    }
                }
                for (Object profile : (List<?>) value) {
                    Element item = element(document, root, "activeProfile");
                    item.appendChild(document.createTextNode((String) profile)); field.appendChild(item);
                }
            } else throw invalid();
        }
        TransformerFactory transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = transformers.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (original != null && original.length >= 3 && original[0] == (byte) 0xef && original[1] == (byte) 0xbb && original[2] == (byte) 0xbf) {
            output.write(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
        }
        transformer.transform(new DOMSource(document), new StreamResult(output));
        byte[] bytes = output.toByteArray();
        if (bytes.length > MAX_BYTES) throw invalid();
        // Let the same secure upstream parser reject invalid XML characters
        // introduced by a value before any replacement can be published.
        builder.parse(new ByteArrayInputStream(bytes));
        return bytes;
    }

    private static List<Node> children(Node parent) {
        List<Node> nodes = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) nodes.add(node);
        return nodes;
    }
    private static Element direct(Element parent, String name) {
        Element found = null;
        for (Node node : children(parent)) if (node.getNodeType() == Node.ELEMENT_NODE && name.equals(local(node)) && sameNamespace(parent, node)) {
            if (found != null) throw invalid(); found = (Element) node;
        }
        return found;
    }
    private static Element element(Document document, Element root, String name) {
        String prefix = root.getPrefix();
        return document.createElementNS(root.getNamespaceURI(), prefix == null ? name : prefix + ":" + name);
    }
    private static String local(Node node) { return node.getLocalName() == null ? node.getNodeName() : node.getLocalName(); }
    private static boolean sameNamespace(Node a, Node b) { return a.getNamespaceURI() == null ? b.getNamespaceURI() == null : a.getNamespaceURI().equals(b.getNamespaceURI()); }
    private static Map<String, Object> source(Map<String, Object> snapshot, String id) {
        Object sources = snapshot.get("sources");
        if (!(sources instanceof List) || ((List<?>) sources).size() > 8) throw invalid();
        for (Object value : (List<?>) sources) { Map<String, Object> source = Json.object(value); if (id.equals(source.get("id"))) return source; }
        throw invalid();
    }
    private static Map<String, Object> field(Map<String, Object> source, String id) {
        for (Object value : (List<?>) source.get("fields")) { Map<String, Object> field = Json.object(value); if (id.equals(field.get("id"))) return field; }
        throw invalid();
    }
    private static String digest(byte[] bytes) throws Exception {
        StringBuilder text = new StringBuilder("sha256:");
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }
    private static Map<String, Object> receipt(String id, String revision, boolean changed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceID", id); result.put("revision", revision); result.put("changed", changed); return result;
    }
    private static PluginError invalid() { return new PluginError("maven.configurationEdit", "Maven configuration edit is invalid or unsafe."); }
    private ConfigurationEditor() {}
}
