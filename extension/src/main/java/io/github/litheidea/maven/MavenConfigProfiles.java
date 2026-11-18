package io.github.litheidea.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.cli.ParseException;
import org.apache.maven.cli.CLIManager;

/** Restores the project-side profile switches Maven merges with the invocation's arguments. */
final class MavenConfigProfiles {
    private MavenConfigProfiles() {}

    static void read(File root, boolean maven39OrLater, List<String> active, List<String> inactive)
            throws IOException, ParseException {
        if (root == null) {
            return;
        }
        Path config = root.toPath().resolve(".mvn").resolve("maven.config");
        if (!Files.isRegularFile(config)) {
            return;
        }
        String[] arguments;
        // Maven 3.9 changed from whitespace tokens to one UTF-8 argument per line.
        if (maven39OrLater) {
            try (Stream<String> lines = Files.lines(config, StandardCharsets.UTF_8)) {
                arguments = lines.filter(line -> !line.isEmpty() && !line.startsWith("#")).toArray(String[]::new);
            }
        } else {
            arguments = new String(Files.readAllBytes(config), Charset.defaultCharset()).trim().split("\\s+");
        }
        String[] groups = new CLIManager().parse(arguments).getOptionValues(CLIManager.ACTIVATE_PROFILES);
        if (groups == null) {
            return;
        }
        for (String group : groups) {
            for (String value : group.split(",")) {
                String profile = value.trim();
                if (profile.startsWith("-") || profile.startsWith("!")) {
                    inactive.add(profile.substring(1));
                } else if (!profile.isEmpty()) {
                    active.add(profile.startsWith("+") ? profile.substring(1) : profile);
                }
            }
        }
    }
}
