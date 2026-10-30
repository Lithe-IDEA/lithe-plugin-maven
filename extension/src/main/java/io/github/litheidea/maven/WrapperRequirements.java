package io.github.litheidea.maven;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads the selected build root's native Wrapper properties without executing it. */
final class WrapperRequirements {
    private static final Logger LOG = LoggerFactory.getLogger(WrapperRequirements.class);
    private static final Pattern DISTRIBUTION = Pattern.compile("apache-maven-([0-9]+(?:\\.[0-9]+)+)-bin\\.(?:zip|tar\\.gz)");

    static void add(MavenSession session, Map<String, Object> properties) {
        Path root = new File(session.getExecutionRootDirectory()).toPath();
        String script = Files.isRegularFile(root.resolve("mvnw")) ? "mvnw"
                : Files.isRegularFile(root.resolve("mvnw.cmd")) ? "mvnw.cmd" : null;
        if (script != null) properties.put("maven.wrapper", script);
        Path source = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        if (!Files.isRegularFile(source)) return;
        try {
            if (Files.size(source) > 1024 * 1024) throw new IllegalArgumentException("Wrapper properties exceed the input budget");
            Properties nativeValues = new Properties();
            try (InputStream input = Files.newInputStream(source)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0, count;
                while ((count = input.read(buffer, 0, Math.min(buffer.length, 1024 * 1024 + 1 - total))) != -1) {
                    total += count;
                    if (total > 1024 * 1024) throw new IllegalArgumentException("Wrapper properties exceed the input budget");
                    bytes.write(buffer, 0, count);
                }
                nativeValues.load(new ByteArrayInputStream(bytes.toByteArray()));
            }
            String distribution = nativeValues.getProperty("distributionUrl");
            if (distribution == null) return;
            String path = new URI(distribution.trim()).getPath();
            if (path == null) return;
            String filename = path.substring(path.lastIndexOf('/') + 1);
            Matcher version = DISTRIBUTION.matcher(filename);
            if (version.matches()) properties.put("maven.minimumVersion", version.group(1));
        } catch (Exception error) {
            LOG.warn("Lithe could not determine Maven Wrapper requirements: {}", error.getMessage());
        }
    }
}
