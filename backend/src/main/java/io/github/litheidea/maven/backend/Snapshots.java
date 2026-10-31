package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Backend;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.StateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code maven-snapshot.json}: the last Maven result of each root, with the
 * fingerprint of the inputs it was computed from.
 *
 * <pre>
 * { "snapshotVersion": 2,
 *   "roots": { "/abs/root": { "fingerprint", "savedAt", "model", "dependencies" } } }
 * </pre>
 *
 * <p>The {@link StateStore} replaces the file atomically. A file that cannot
 * be read, has another {@code snapshotVersion} or lacks a field is a miss,
 * never an error: the next run overwrites it.
 */
final class Snapshots {
    static final String KEY = "maven-snapshot";
    static final int VERSION = 2;
    /** Roots kept per workspace; the least recently saved go first. */
    private static final int MAX_ROOTS = 32;

    private final StateStore store;
    private final Backend backend;

    Snapshots(StateStore store, Backend backend) {
        this.store = store;
        this.backend = backend;
    }

    /** The stored result for {@code root}, or null when there is none for {@code fingerprint}. */
    synchronized MavenRuns.Loaded read(Path root, String fingerprint) {
        Map<String, Object> entry = Json.object(roots(store.read(KEY, null)).get(root.toString()));
        if (!fingerprint.equals(entry.get("fingerprint"))
                || !(entry.get("model") instanceof Map) || !(entry.get("dependencies") instanceof Map)
                || !NativeSources.current(entry.get("nativeSources"))) {
            return null;
        }
        return new MavenRuns.Loaded(Json.object(entry.get("model")), Json.object(entry.get("dependencies")),
                new ArrayList<>((List<?>) entry.get("nativeSources")));
    }

    synchronized void write(Path root, String fingerprint, MavenRuns.Loaded loaded) {
        Map<String, Object> roots = roots(store.read(KEY, null));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fingerprint", fingerprint);
        entry.put("savedAt", System.currentTimeMillis());
        entry.put("model", loaded.model());
        entry.put("dependencies", loaded.dependencies());
        entry.put("nativeSources", loaded.nativeSources());
        roots.remove(root.toString());
        roots.put(root.toString(), entry);
        prune(roots);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("snapshotVersion", VERSION);
        snapshot.put("roots", roots);
        try {
            store.write(KEY, snapshot);
        } catch (IOException | RuntimeException error) {
            // The result is still answered; only the next start runs Maven again.
            backend.log("Could not save the Maven snapshot: " + error.getMessage());
        }
    }

    private static Map<String, Object> roots(Object stored) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!(stored instanceof Map)) {
            return result;
        }
        Object version = ((Map<?, ?>) stored).get("snapshotVersion");
        Object roots = ((Map<?, ?>) stored).get("roots");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != VERSION || !(roots instanceof Map)) {
            return result;
        }
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) roots).entrySet()) {
            if (entry.getKey() instanceof String && entry.getValue() instanceof Map) {
                result.put((String) entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /** Drops roots that no longer exist, then the oldest beyond {@link #MAX_ROOTS}. */
    private static void prune(Map<String, Object> roots) {
        roots.keySet().removeIf(root -> !Files.isDirectory(Paths.get(root)));
        if (roots.size() <= MAX_ROOTS) {
            return;
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(roots.entrySet());
        entries.sort(Comparator.comparingDouble(entry -> savedAt(entry.getValue())));
        for (int index = 0; index < entries.size() - MAX_ROOTS; index++) {
            roots.remove(entries.get(index).getKey());
        }
    }

    private static double savedAt(Object entry) {
        Object value = Json.object(entry).get("savedAt");
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }
}
