package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.CallContext;
import io.github.litheidea.plugin.sdk.PluginError;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Evidence that Surefire actually ran an explicit selector, not an empty successful reactor. */
final class MavenTestSelection {
    private static final int MAX_REPORT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_REPORTS = 10000;
    private final Map<Path, Stamp> before;
    private final List<Path> roots;
    private final String suite;
    private final String method;

    MavenTestSelection(List<Path> roots, String suite, String method) throws IOException {
        this.roots = roots;
        this.suite = suite;
        this.method = method;
        before = reports(roots);
    }

    void verify(CallContext context) throws IOException {
        if (suite == null) return;
        for (Map.Entry<Path, Stamp> entry : reports(roots).entrySet()) {
            context.throwIfCancelled();
            if (entry.getValue().same(before.get(entry.getKey()))) continue;
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                NodeList tests = factory.newDocumentBuilder().parse(new ByteArrayInputStream(entry.getValue().bytes)).getElementsByTagName("testcase");
                for (int index = 0; index < tests.getLength(); index++) {
                    Element test = (Element) tests.item(index);
                    String name = test.getAttribute("classname");
                    if ((suite.equals(name) || (!suite.contains(".") && name.endsWith("." + suite))) && (method == null || method.equals(test.getAttribute("name")))) return;
                }
            } catch (ParserConfigurationException | SAXException | IllegalArgumentException error) {
                throw new PluginError("maven.invalidTestReport", "Surefire produced an unreadable test report.", error);
            }
        }
        throw new PluginError("maven.noMatchingTests", "Surefire produced no fresh result for the selected class or method.");
    }

    private static Map<Path, Stamp> reports(List<Path> roots) throws IOException {
        Map<Path, Stamp> result = new LinkedHashMap<>();
        long total = 0;
        int visited = 0;
        for (Path root : roots) {
            Path directory = root.resolve("target/surefire-reports");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            Path real = directory.toRealPath();
            if (!real.startsWith(root)) throw new PluginError("maven.invalidTestReport", "Surefire reports leave the selected project.");
            try (Stream<Path> files = Files.list(real)) {
                for (Path file : (Iterable<Path>) files.limit(MAX_REPORTS + 1L).sorted()::iterator) {
                    if (++visited > MAX_REPORTS) throw new PluginError("maven.invalidTestReport", "Surefire reports exceed the verification budget.");
                    String name = file.getFileName().toString();
                    if (!name.startsWith("TEST-") || !name.endsWith(".xml") || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (result.size() >= MAX_REPORTS || attributes.size() > MAX_REPORT_BYTES - total) throw new PluginError("maven.invalidTestReport", "Surefire reports exceed the verification budget.");
                    byte[] bytes = boundedRead(file, (int) (MAX_REPORT_BYTES - total));
                    total += bytes.length;
                    result.put(file, new Stamp(attributes, bytes));
                }
            }
        }
        return result;
    }

    private static byte[] boundedRead(Path file, int remaining) throws IOException {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                if (count > remaining - output.size()) throw new PluginError("maven.invalidTestReport", "Surefire reports exceed the verification budget.");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static final class Stamp {
        final BasicFileAttributes attributes;
        final byte[] bytes;
        Stamp(BasicFileAttributes attributes, byte[] bytes) { this.attributes = attributes; this.bytes = bytes; }
        boolean same(Stamp other) {
            return other != null && attributes.lastModifiedTime().equals(other.attributes.lastModifiedTime())
                    && java.util.Objects.equals(attributes.fileKey(), other.attributes.fileKey()) && Arrays.equals(bytes, other.bytes);
        }
    }
}
