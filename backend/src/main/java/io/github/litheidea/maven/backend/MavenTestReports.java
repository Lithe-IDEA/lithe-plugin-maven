package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.CallContext;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.apache.maven.plugin.surefire.log.api.NullConsoleLogger;
import org.apache.maven.plugins.surefire.report.ReportTestCase;
import org.apache.maven.plugins.surefire.report.ReportTestSuite;
import org.apache.maven.plugins.surefire.report.TestSuiteXmlParser;
import org.xml.sax.helpers.DefaultHandler;

/** A private operation snapshot parsed by Apache's own Surefire report parser. */
final class MavenTestReports implements AutoCloseable {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final Path directory;
    private final Path workspace;

    MavenTestReports(Path state, Path workspace) throws IOException {
        this.workspace = workspace.toRealPath();
        directory = state == null ? Files.createTempDirectory("lithe-maven-execution-") : Files.createTempDirectory(state, "execution-");
        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put("format", "litheMavenOperationReports"); initial.put("version", 1);
        initial.put("reports", Collections.emptyList());
        Files.write(directory.resolve("index.json"), Json.stringify(initial).getBytes(StandardCharsets.UTF_8));
    }

    void instrument(List<String> command, Path extension) {
        command.add("-Dmaven.ext.class.path=" + extension);
        command.add("-Dlithe.reportOutput=" + directory.resolve("index.json"));
        command.add("-Dlithe.reportWorkspace=" + workspace);
    }

    Map<String, Object> read(CallContext context, String suite, String method, boolean requireMatch) throws Exception {
        Map<String, Object> index = Json.object(Json.parse(new String(read(directory.resolve("index.json"), MAX_BYTES), StandardCharsets.UTF_8)));
        if (!"litheMavenOperationReports".equals(index.get("format")) || !(index.get("version") instanceof Number)
                || ((Number) index.get("version")).intValue() != 1 || index.containsKey("error") || !(index.get("reports") instanceof List)) {
            throw invalid("Maven could not capture its current test reports.");
        }
        List<?> files = (List<?>) index.get("reports");
        if (files.size() > 10000) throw invalid("Maven test reports exceed the report count budget.");
        List<Object> tests = new ArrayList<>();
        int total = 0;
        boolean matched = false;
        for (Object value : files) {
            context.throwIfCancelled();
            Map<String, Object> entry = Json.object(value);
            String file = Json.string(entry, "file"), project = Json.string(entry, "project");
            if (file == null || !file.matches("report-[0-9]+\\.xml") || project == null) throw invalid("Invalid operation report identity.");
            Path owner = workspace.resolve(project).normalize();
            if (!owner.startsWith(workspace) || !owner.toRealPath().startsWith(workspace)) throw invalid("Report project leaves the workspace.");
            byte[] bytes = read(directory.resolve(file), MAX_BYTES - total); total += bytes.length;
            if (!hash(bytes).equals(entry.get("sha256"))) throw invalid("A captured report changed before decoding.");
            // Upstream's parser does not harden its SAX factory. Reject DTDs
            // and external entities on the same bounded immutable bytes first;
            // all test outcome semantics remain in Apache's parser.
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.newSAXParser().parse(new ByteArrayInputStream(bytes), new DefaultHandler());
            TestSuiteXmlParser parser = new TestSuiteXmlParser(new NullConsoleLogger());
            try (InputStreamReader input = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                for (ReportTestSuite parsed : parser.parse(input)) {
                    for (ReportTestCase test : parsed.getTestCases()) {
                        context.throwIfCancelled();
                        if (tests.size() >= 10000) throw invalid("Maven test cases exceed the result budget.");
                        String name = test.getFullClassName();
                        String testName = test.getName();
                        if (name == null || name.isEmpty() || testName == null || testName.isEmpty()) throw invalid("Maven produced an unnamed test result.");
                        double time = test.getTime();
                        if (!Double.isFinite(time) || time < 0 || time * 1000 > 9_007_199_254_740_991L) throw invalid("Maven produced an invalid test duration.");
                        String status = test.hasError() ? "error" : test.hasFailure() ? "failed" : test.hasSkipped() ? "skipped" : "passed";
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("project", project.isEmpty() ? "." : project.replace(java.io.File.separatorChar, '/'));
                        item.put("suite", name); item.put("case", testName); item.put("status", status);
                        item.put("durationMillis", Math.round(time * 1000));
                        String message = test.getFailureMessage();
                        if (message == null || message.isEmpty()) message = test.getFailureDetail();
                        if (message != null && !message.isEmpty()) item.put("message", message.substring(0, Math.min(message.length(), 16000)));
                        tests.add(item);
                        if (suite != null && (suite.equals(name) || !suite.contains(".") && name.endsWith("." + suite))
                                && (method == null || method.equals(testName))) matched = true;
                    }
                }
            }
        }
        if (requireMatch && suite != null && !matched) throw new PluginError("maven.noMatchingTests", "Surefire produced no fresh result for the selected class or method.");
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "litheTestResults"); document.put("version", 1); document.put("tests", tests);
        if (Json.stringify(document).getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw invalid("Maven's canonical test report exceeds its result budget.");
        return document;
    }

    private static byte[] read(Path file, int remaining) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > remaining) throw invalid("A captured report is not a bounded regular file.");
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int count;
            while ((count = input.read(chunk)) >= 0) {
                if (count > remaining - output.size()) throw invalid("Captured reports exceed the byte budget.");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x", value & 255));
        return result.toString();
    }
    private static PluginError invalid(String message) { return new PluginError("maven.invalidTestReport", message); }

    @Override
    public void close() throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
        }
    }
}
