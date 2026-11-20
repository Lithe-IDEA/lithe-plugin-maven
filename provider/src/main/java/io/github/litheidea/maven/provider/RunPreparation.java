package io.github.litheidea.maven.provider;

import io.github.litheidea.plugin.sdk.CallContext;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import io.github.litheidea.plugin.sdk.Processes;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Answers {@code lithe/build/prepareRun} for a Maven application target.
 *
 * <p>The user's Maven compiles the selected project and the reactor modules
 * it depends on ({@code -pl <project> -am process-classes}) with the
 * packaged core extension loaded, which then writes the runtime classpath
 * and the compiled main classes ({@code RunExport}). The answer is a plain
 * {@code java -cp <classpath> <main class>} launch on the project's JDK;
 * Lithe starts and owns that process. Nothing runs through
 * {@code spring-boot:run}, so the process Lithe stops is the application
 * itself and a debugger can attach to it.
 *
 * <p>A classpath longer than {@link #MAX_CLASSPATH_CHARS} goes into a
 * manifest-only jar under the project's build directory, as IntelliJ IDEA's
 * "JAR manifest" shortening does: one launch argument is limited to 64 KiB,
 * and a Windows command line to 32 767 characters. The jar is build output
 * Maven's {@code clean} removes, named by a hash of its classpath so equal
 * launches reuse it.
 */
final class RunPreparation {
    static final int MAX_CLASSPATH_CHARS = 24_000;
    static final String SPRING_BOOT = "spring-boot";
    static final String JAVA_TOOL = "java";
    private static final int OUTPUT_CHUNK = 32 * 1024;

    private final Path workspace;
    private final Path extensionJar;
    private final Messages messages;

    RunPreparation(Path workspace, Path extensionJar, Messages messages) {
        this.workspace = workspace;
        this.extensionJar = extensionJar;
        this.messages = messages;
    }

    Map<String, Object> prepare(Map<String, Object> params, CallContext context) throws Exception {
        Request request = Request.of(params);
        Path instance = workspace.resolve(request.root).normalize();
        String project = relative(request.root, request.projectPath);
        Map<String, String> environment = environment(request.environment);
        List<String> command = new ArrayList<>();
        command.add(executable(maven(request.tool, instance), environment, File.separatorChar == '\\'));
        command.add("-B");
        command.add("-ntp");
        command.addAll(request.optionArguments);
        command.add("-Dmaven.ext.class.path=" + extensionJar);
        Path output = Files.createTempFile("lithe-maven-run-", ".json");
        try {
            command.add("-Dlithe.runOutput=" + output);
            command.add("-Dlithe.runProject=./" + project);
            if (request.tags.contains(SPRING_BOOT) && !hasPropertyArgument(request.optionArguments, "lithe.runIncludeProvided")) {
                command.add("-Dlithe.runIncludeProvided=true");
            }
            if (!".".equals(project)) {
                command.add("-pl");
                command.add("./" + project);
                command.add("-am");
            }
            command.add("process-classes");
            context.progress(messages.compiling(request.targetName), null, null);
            Processes.Result result = context.run(command, new Processes.Options()
                    .directory(instance)
                    .environment(environment)
                    .cancellation(context.cancellation())
                    .maxOutputBytes(64 * 1024)
                    .onStdout(text -> stream(context, text))
                    .onStderr(text -> stream(context, text)));
            if (result.exitCode() != 0) {
                throw new PluginError("maven.run.buildFailed", messages.buildFailed(result.exitCode()));
            }
            String text = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                throw new PluginError("maven.run.noRunDocument", messages.noRunDocument());
            }
            Map<String, Object> document = Json.object(Json.parse(text));
            return launch(request, document);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /** The launch plan for the run document Maven wrote. */
    Map<String, Object> launch(Request request, Map<String, Object> document) throws IOException {
        String main = mainClass(request, document);
        List<String> classpath = strings(document.get("classpath"));
        List<String> arguments = new ArrayList<>(request.runtimeArguments);
        if (request.debugPort > 0) {
            arguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=" + request.debugHost + ":" + request.debugPort);
        }
        arguments.add("-cp");
        arguments.add(classpathArgument(classpath, Paths.get(String.valueOf(document.get("outputDirectory")))));
        arguments.add(main);
        arguments.addAll(request.arguments);
        arguments.addAll(request.userArguments);
        Map<String, Object> executable = new LinkedHashMap<>();
        executable.put("kind", "toolchain");
        executable.put("value", JAVA_TOOL);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("executable", executable);
        plan.put("arguments", arguments);
        plan.put("workingDirectory", request.workingDirectory != null ? request.workingDirectory : request.root);
        plan.put("environment", request.launchEnvironment);
        return plan;
    }

    /**
     * The chosen entry point, the target's main class, or the one main class
     * Maven compiled; Spring Boot targets prefer the annotated classes.
     */
    String mainClass(Request request, Map<String, Object> document) {
        if (request.entrypoint != null) {
            return request.entrypoint;
        }
        if (request.mainClass != null) {
            return request.mainClass;
        }
        List<String> all = new ArrayList<>();
        List<String> annotated = new ArrayList<>();
        Object listed = document.get("mainClasses");
        if (listed instanceof List) {
            for (Object item : (List<?>) listed) {
                Map<String, Object> main = Json.object(item);
                String name = String.valueOf(main.get("name"));
                all.add(name);
                if (Boolean.TRUE.equals(main.get("springBoot"))) {
                    annotated.add(name);
                }
            }
        }
        List<String> candidates = request.tags.contains(SPRING_BOOT) && !annotated.isEmpty() ? annotated : all;
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new PluginError("maven.run.noMainClass", messages.noMainClass(request.targetName));
        }
        Collections.sort(candidates);
        throw new PluginError("maven.run.ambiguousMainClass", messages.ambiguousMainClass(request.targetName, candidates));
    }

    /** The classpath as one argument, or a manifest jar on it when that argument would be too long. */
    static String classpathArgument(List<String> classpath, Path outputDirectory) throws IOException {
        String joined = String.join(File.pathSeparator, classpath);
        if (joined.length() <= MAX_CLASSPATH_CHARS) {
            return joined;
        }
        StringBuilder entries = new StringBuilder();
        for (String entry : classpath) {
            File file = new File(entry);
            String uri = file.toURI().toString();
            if (file.isDirectory() && !uri.endsWith("/")) {
                uri += "/";
            }
            if (entries.length() > 0) {
                entries.append(' ');
            }
            entries.append(uri);
        }
        Path directory = outputDirectory.getParent().resolve("lithe-run");
        Files.createDirectories(directory);
        Path jar = directory.resolve("classpath-" + hash(entries.toString()) + ".jar");
        if (!Files.isRegularFile(jar)) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, entries.toString());
            Path partial = Files.createTempFile(directory, "classpath-", ".tmp");
            try (OutputStream out = Files.newOutputStream(partial); JarOutputStream ignored = new JarOutputStream(out, manifest)) {
                // A manifest is the whole jar.
            }
            try {
                Files.move(partial, jar, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException raced) {
                Files.deleteIfExists(partial);
                if (!Files.isRegularFile(jar)) {
                    throw raced;
                }
            }
        }
        return jar.toString();
    }

    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The Maven to run: the one Lithe resolved from the build system's
     * invocation, otherwise the same order the manifest declares, without
     * the user's override, which only Lithe can read.
     */
    static String maven(String tool, Path instance) {
        if (tool != null) {
            return tool;
        }
        boolean windows = File.separatorChar == '\\';
        Path wrapper = instance.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (Files.isRegularFile(wrapper) && Files.isRegularFile(instance.resolve(".mvn/wrapper/maven-wrapper.properties"))) {
            return wrapper.toString();
        }
        String home = System.getenv("MAVEN_HOME");
        if (home != null && !home.isEmpty()) {
            Path candidate = Paths.get(home, "bin", windows ? "mvn.cmd" : "mvn");
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return windows ? "mvn.cmd" : "mvn";
    }

    /**
     * The path to start for {@code command}. A command without a directory is
     * looked up on the {@code PATH} of {@code environment}, as a shell would:
     * Java starts a bare name through the operating system, and on Windows
     * that finds only {@code .exe} files, so {@code mvn}, which is
     * {@code mvn.cmd} there, would fail to start. On Windows a name without
     * an extension also tries each {@code PATHEXT} extension. A command that
     * names a directory is returned as it is.
     *
     * @throws PluginError {@code maven.run.noMaven} when no {@code PATH}
     *     entry holds the command
     */
    String executable(String command, Map<String, String> environment, boolean windows) {
        if (command.indexOf('/') >= 0 || (windows && (command.indexOf('\\') >= 0 || command.indexOf(':') >= 0))) {
            return command;
        }
        List<String> names = new ArrayList<>();
        String extensions = variable(environment, "PATHEXT", windows);
        if (windows && command.indexOf('.') < 0) {
            for (String extension : (extensions == null || extensions.isEmpty() ? ".COM;.EXE;.BAT;.CMD" : extensions).split(";")) {
                if (!extension.isEmpty()) {
                    names.add(command + extension.toLowerCase(Locale.ROOT));
                }
            }
        } else {
            names.add(command);
        }
        String path = variable(environment, "PATH", windows);
        if (path != null) {
            for (String directory : path.split(File.pathSeparator)) {
                if (directory.isEmpty()) {
                    continue;
                }
                for (String name : names) {
                    Path candidate;
                    try {
                        candidate = Paths.get(directory.replace("\"", ""), name);
                    } catch (java.nio.file.InvalidPathException ignored) {
                        break;
                    }
                    if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate))) {
                        return candidate.toString();
                    }
                }
            }
        }
        throw new PluginError("maven.run.noMaven", messages.noMaven(command));
    }

    /** The variable {@code name}, whose case Windows ignores. */
    private static String variable(Map<String, String> environment, String name, boolean windows) {
        if (!windows) {
            return environment.get(name);
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Map<String, String> environment(Map<String, String> overrides) {
        Map<String, String> environment = new LinkedHashMap<>(System.getenv());
        environment.putAll(overrides);
        return environment;
    }

    private static void stream(CallContext context, String text) {
        for (int start = 0; start < text.length(); start += OUTPUT_CHUNK) {
            context.progress(null, null, text.substring(start, Math.min(text.length(), start + OUTPUT_CHUNK)));
        }
    }

    /** path relative to root, both workspace-relative with forward slashes. */
    static String relative(String root, String path) {
        if (".".equals(root) || root.isEmpty()) {
            return path.isEmpty() ? "." : path;
        }
        if (path.equals(root)) {
            return ".";
        }
        if (path.startsWith(root + "/")) {
            return path.substring(root.length() + 1);
        }
        throw new PluginError("maven.run.invalidProject", "The project is outside the build root.");
    }

    static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    private static boolean hasPropertyArgument(List<String> arguments, String property) {
        String prefix = "-D" + property + "=";
        String bare = "-D" + property;
        for (String argument : arguments) {
            if (bare.equals(argument) || argument.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    /** The fields of one prepareRun request this provider uses. */
    static final class Request {
        String root = ".";
        String projectPath = ".";
        String targetName = "";
        List<String> tags = new ArrayList<>();
        String mainClass;
        String tool;
        List<String> optionArguments = new ArrayList<>();
        Map<String, String> environment = new LinkedHashMap<>();
        List<String> runtimeArguments = new ArrayList<>();
        List<String> arguments = new ArrayList<>();
        List<String> userArguments = new ArrayList<>();
        Map<String, String> launchEnvironment = new LinkedHashMap<>();
        String entrypoint;
        String workingDirectory;
        String debugHost;
        int debugPort;

        static Request of(Map<String, Object> params) {
            Request request = new Request();
            String root = text(params.get("root"));
            request.root = root == null ? "." : root;
            Map<String, Object> project = params.get("project") instanceof Map ? Json.object(params.get("project")) : Collections.emptyMap();
            String projectPath = text(project.get("path"));
            request.projectPath = projectPath == null ? request.root : projectPath;
            Map<String, Object> target = params.get("target") instanceof Map ? Json.object(params.get("target")) : Collections.emptyMap();
            String name = text(target.get("name"));
            request.targetName = name == null ? "" : name;
            request.tags = strings(target.get("tags"));
            Map<String, Object> properties = target.get("properties") instanceof Map ? Json.object(target.get("properties")) : Collections.emptyMap();
            request.mainClass = text(properties.get("mainClass"));
            Map<String, Object> tool = params.get("tool") instanceof Map ? Json.object(params.get("tool")) : Collections.emptyMap();
            request.tool = text(tool.get("executable"));
            request.optionArguments = strings(params.get("optionArguments"));
            request.environment = stringMap(params.get("environment"));
            request.userArguments = strings(params.get("userArguments"));
            if (params.get("launchOptions") instanceof Map) {
                Map<String, Object> options = Json.object(params.get("launchOptions"));
                request.runtimeArguments = strings(options.get("runtimeArguments"));
                request.arguments = strings(options.get("arguments"));
                request.launchEnvironment = stringMap(options.get("environment"));
                request.entrypoint = text(options.get("entrypoint"));
                request.workingDirectory = text(options.get("workingDirectory"));
                if (options.get("debug") instanceof Map) {
                    Map<String, Object> debug = Json.object(options.get("debug"));
                    Map<String, Object> endpoint = debug.get("target") instanceof Map ? Json.object(debug.get("target")) : Collections.emptyMap();
                    request.debugHost = text(endpoint.get("host"));
                    Object port = endpoint.get("port");
                    request.debugPort = port instanceof Number ? ((Number) port).intValue() : 0;
                    if (request.debugHost == null || request.debugPort < 1 || request.debugPort > 65535) {
                        throw new PluginError("maven.run.invalidDebug", "The debug target is not a local connection.");
                    }
                }
            }
            // An entrypoint run names no target; progress and errors name the class instead.
            if (request.targetName.isEmpty() && request.entrypoint != null) {
                request.targetName = request.entrypoint.substring(request.entrypoint.lastIndexOf('.') + 1);
            }
            return request;
        }
    }
}
