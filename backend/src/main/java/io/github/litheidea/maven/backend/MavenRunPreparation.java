package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates a current Maven target and returns a host-owned Maven launch. */
final class MavenRunPreparation {
    private MavenRunPreparation() {}

    static Map<String, Object> prepare(Map<String, Object> params, Path root, Path workspace,
            MavenRuns.Loaded loaded, Path extension) throws Exception {
        Map<String, Object> options = Json.object(params.get("launchOptions"));
        String run = Json.string(params, "run");
        boolean framework = "framework".equals(run);
        if (!(framework || "module".equals(run)) || !(framework ? "service" : "task").equals(options.get("execution"))
                || !strings(params.get("userArguments")).isEmpty()) throw invalid("Select a Maven run with typed launch options.");
        Path project = MavenExecution.selectedProject(params.get("project"), root, workspace, loaded);
        if (project == null) throw invalid("A Maven run needs one current project.");
        Map<String, Object> selected = Json.object(params.get("target"));
        Map<String, Object> target = null;
        for (Object candidate : list(loaded.model().get("targets"))) {
            Map<String, Object> known = Json.object(candidate);
            String path = Json.string(known, "path");
            if (path != null && root.resolve(path).toRealPath().equals(project)
                    && known.get("name").equals(selected.get("name"))
                    && workspace.resolve(Json.string(selected, "path")).toRealPath().equals(project)
                    && strings(known.get("tags")).contains(framework ? "maven-service" : "maven-module")) target = known;
        }
        if (target == null) throw invalid("The run target is absent from the current Maven model.");
        Map<String, Object> properties = Json.object(target.get("properties"));
        String cwd = options.containsKey("workingDirectory") ? Json.string(options, "workingDirectory") : relative(workspace, root);
        Path directory = workspace.resolve(cwd).toRealPath();
        if (!directory.startsWith(workspace) || !Files.isDirectory(directory)) throw invalid("The run working directory leaves the workspace.");
        List<String> arguments = new ArrayList<>(Arrays.asList("-B", "-ntp", "--file", root.resolve("pom.xml").toString()));
        arguments.addAll(MavenExecution.optionArguments(params, true));
        if (!project.equals(root)) { arguments.add("-pl"); arguments.add(root.relativize(project).toString()); arguments.add("-am"); }
        List<String> runtimeArguments = strings(options.get("runtimeArguments"));
        List<String> programArguments = strings(options.get("arguments"));
        if (framework) {
            if (!"1".equals(properties.get("run.preparationVersion"))) throw invalid("The Maven model cannot prepare this framework run. Refresh or update the plugin.");
            addArguments(arguments, "runtime", runtimeArguments);
            addArguments(arguments, "arguments", programArguments);
            String entrypoint = Json.string(options, "entrypoint");
            if (entrypoint != null) addProperty(arguments, properties, "run.entrypointProperty", entrypoint);
            if (options.containsKey("workingDirectory")) {
                if (!properties.containsKey("run.workingDirectoryParameter")) throw invalid("This framework cannot override its application working directory.");
                arguments.add("-Dlithe.run.workingDirectory=" + directory);
            }
            arguments.add("-Dmaven.ext.class.path=" + extension);
            arguments.add("-Dlithe.run.project=" + project);
            arguments.add("-Dlithe.run.goal=" + properties.get("run.goal"));
            arguments.add("test-compile");
        } else {
            if (!runtimeArguments.isEmpty() || options.containsKey("entrypoint")) throw invalid("Maven module goals do not accept application runtime or entrypoint options.");
            if (programArguments.isEmpty()) throw invalid("A Maven module run needs one or more goals.");
            modelArguments(params); // The same checked properties were supplied to the model read.
            arguments.addAll(programArguments);
        }
        Map<String, Object> environment = new LinkedHashMap<>();
        if (params.get("environment") instanceof Map) environment.putAll(Json.object(params.get("environment")));
        environment.putAll(Json.object(options.get("environment")));
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> executable = new LinkedHashMap<>(); executable.put("kind", "toolchain"); executable.put("value", "project-maven");
        result.put("executable", executable); result.put("arguments", arguments); result.put("workingDirectory", cwd); result.put("environment", environment);
        return result;
    }

    /** Maven parses property values itself; model reads and goal execution receive identical argv. */
    static List<String> modelArguments(Map<String, Object> params) {
        List<String> properties = new ArrayList<>();
        if (!"module".equals(params.get("run"))) return properties;
        for (String argument : strings(Json.object(params.get("launchOptions")).get("arguments"))) {
            if (argument.startsWith("-D") && argument.length() > 2 && !argument.startsWith("-Dlithe.")
                    && !argument.startsWith("-Dmaven.ext.class.path") && !argument.startsWith("-Dmaven.repo.local")) properties.add(argument);
            else if (argument.startsWith("-")) throw invalid("Configure Maven profiles, settings, repositories and project selection through the build options before running goals.");
        }
        return properties;
    }

    private static void addProperty(List<String> arguments, Map<String, Object> properties, String field, String value) {
        if (value.isEmpty()) return;
        String property = Json.string(properties, field);
        if (property == null || !property.matches("[A-Za-z][A-Za-z0-9._-]*")) throw invalid("The framework cannot accept this launch choice.");
        arguments.add("-D" + property + "=" + value);
    }

    /** Indexed Maven user properties preserve argv until the actual Mojo descriptor is available. */
    private static void addArguments(List<String> arguments, String kind, List<String> values) {
        if (values.stream().anyMatch(value -> value.trim().isEmpty())) throw invalid("This Maven framework cannot preserve empty application arguments.");
        arguments.add("-Dlithe.run." + kind + ".count=" + values.size());
        for (int index = 0; index < values.size(); index++) arguments.add("-Dlithe.run." + kind + "." + index + "=" + values.get(index));
    }

    private static String relative(Path workspace, Path path) {
        String value = workspace.relativize(path).toString().replace(java.io.File.separatorChar, '/');
        return value.isEmpty() ? "." : value;
    }
    private static List<?> list(Object value) { return value instanceof List ? (List<?>) value : Collections.emptyList(); }
    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        for (Object item : list(value)) { if (!(item instanceof String)) throw invalid("Expected an argument array."); result.add((String) item); }
        return result;
    }
    private static PluginError invalid(String message) { return new PluginError("maven.invalidRun", message); }
}
