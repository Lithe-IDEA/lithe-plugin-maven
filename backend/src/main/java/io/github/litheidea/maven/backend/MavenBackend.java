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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

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
    private volatile Path workspaceState;

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
            workspaceState = state;
            runs = new MavenRuns(snapshots, backend, state);
        });
        backend.onConfiguration((params, context) -> ConfigurationProbe.read(inputs(params), workspaceState, context.cancellation()));
        backend.onModel((params, context) -> copy(load(params, context).model()));
        backend.onDependencies(this::dependencies);
        backend.onExecute((params, context) -> execution(params, context, false));
        backend.onTest((params, context) -> execution(params, context, true));
        backend.onPrepareRun((params, context) -> {
            Map<String, Object> request = new LinkedHashMap<>(params);
            Map<String, Object> environment = new LinkedHashMap<>();
            if (params.get("environment") instanceof Map) environment.putAll(Json.object(params.get("environment")));
            environment.putAll(Json.object(Json.object(params.get("launchOptions")).get("environment")));
            request.put("environment", environment);
            Inputs selected = inputs(request);
            List<String> arguments = new java.util.ArrayList<>(selected.optionArguments);
            arguments.addAll(MavenRunPreparation.modelArguments(request));
            MavenRuns.Loaded loaded = runs.load(new Inputs(selected.root, selected.maven, arguments, selected.environment,
                    selected.hostEnvironment, selected.extensionJar), Boolean.TRUE.equals(request.get("refresh")), context.cancellation());
            context.throwIfCancelled();
            return MavenRunPreparation.prepare(request, root(request), workspaceRoot, loaded, extensionJar());
        });
        backend.listen();
    }

    private Object execution(Map<String, Object> params, CallContext context, boolean tests) throws Exception {
        Path root = root(params);
        Map<String, String> environment = environment(params.get("environment"));
        Object tool = params.get("tool");
        String executable = tool instanceof Map ? Json.string(Json.object(tool), "executable") : null;
        Path maven = MavenExecutable.resolve(executable, root, environment);
        // Model and execution use the same effective options and Maven runtime.
        // The extension remains the owner of reactor and plugin-goal facts.
        MavenRuns.Loaded loaded = load(params, context);
        return MavenExecution.run(params, context, root, workspaceRoot, maven, environment, loaded, tests, extensionJar(), workspaceState);
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
        result.put("dependencies", Projects.dependenciesOf(loaded, workspaceRoot, path));
        return result;
    }

    private MavenRuns.Loaded load(Map<String, Object> params, CallContext context) throws Exception {
        if (runs == null) {
            throw new PluginError("maven.notInitialized", "The Maven backend was not initialized.");
        }
        Inputs inputs = inputs(params);
        boolean refresh = Boolean.TRUE.equals(params.get("refresh"));
        return runs.load(inputs, refresh, context.cancellation());
    }

    private Inputs inputs(Map<String, Object> params) throws IOException {
        Path root = root(params);
        if (!Files.isDirectory(root)) throw new PluginError("maven.missingRoot", "The Maven root is unavailable.");
        Map<String, String> environment = environment(params.get("environment"));
        Object tool = params.get("tool");
        String executable = tool instanceof Map ? Json.string(Json.object(tool), "executable") : null;
        Path maven = MavenExecutable.resolve(executable, root, environment);
        return new Inputs(root, maven, MavenExecution.optionArguments(params, false),
                environment, hostEnvironment(params.get("environment")), extensionJar());
    }

    private Path root(Map<String, Object> params) throws IOException {
        if (workspaceRoot == null) throw new PluginError("maven.notInitialized", "The Maven backend was not initialized.");
        String value = Json.string(params, "root");
        if (value == null || value.isEmpty() || Paths.get(value).isAbsolute()) throw new PluginError("maven.invalidRoot", "Expected a workspace-relative Maven root.");
        Path root = workspaceRoot.resolve(value).normalize().toRealPath();
        if (!root.startsWith(workspaceRoot) || !Files.isRegularFile(root.resolve("pom.xml"))) {
            throw new PluginError("maven.invalidRoot", "The Maven root must contain pom.xml inside the workspace.");
        }
        return root;
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
        return Paths.get(URI.create(rootUri)).toRealPath();
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
