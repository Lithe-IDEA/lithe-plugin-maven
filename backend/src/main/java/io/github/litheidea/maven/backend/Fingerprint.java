package io.github.litheidea.maven.backend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A digest of everything that can change what Maven reports for a root. A
 * snapshot is only reused when its fingerprint equals the current one.
 *
 * <ul>
 *   <li>the content of every {@code pom.xml} under the root (a bounded walk
 *       that skips {@code target/}, {@code .git/} and {@code node_modules/});</li>
 *   <li>{@code .mvn/maven.config}, {@code extensions.xml}, {@code jvm.config}
 *       and the wrapper properties;</li>
 *   <li>the user and global {@code settings.xml}, the user {@code toolchains.xml},
 *       and settings files named by {@code -s}/{@code -gs};</li>
 *   <li>the option arguments, the environment Maven reads, the executable's
 *       path and modification time, {@code java.version}, and the extension
 *       and backend versions.</li>
 * </ul>
 *
 * <p>Inputs outside these, such as a parent POM outside the root or a changed
 * SNAPSHOT in a remote repository, need an explicit refresh.
 */
final class Fingerprint {
    /** Bumped when the inputs below change meaning. */
    private static final int VERSION = 1;
    private static final int MAX_DEPTH = 24;
    private static final int MAX_ENTRIES = 50_000;
    private static final Set<String> SKIPPED = new HashSet<>(Arrays.asList("target", ".git", "node_modules"));
    private static final List<String> ENVIRONMENT = Arrays.asList(
            "JAVA_HOME", "MAVEN_HOME", "M2_HOME", "MAVEN_OPTS", "MAVEN_ARGS", "MAVEN_CONFIG");
    private static final List<String> SETTINGS_FLAGS = Arrays.asList(
            "-s", "--settings", "-gs", "--global-settings", "-t", "--toolchains", "-gt", "--global-toolchains");

    private final MessageDigest digest;

    private Fingerprint() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    static String of(Inputs inputs) throws IOException {
        Fingerprint fingerprint = new Fingerprint();
        fingerprint.add("fingerprint", Integer.toString(VERSION));
        fingerprint.add("backend", MavenBackend.VERSION);
        fingerprint.add("root", inputs.root.toString());
        fingerprint.addPoms(inputs.root);
        for (String name : Arrays.asList("maven.config", "extensions.xml", "jvm.config", "wrapper/maven-wrapper.properties")) {
            fingerprint.addFile(".mvn/" + name, inputs.root.resolve(".mvn").resolve(name));
        }

        Path userHome = Paths.get(System.getProperty("user.home"));
        fingerprint.addFile("userSettings", userHome.resolve(".m2/settings.xml"));
        fingerprint.addFile("userToolchains", userHome.resolve(".m2/toolchains.xml"));
        Path mavenHome = mavenHome(inputs);
        if (mavenHome != null) {
            fingerprint.add("mavenHome", mavenHome.toString());
            fingerprint.addFile("globalSettings", mavenHome.resolve("conf/settings.xml"));
        }
        List<String> configArguments = mavenConfig(inputs.root);
        for (List<String> arguments : Arrays.asList(inputs.optionArguments, configArguments)) {
            for (String file : settingsFiles(arguments)) {
                fingerprint.addFile("settingsFile " + file, inputs.root.resolve(file));
            }
        }

        fingerprint.add("optionArguments", String.join("\u0000", inputs.optionArguments));
        for (Map.Entry<String, String> entry : new TreeMap<>(inputs.hostEnvironment).entrySet()) {
            fingerprint.add("hostEnvironment " + entry.getKey(), entry.getValue());
        }
        for (String name : ENVIRONMENT) {
            fingerprint.add("environment " + name, inputs.environment.get(name));
        }
        fingerprint.add("maven", inputs.maven.toString());
        fingerprint.addStamp("mavenStamp", inputs.maven);
        fingerprint.add("java.version", System.getProperty("java.version"));
        fingerprint.addFile("extension", inputs.extensionJar);
        return fingerprint.hex();
    }

    /** {@code -s file}, {@code --settings file}, {@code --settings=file} and {@code -sfile} forms. */
    static List<String> settingsFiles(List<String> arguments) {
        List<String> files = new java.util.ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            for (String flag : SETTINGS_FLAGS) {
                if (argument.equals(flag) && index + 1 < arguments.size()) {
                    files.add(arguments.get(++index));
                    break;
                }
                if (flag.startsWith("--") && argument.startsWith(flag + "=")) {
                    files.add(argument.substring(flag.length() + 1));
                    break;
                }
            }
        }
        return files;
    }

    /** The Maven installation an {@code mvn} script belongs to; none for a wrapper. */
    private static Path mavenHome(Inputs inputs) {
        if (inputs.maven.getFileName().toString().startsWith("mvnw")) {
            return null;
        }
        try {
            Path real = inputs.maven.toRealPath();
            Path bin = real.getParent();
            return bin == null || bin.getParent() == null ? null : bin.getParent();
        } catch (IOException error) {
            return null;
        }
    }

    private static List<String> mavenConfig(Path root) {
        Path config = root.resolve(".mvn/maven.config");
        if (!Files.isRegularFile(config)) {
            return Collections.emptyList();
        }
        try {
            String text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? Collections.emptyList() : Arrays.asList(text.split("\\s+"));
        } catch (IOException error) {
            return Collections.emptyList();
        }
    }

    private void addPoms(Path root) throws IOException {
        TreeMap<String, String> poms = new TreeMap<>();
        int[] entries = {0};
        boolean[] truncated = {false};
        Files.walkFileTree(root, Collections.emptySet(), MAX_DEPTH, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(root) && SKIPPED.contains(directory.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return count();
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile() && file.getFileName().toString().equals("pom.xml")) {
                    poms.put(root.relativize(file).toString(), sha256(Files.readAllBytes(file)));
                }
                return count();
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return count();
            }

            private FileVisitResult count() {
                if (++entries[0] > MAX_ENTRIES) {
                    truncated[0] = true;
                    return FileVisitResult.TERMINATE;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        for (Map.Entry<String, String> pom : poms.entrySet()) {
            add("pom " + pom.getKey(), pom.getValue());
        }
        add("pomWalk", truncated[0] ? "truncated" : "complete");
    }

    private void addFile(String label, Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            add(label, sha256(Files.readAllBytes(path)));
        } else {
            add(label, null);
        }
    }

    private void addStamp(String label, Path path) {
        try {
            Path real = path.toRealPath();
            add(label, real + " " + Files.getLastModifiedTime(real).toMillis() + " " + Files.size(real));
        } catch (IOException error) {
            add(label, null);
        }
    }

    private void add(String label, String value) {
        digest.update(label.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        // Absent and empty differ.
        digest.update(value == null ? new byte[] {1} : ("=" + value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String hex() {
        return hex(digest.digest());
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0xf, 16)).append(Character.forDigit(value & 0xf, 16));
        }
        return text.toString();
    }
}
