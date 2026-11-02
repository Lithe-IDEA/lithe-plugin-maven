package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact native inputs selected by Maven, without rediscovering CLI precedence. */
final class NativeSources {
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    static List<Object> fromDocument(Map<String, Object> document) {
        if (!"litheMavenNativeInputs".equals(document.get("format"))
                || !(document.get("version") instanceof Number)
                || ((Number) document.get("version")).doubleValue() != 1) {
            throw new PluginError("maven.invalidOutput", "Maven did not export its selected configuration sources.");
        }
        Object input = document.get("sources");
        if (!(input instanceof List) || ((List<?>) input).isEmpty() || ((List<?>) input).size() > 8) {
            throw new PluginError("maven.invalidOutput", "Maven configuration sources are invalid.");
        }
        List<Object> result = new ArrayList<>();
        for (Object value : (List<?>) input) {
            Map<String, Object> source = Json.object(value);
            Map<String, Object> identity = new LinkedHashMap<>();
            identity.put("uri", source.get("uri"));
            identity.put("revision", source.get("revision"));
            result.add(identity);
        }
        if (!current(result)) throw changed();
        return result;
    }

    static boolean current(Object input) {
        if (!(input instanceof List) || ((List<?>) input).isEmpty() || ((List<?>) input).size() > 8) return false;
        try {
            for (Object value : (List<?>) input) {
                Map<String, Object> source = Json.object(value);
                if (!(source.get("uri") instanceof String) || !(source.get("revision") instanceof String)) return false;
                URI uri = new URI((String) source.get("uri"));
                if (!"file".equals(uri.getScheme()) || uri.getQuery() != null || uri.getFragment() != null
                        || uri.getUserInfo() != null) return false;
                Path path = Paths.get(uri);
                String revision = (String) source.get("revision");
                if ("missing".equals(revision)) {
                    if (Files.exists(path)) return false;
                } else {
                    if (!revision.matches("sha256:[0-9a-f]{64}") || !Files.isRegularFile(path)
                            || Files.size(path) > MAX_BYTES) return false;
                    byte[] bytes = Files.readAllBytes(path);
                    if (bytes.length > MAX_BYTES || !revision.equals("sha256:" + hex(MessageDigest.getInstance("SHA-256").digest(bytes)))) return false;
                }
            }
            return true;
        } catch (Exception error) { return false; }
    }

    static PluginError changed() {
        return new PluginError("maven.configurationChanged", "Maven configuration changed while the project was read. Reload the project.");
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
