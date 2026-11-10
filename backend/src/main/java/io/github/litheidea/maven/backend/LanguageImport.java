package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.CallContext;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The plugin function {@code languageImport.settings} (version 1) that Java
 * Support calls before it starts JDT LS: the import settings Maven's own
 * project configuration implies. It reads {@code .mvn/maven.config} only, runs
 * nothing and writes nothing; the workspace root it is asked about must lie
 * inside an open local workspace folder.
 *
 * <p>{@code -s/--settings} becomes {@code java.configuration.maven.userSettings},
 * {@code -gs/--global-settings} {@code java.configuration.maven.globalSettings}
 * and {@code -o/--offline} {@code java.import.maven.offline.enabled}.
 */
final class LanguageImport {
    static final String FUNCTION = "languageImport.settings";
    private static final long MAX_CONFIG_BYTES = 64 * 1024;

    private LanguageImport() {}

    /** The function handler. */
    static Object handle(Map<String, Object> params, CallContext context) throws Exception {
        String requested = Json.string(params, "workspaceRoot");
        if (requested == null || !Paths.get(requested).isAbsolute()) {
            throw new PluginError("maven.invalidRoot", "workspaceRoot must be an absolute path.");
        }
        Map<String, Object> workspace = context.workspaceContext();
        if (workspace == null) {
            throw new PluginError("maven.noWorkspaceContext", "The call carries no workspace context.");
        }
        Path root = Paths.get(requested).toRealPath();
        Map<String, Object> answer = context.host().workspace().getFolders(workspace).get(10, TimeUnit.SECONDS);
        if (!insideFolder(root, answer.get("folders"))) {
            throw new PluginError("maven.invalidRoot", "workspaceRoot is not inside an open workspace folder.");
        }
        context.throwIfCancelled();
        return settings(root);
    }

    private static boolean insideFolder(Path root, Object folders) throws IOException {
        if (!(folders instanceof List)) {
            return false;
        }
        for (Object folder : (List<?>) folders) {
            Object path = folder instanceof Map ? ((Map<?, ?>) folder).get("path") : null;
            if (path instanceof String && root.startsWith(Paths.get((String) path).toRealPath())) {
                return true;
            }
        }
        return false;
    }

    /** {@code { settings }} for the Maven build at {@code root}. */
    static Map<String, Object> settings(Path root) throws IOException {
        Map<String, Object> settings = new LinkedHashMap<>();
        Path config = root.resolve(".mvn").resolve("maven.config");
        if (Files.isRegularFile(config) && Files.size(config) <= MAX_CONFIG_BYTES) {
            List<String> tokens = tokens(new String(Files.readAllBytes(config), StandardCharsets.UTF_8));
            for (int index = 0; index < tokens.size(); index++) {
                String token = tokens.get(index);
                String next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
                if (token.equals("-o") || token.equals("--offline")) {
                    settings.put("java.import.maven.offline.enabled", Boolean.TRUE);
                } else if (token.equals("-s") || token.equals("--settings")) {
                    put(settings, "java.configuration.maven.userSettings", root, next);
                } else if (token.startsWith("--settings=")) {
                    put(settings, "java.configuration.maven.userSettings", root, token.substring(11));
                } else if (token.equals("-gs") || token.equals("--global-settings")) {
                    put(settings, "java.configuration.maven.globalSettings", root, next);
                } else if (token.startsWith("--global-settings=")) {
                    put(settings, "java.configuration.maven.globalSettings", root, token.substring(18));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("settings", settings);
        return result;
    }

    private static void put(Map<String, Object> settings, String key, Path root, String value) {
        if (value == null || value.isEmpty() || value.startsWith("-")) {
            return;
        }
        Path path = root.resolve(value).normalize();
        if (Files.isRegularFile(path)) {
            settings.put(key, path.toString());
        }
    }

    /** Whitespace-separated tokens; double or single quotes keep spaces. */
    static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                started = true;
            } else if (Character.isWhitespace(c)) {
                if (started) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    started = false;
                }
            } else {
                current.append(c);
                started = true;
            }
        }
        if (started) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
