package io.github.litheidea.maven;

import java.io.File;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;

/**
 * Non-sensitive configuration facts from the selected Maven's own session.
 * This runs before projects are read: no second settings precedence, reactor,
 * profile activation engine, or dependency importer is maintained by Lithe.
 * Full native documents and credentials never enter the exported JSON.
 */
final class SettingsExport {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String SETTINGS_NAMESPACE = "http://maven.apache.org/SETTINGS/1.0.0";

    static final class Capture {
        final NativeSource installation;
        final NativeSource user;
        List<String> effectiveProfiles;

        Capture(SettingsBuildingRequest request) throws Exception {
            if (request.getGlobalSettingsSource() != null || request.getUserSettingsSource() != null) {
                throw new IllegalStateException("Non-file Maven settings sources cannot be edited.");
            }
            installation = read("installation", request.getGlobalSettingsFile(), false);
            // A source shared with installation settings remains read-only.
            boolean userWritable = !canonical(request.getUserSettingsFile()).equals(installation.canonicalPath);
            user = read("user", request.getUserSettingsFile(), userWritable);
        }
    }

    static Map<String, Object> document(MavenSession session, Capture capture) throws Exception {
        return document(session.getRequest(), capture);
    }

    static Map<String, Object> document(MavenExecutionRequest request, Capture capture) throws Exception {
        if (capture == null || capture.effectiveProfiles == null
                || !capture.installation.current(request.getGlobalSettingsFile())
                || !capture.user.current(request.getUserSettingsFile())) {
            throw new IllegalStateException("Maven configuration changed during inspection; retry with current files.");
        }
        NativeSource installation = capture.installation;
        NativeSource user = capture.user;
        String repository = request.getLocalRepositoryPath().getAbsolutePath();
        List<String> profiles = new ArrayList<>(request.getActiveProfiles());
        String repositoryOrigin = request.getUserProperties().containsKey("maven.repo.local")
                || request.getSystemProperties().containsKey("maven.repo.local") ? "commandLine"
                : present(user.settings.getLocalRepository()) ? "user"
                : present(installation.settings.getLocalRepository()) ? "installation" : "default";
        // The original SettingsBuildingResult predates CLI population; the
        // session's SettingsAdapter already includes command-line overrides.
        String profileOrigin = profiles.equals(capture.effectiveProfiles) ? "settings" : "commandLine";
        List<Object> sources = new ArrayList<>();
        sources.add(installation.export(repository, profiles, repositoryOrigin, profileOrigin));
        sources.add(user.export(repository, profiles, repositoryOrigin, profileOrigin));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "litheBuildConfiguration"); result.put("version", 1); result.put("sources", sources);
        return result;
    }

    static Map<String, Object> context(MavenSession session, Capture capture) throws Exception {
        Map<String, Object> configuration = document(session, capture);
        List<Object> inputs = new ArrayList<>();
        for (Object value : (List<?>) configuration.get("sources")) {
            @SuppressWarnings("unchecked") Map<String, Object> source = (Map<String, Object>) value;
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("uri", source.get("uri")); identity.put("revision", source.get("revision"));
            inputs.add(identity);
        }
        for (File file : new File[] {session.getRequest().getGlobalToolchainsFile(), session.getRequest().getUserToolchainsFile()}) {
            if (file == null) continue;
            Path path = file.toPath().toAbsolutePath().normalize();
            String revision = "missing";
            if (Files.exists(path)) {
                if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) throw new IllegalStateException("Maven toolchains are not a bounded regular file.");
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length > MAX_BYTES) throw new IllegalStateException("Maven toolchains exceed the size limit.");
                revision = "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes));
            }
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("uri", path.toUri().toString()); identity.put("revision", revision); inputs.add(identity);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", "litheMavenNativeInputs"); result.put("version", 1); result.put("sources", inputs);
        return result;
    }

    private static Path canonical(File file) throws Exception {
        if (file == null) throw new IllegalStateException("Maven did not select a native settings file.");
        Path path = file.toPath().toAbsolutePath().normalize();
        return Files.exists(path) ? path.toRealPath() : path;
    }

    private static NativeSource read(String id, File file, boolean writable) throws Exception {
        if (file == null) throw new IllegalStateException("Maven did not select its " + id + " settings source.");
        Path path = file.toPath().toAbsolutePath().normalize();
        if (!Files.exists(path)) return new NativeSource(id, path, path, "missing", writable && writableParent(path),
                new Settings(), false, "settings", SETTINGS_NAMESPACE);
        Path canonicalPath = path.toRealPath();
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) {
            throw new IllegalStateException("Maven settings are not a bounded regular file.");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("Maven settings exceed the size limit.");
        String xml = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        // The owning Maven's generated reader supplies raw values. StAX only
        // preserves absence/root metadata needed by the generic native editor.
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
        String rootName = null;
        String rootNamespace = "";
        boolean activeProfiles = false;
        int depth = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    if (depth == 0) {
                        rootName = reader.getLocalName();
                        rootNamespace = reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI();
                    } else if (depth == 1 && "activeProfiles".equals(reader.getLocalName())
                            && rootNamespace.equals(reader.getNamespaceURI() == null ? "" : reader.getNamespaceURI())) {
                        activeProfiles = true;
                    }
                    if (++depth > 64) throw new IllegalStateException("Maven settings are too deeply nested.");
                } else if (event == XMLStreamConstants.END_ELEMENT) depth--;
                else if (event == XMLStreamConstants.DTD) throw new IllegalStateException("Maven settings DTDs cannot be edited.");
            }
        } finally { reader.close(); }
        Settings settings = new SettingsXpp3Reader().read(new StringReader(xml));
        return new NativeSource(id, path, canonicalPath, "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                writable && Files.isWritable(path), settings, activeProfiles, rootName, rootNamespace);
    }

    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }

    private static boolean writableParent(Path path) {
        Path parent = path.getParent();
        while (parent != null && !Files.exists(parent)) parent = parent.getParent();
        return parent != null && Files.isDirectory(parent) && Files.isWritable(parent);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static final class NativeSource {
        final String id;
        final Path path;
        final Path canonicalPath;
        final String revision;
        final boolean writable;
        final Settings settings;
        final boolean activeProfiles;
        final String rootName;
        final String rootNamespace;

        NativeSource(String id, Path path, Path canonicalPath, String revision, boolean writable, Settings settings,
                boolean activeProfiles, String rootName, String rootNamespace) {
            this.id = id; this.path = path; this.canonicalPath = canonicalPath; this.revision = revision; this.writable = writable;
            this.settings = settings; this.activeProfiles = activeProfiles;
            this.rootName = rootName; this.rootNamespace = rootNamespace;
        }

        boolean current(File selected) throws Exception {
            if (!canonicalPath.equals(canonical(selected))) return false;
            if ("missing".equals(revision)) return !Files.exists(path);
            if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) return false;
            byte[] bytes = Files.readAllBytes(path);
            return bytes.length <= MAX_BYTES && revision.equals("sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }

        Map<String, Object> export(String repository, List<String> profiles, String repositoryOrigin, String profileOrigin) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("id", id); source.put("scope", id); source.put("uri", path.toUri().toString());
            source.put("revision", revision); source.put("writable", writable);
            Map<String, Object> root = new LinkedHashMap<>(); root.put("name", rootName); root.put("namespace", rootNamespace);
            source.put("rootElement", root);
            List<Object> fields = new ArrayList<>();
            fields.add(field("localRepository", "directory", settings.getLocalRepository(), repository, repositoryOrigin, null));
            fields.add(field("activeProfiles", "stringList", activeProfiles ? settings.getActiveProfiles() : null,
                    profiles, profileOrigin, "activeProfile"));
            source.put("fields", fields);
            return source;
        }

        private Map<String, Object> field(String name, String kind, Object value, Object effective, String origin, String item) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("id", name); field.put("kind", kind); field.put("value", value);
            field.put("effectiveValue", effective); field.put("effectiveSource", origin);
            field.put("path", Collections.singletonList(name));
            if (item != null) field.put("itemElement", item);
            return field;
        }
    }
}
