package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.CallContext;
import io.github.litheidea.plugin.sdk.Cancellation;
import io.github.litheidea.plugin.sdk.CancelledException;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import io.github.litheidea.plugin.sdk.Processes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native build/test requests use Maven's current reactor, never a host command template. */
final class MavenExecution {
    private static final List<String> LIFECYCLE = Arrays.asList(
            "clean", "validate", "compile", "test", "package", "verify", "install", "site", "deploy");
    private static final List<String> TEST_PHASES = Arrays.asList("test", "package", "verify", "install", "deploy", "plugin-goal", "goal");
    private static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

    private MavenExecution() {}

    static Map<String, Object> run(Map<String, Object> params, CallContext context, Path root, Path workspace,
            Path maven, Map<String, String> environment, MavenRuns.Loaded loaded, boolean tests, Path extension, Path state) throws Exception {
        String task = tests ? "test" : Json.string(params, "task");
        boolean prepareMain = "java-prepare-run-main".equals(task), prepareTest = "java-prepare-run-test".equals(task);
        if (!tests && !prepareMain && !prepareTest && !LIFECYCLE.contains(task) && !"plugin-goal".equals(task) && !"goal".equals(task)) {
            throw invalid("Unknown Maven task.");
        }
        Path project = selectedProject(params.get("project"), root, workspace, loaded);
        if ((prepareMain || prepareTest) && (project == null || params.get("modelTask") != null
                || !strings(params.get("userArguments")).isEmpty())) throw invalid("Java resource preparation needs one current project and no raw goals.");
        if (params.get("target") != null) throw invalid("Maven tasks do not accept a target selector.");
        List<String> command = new ArrayList<>();
        command.add(maven.toString());
        command.add("-B");
        command.add("-ntp");
        command.addAll(optionArguments(params, !tests && TEST_PHASES.contains(task)));
        if (project != null && !project.equals(root)) {
            command.add("-pl");
            command.add(root.relativize(project).toString());
        }
        MavenTestSelection selection = MavenTestSelection.from(params);
        if (tests) {
            if (selection.isFiltered()) {
                if (project != null && !project.equals(root)) command.add("-am");
            }
            command.add("test");
        } else if (prepareMain || prepareTest) {
            if (!project.equals(root)) command.add("-am");
            command.add("resources:resources");
            if (prepareTest) command.add("resources:testResources");
        } else if ("plugin-goal".equals(task)) {
            command.add(pluginGoal(params.get("modelTask"), project, root, loaded));
        } else if (!"goal".equals(task)) {
            if (params.get("modelTask") != null) throw invalid("Only a plugin-goal task accepts a model task.");
            command.add(task);
        }
        List<String> arguments = strings(params.get("userArguments"));
        if ("goal".equals(task) && arguments.isEmpty()) throw invalid("A custom Maven goal needs arguments.");
        command.addAll(arguments);
        // Typed selectors outrank raw CLI properties; the user must not
        // accidentally turn an exact test action into an unfiltered run.
        if (tests && selection.isFiltered()) {
            command.add(selection.argument());
            command.add("-Dsurefire.failIfNoSpecifiedTests=false");
        }
        try (MavenTestReports reports = new MavenTestReports(state, workspace)) {
        reports.instrument(command, extension, tests ? project : null);
        context.throwIfCancelled();
        Output output = new Output(context);
        Processes.Result result;
        try {
            result = context.run(command, new Processes.Options().directory(root).environment(environment)
                    .cancellation(output.cancellation).maxOutputBytes(0).onStdout(output::append).onStderr(output::append));
        } catch (CancelledException error) {
            if (output.overflow) throw new PluginError("maven.outputTooLarge", "Maven output exceeded the bounded build transcript.");
            throw error;
        }
        context.throwIfCancelled();
        Map<String, Object> testResults = reports.read(context);
        if (tests && result.exitCode() == 0 && selection.isFiltered()) {
            String owner = project == null ? null : workspace.relativize(project).toString().replace(java.io.File.separatorChar, '/');
            selection.verify(testResults, "".equals(owner) ? "." : owner);
        }
        if (tests) selection.addCoverage(testResults);
        if (Json.stringify(testResults).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES) {
            throw new PluginError("maven.outputTooLarge", "Maven's canonical test report exceeds its result budget.");
        }
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("exitCode", result.exitCode());
        reply.put("output", output.text());
        reply.put("testResults", testResults);
        return reply;
        }
    }

    /** One configuration drives model import, task execution and test execution. */
    static List<String> optionArguments(Map<String, Object> params, boolean skipTests) {
        Map<String, Object> options = params.get("options") instanceof Map ? Json.object(params.get("options")) : Collections.emptyMap();
        List<String> result = new ArrayList<>();
        Object profiles = options.get("profiles");
        if (profiles != null) {
            List<String> values = strings(profiles);
            for (String value : values) if (value.contains(",") || value.startsWith("!") || value.startsWith("-")) throw invalid("Invalid active Maven profile.");
            if (!values.isEmpty()) result.add("-P" + String.join(",", values));
        }
        String settings = optionalString(options.get("settingsFile"));
        if (settings != null && !settings.isEmpty()) { result.add("-s"); result.add(settings); }
        String repository = optionalString(options.get("localRepository"));
        if (repository != null && !repository.isEmpty()) result.add("-Dmaven.repo.local=" + repository);
        if (booleanOption(options, "offline")) result.add("-o");
        if (skipTests && booleanOption(options, "skipTests")) result.add("-DskipTests");
        return result;
    }

    private static Path selectedProject(Object selected, Path root, Path workspace, MavenRuns.Loaded loaded) throws IOException {
        if (selected == null) return null;
        if (!(selected instanceof Map)) throw invalid("Expected a Maven project selector.");
        Map<String, Object> selection = Json.object(selected);
        String path = Json.string(selection, "path");
        String name = Json.string(selection, "name");
        if (path == null || Paths.get(path).isAbsolute()) throw invalid("Expected a workspace-relative project path.");
        Path wanted = workspace.resolve(path).normalize().toRealPath();
        if (!wanted.startsWith(root) || !Files.isDirectory(wanted)) throw invalid("The selected project leaves the Maven root.");
        for (Object candidate : list(loaded.model().get("projects"))) {
            Map<String, Object> project = Json.object(candidate);
            String candidatePath = Json.string(project, "path");
            if (candidatePath != null && root.resolve(candidatePath).normalize().toRealPath().equals(wanted)
                    && name != null && name.equals(project.get("name"))) return wanted;
        }
        throw invalid("The selected project is absent from the current Maven reactor.");
    }

    private static String pluginGoal(Object selected, Path project, Path root, MavenRuns.Loaded loaded) throws IOException {
        if (!(selected instanceof Map) || project == null) throw invalid("A plugin goal needs its current model task and project.");
        Map<String, Object> task = Json.object(selected);
        String name = Json.string(task, "name");
        Object goal = Json.object(task.get("properties")).get("goal");
        for (Object candidate : list(loaded.model().get("tasks"))) {
            Map<String, Object> known = Json.object(candidate);
            String owner = Json.string(known, "project");
            if ("plugin-goal".equals(known.get("template")) && name != null && name.equals(known.get("name"))
                    && owner != null && root.resolve(owner).normalize().toRealPath().equals(project)
                    && goal instanceof String && goal.equals(Json.object(known.get("properties")).get("goal"))) return (String) goal;
        }
        throw invalid("The plugin goal is absent from the current Maven model.");
    }

    private static boolean booleanOption(Map<String, Object> options, String name) {
        Object value = options.get(name);
        if (value != null && !(value instanceof Boolean)) throw invalid("Expected a boolean Maven option.");
        return Boolean.TRUE.equals(value);
    }

    private static String optionalString(Object value) {
        if (value == null) return null;
        if (!(value instanceof String) || ((String) value).length() > 4096 || ((String) value).chars().anyMatch(Character::isISOControl)) throw invalid("Invalid Maven string value.");
        return (String) value;
    }

    private static List<String> strings(Object value) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List) || ((List<?>) value).size() > 64) throw invalid("Expected bounded Maven arguments.");
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            String text = optionalString(item);
            if (text == null || text.isEmpty()) throw invalid("Empty Maven argument.");
            result.add(text);
        }
        return result;
    }

    private static List<?> list(Object value) { return value instanceof List ? (List<?>) value : Collections.emptyList(); }
    private static PluginError invalid(String message) { return new PluginError("maven.invalidSelection", message); }

    /** Keeps progress and the final transcript in the same order, with one shared output budget. */
    private static final class Output {
        private final CallContext context;
        private final StringBuilder text = new StringBuilder();
        private int bytes;
        private final Cancellation cancellation = new Cancellation();
        private volatile boolean overflow;
        Output(CallContext context) { this.context = context; }
        synchronized void append(String chunk) {
            int size = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (overflow) return;
            if (bytes + size > MAX_OUTPUT_BYTES) {
                overflow = true;
                cancellation.cancel("Maven output exceeded the build transcript budget.");
                return;
            }
            bytes += size;
            text.append(chunk);
            context.progress(null, null, chunk);
        }
        synchronized String text() { return text.toString(); }
    }
}
