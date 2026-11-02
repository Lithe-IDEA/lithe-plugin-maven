package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Cancellation;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import io.github.litheidea.plugin.sdk.Processes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Asks the selected Maven to initialize its own settings and exit before
 * project import. The extension exports only non-sensitive fields; no complete
 * settings document or Maven debug output is returned over the provider wire.
 */
final class ConfigurationProbe {
    static Map<String, Object> read(Inputs inputs, Path state, Cancellation cancellation) throws IOException {
        Path work = state == null ? Files.createTempDirectory("lithe-maven-configuration-")
                : Files.createTempDirectory(state, "configuration-");
        try {
            Path output = work.resolve("configuration.json");
            List<String> command = new ArrayList<>();
            command.add(inputs.maven.toString());
            command.add("-B"); command.add("-ntp"); command.add("-o");
            command.addAll(inputs.optionArguments);
            command.add("-Dmaven.ext.class.path=" + inputs.extensionJar);
            command.add("-Dlithe.configurationOutput=" + output);
            command.add("validate");
            Processes.Result result = Processes.run(command, new Processes.Options()
                    .directory(inputs.root).environment(inputs.environment).cancellation(cancellation)
                    .maxOutputBytes(16 * 1024));
            cancellation.throwIfCancelled();
            // The extension intentionally aborts Maven after exporting settings.
            // A failed invocation without that fresh output must never be
            // represented as empty/default settings or disclose debug output.
            if (!Files.isRegularFile(output) || Files.size(output) > 2 * 1024 * 1024) {
                throw new PluginError("maven.configurationUnavailable", "Maven could not inspect its native settings (exit status "
                        + result.exitCode() + "). Check the selected Maven installation and settings sources.");
            }
            Object value = Json.parse(new String(Files.readAllBytes(output), StandardCharsets.UTF_8));
            if (!(value instanceof Map) || !"litheBuildConfiguration".equals(Json.object(value).get("format"))) {
                throw new PluginError("maven.invalidConfiguration", "Maven returned an invalid native configuration snapshot.");
            }
            return Json.object(value);
        } finally {
            // Every probe owns its temporary files. A cleanup failure is visible
            // instead of leaving unbounded native settings snapshots behind.
            try (Stream<Path> paths = Files.walk(work)) {
                List<Path> owned = paths.sorted(Comparator.reverseOrder()).limit(1025).collect(Collectors.toList());
                if (owned.size() > 1024) throw new IOException("The Maven configuration probe exceeded its cleanup limit.");
                for (Path path : owned) Files.deleteIfExists(path);
            }
        }
    }
}
