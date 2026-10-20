package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.PluginError;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * The Maven a run uses. Lithe resolves the executable from plugin.json's
 * {@code invocation} (wrapper, MAVEN_HOME, PATH, the user's override) and
 * passes it as {@code tool.executable}. A host that predates that parameter
 * passes nothing; the same order is then followed here.
 */
final class MavenExecutable {
    private MavenExecutable() {}

    static Path resolve(String fromHost, Path root, Map<String, String> environment) {
        if (fromHost != null && !fromHost.isEmpty()) {
            Path path = root.resolve(fromHost).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                throw new PluginError("maven.notFound", "The Maven executable " + path + " is missing or not executable.");
            }
            return path;
        }
        Path wrapper = root.resolve("mvnw");
        if (Files.isRegularFile(root.resolve(".mvn/wrapper/maven-wrapper.properties")) && Files.isExecutable(wrapper)) {
            return wrapper;
        }
        String home = environment.get("MAVEN_HOME");
        if (home != null && !home.isEmpty()) {
            Path mvn = Paths.get(home, "bin", "mvn");
            if (Files.isRegularFile(mvn) && Files.isExecutable(mvn)) {
                return mvn.toAbsolutePath().normalize();
            }
        }
        String path = environment.get("PATH");
        if (path != null) {
            for (String directory : path.split(File.pathSeparator)) {
                if (directory.isEmpty()) {
                    continue;
                }
                Path mvn = Paths.get(directory, "mvn");
                if (Files.isRegularFile(mvn) && Files.isExecutable(mvn)) {
                    return mvn.toAbsolutePath().normalize();
                }
            }
        }
        throw new PluginError("maven.notFound",
                "Lithe found no Maven: no Maven Wrapper in the project, no MAVEN_HOME and no mvn on PATH. "
                        + "Install Maven 3.6.3 or newer, for example with `brew install maven`.");
    }
}
