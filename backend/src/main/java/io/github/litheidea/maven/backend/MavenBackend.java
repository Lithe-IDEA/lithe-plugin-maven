package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Backend;
import io.github.litheidea.plugin.sdk.CallContext;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import io.github.litheidea.plugin.sdk.StateStore;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lithe's build provider for Maven. It answers {@code lithe/build/model} and
 * {@code lithe/build/dependencies} for every Maven root of one workspace.
 *
 * <p>One Maven run with the bundled extension writes both the model and the
 * dependency tree of every module. The result is kept in
 * {@code maven-snapshot.json} in the workspace state directory, so a request
 * whose inputs (see {@link Fingerprint}) did not change, also after Lithe
 * restarted, is answered without starting Maven. A request with
 * {@code refresh: true} always runs Maven.
 */
public final class MavenBackend {
    static final String VERSION = version();

    private final Backend backend = Backend.create("lithe-maven-backend", VERSION);
    private final String stateDirectory;
    private volatile Path workspaceRoot;
    private volatile Snapshots snapshots;
    private volatile MavenRuns runs;

    private MavenBackend(String stateDirectory) {
        this.stateDirectory = stateDirectory;
    }

    public static void main(String[] arguments) {
        new MavenBackend(argument(arguments, "--state")).serve();
    }

    private void serve() {
        backend.onInitialize((params, negotiated) -> {
            workspaceRoot = workspaceRoot(Json.string(params, "rootUri"));
            Path state = null;
            if (stateDirectory != null && !stateDirectory.isEmpty()) {
                // `--state ${workspaceStateDir}`: writable and kept across restarts.
                StateStore store = StateStore.open(Paths.get(stateDirectory));
                snapshots = new Snapshots(store, backend);
                state = store.directory();
            } else {
                backend.log("No --state directory: Maven results are not kept across restarts.");
            }
            runs = new MavenRuns(snapshots, backend, state);
        });
        backend.onModel((params, context) -> copy(load(params, context).model()));
        backend.onDependencies(this::dependencies);
        backend.listen();
    }

    private Object dependencies(Map<String, Object> params, CallContext context) throws Exception {
        Path root = root(params);
        MavenRuns.Loaded loaded = load(params, context);
        Object selected = params.get("project");
        if (!(selected instanceof Map)) {
            return copy(loaded.dependencies());
        }
        String path = Json.string(Json.object(selected), "path");
        if (path == null) {
            throw new PluginError("maven.invalidProject", "The selected project has no path.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dependencies", Projects.dependenciesOf(loaded, root, path));
        return result;
    }

    private MavenRuns.Loaded load(Map<String, Object> params, CallContext context) throws Exception {
        if (runs == null) {
            throw new PluginError("maven.notInitialized", "The Maven backend was not initialized.");
        }
        Path root = root(params);
        if (!Files.isDirectory(root)) {
            throw new PluginError("maven.missingRoot", root + " is not a directory.");
        }
        Map<String, String> environment = environment(params.get("environment"));
        Object tool = params.get("tool");
        String executable = tool instanceof Map ? Json.string(Json.object(tool), "executable") : null;
        Path maven = MavenExecutable.resolve(executable, root, environment);
        Inputs inputs = new Inputs(root, maven, strings(params.get("optionArguments")),
                environment, hostEnvironment(params.get("environment")), extensionJar());
        boolean refresh = Boolean.TRUE.equals(params.get("refresh"));
        return runs.load(inputs, refresh, context.cancellation());
    }

    private Path root(Map<String, Object> params) {
        String root = Json.string(params, "root");
        Path base = workspaceRoot == null ? Paths.get("").toAbsolutePath() : workspaceRoot;
        return base.resolve(root == null || root.isEmpty() ? "." : root).toAbsolutePath().normalize();
    }

    /**
     * The backend's own environment with what the host's options contribute
     * on top. JAVA_HOME defaults to the runtime Lithe started this backend
     * with, which is the JDK it found for Maven too.
     */
    private static Map<String, String> environment(Object fromHost) {
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        environment.putAll(hostEnvironment(fromHost));
        if (!environment.containsKey("JAVA_HOME")) {
            environment.put("JAVA_HOME", System.getProperty("java.home"));
        }
        return environment;
    }

    private static Map<String, String> hostEnvironment(Object fromHost) {
        Map<String, String> result = new LinkedHashMap<>();
        if (fromHost instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) fromHost).entrySet()) {
                if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                    result.put((String) entry.getKey(), (String) entry.getValue());
                }
            }
        }
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List)) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (item instanceof String) {
                result.add((String) item);
            }
        }
        return result;
    }

    /** The extension ships next to this JAR: {@code lib/lithe-maven-extension.jar}. */
    private static Path extensionJar() {
        try {
            Path self = Paths.get(MavenBackend.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path extension = self.resolveSibling("lithe-maven-extension.jar");
            if (Files.isRegularFile(extension)) {
                return extension;
            }
            throw new PluginError("maven.missingExtension",
                    "The plugin package is missing lib/lithe-maven-extension.jar. Reinstall the Maven plugin.");
        } catch (URISyntaxException | RuntimeException error) {
            if (error instanceof PluginError) {
                throw (PluginError) error;
            }
            throw new PluginError("maven.missingExtension", "The Maven plugin cannot locate its extension.", error);
        }
    }

    private static Path workspaceRoot(String rootUri) throws IOException {
        if (rootUri == null) {
            return Paths.get("").toAbsolutePath();
        }
        // `Paths.get(URI)` accepts the trailing slash a directory URI may carry.
        return Paths.get(URI.create(rootUri)).toAbsolutePath().normalize();
    }

    /** A shallow copy: the SDK stamps {@code format} on the returned document. */
    private static Map<String, Object> copy(Map<String, Object> document) {
        return new LinkedHashMap<>(document);
    }

    private static String version() {
        String version = MavenBackend.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }

    private static String argument(String[] arguments, String name) {
        for (int index = 0; index + 1 < arguments.length; index++) {
            if (arguments[index].equals(name)) {
                return arguments[index + 1];
            }
        }
        return null;
    }
}
