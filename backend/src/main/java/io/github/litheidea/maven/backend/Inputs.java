package io.github.litheidea.maven.backend;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** What one Maven run reads, as resolved for one request. */
final class Inputs {
    final Path root;
    final Path maven;
    final List<String> optionArguments;
    /** The environment Maven runs with. */
    final Map<String, String> environment;
    /** The part of it the host's options contributed. */
    final Map<String, String> hostEnvironment;
    final Path extensionJar;

    Inputs(Path root, Path maven, List<String> optionArguments, Map<String, String> environment,
            Map<String, String> hostEnvironment, Path extensionJar) {
        this.root = root;
        this.maven = maven;
        this.optionArguments = Collections.unmodifiableList(optionArguments);
        this.environment = Collections.unmodifiableMap(environment);
        this.hostEnvironment = Collections.unmodifiableMap(hostEnvironment);
        this.extensionJar = extensionJar;
    }
}
