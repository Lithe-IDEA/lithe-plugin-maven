package io.github.litheidea.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * The applications of one Maven project that Lithe offers as run
 * configurations, read without compiling anything:
 *
 * <ul>
 * <li>Spring Boot applications: every top-level Java class annotated with
 * {@code @SpringBootApplication} in the compile source roots, as IntelliJ
 * IDEA lists them. A project that applies {@code spring-boot-maven-plugin}
 * without such a class (Kotlin sources, a generated class) is one
 * application named after the project, with the main class the plugin or
 * {@code start-class} configures, or none; run preparation then finds it in
 * the compiled classes as the plugin itself does.</li>
 * <li>Plain applications whose main class the build already names:
 * {@code exec-maven-plugin}'s {@code mainClass} or the jar manifest's
 * {@code mainClass}.</li>
 * </ul>
 *
 * <p>Sources are scanned within fixed bounds so a large project cannot make
 * reading the model slow: only {@code .java} files, at most {@link #MAX_FILES}
 * per project, and only the first {@link #HEAD_BYTES} bytes of each, where a
 * class declaration and its annotations are.
 */
final class Applications {
    /** Target tag of a Spring Boot application; plugin.json's spring-boot run selects it. */
    static final String SPRING_BOOT = "spring-boot";
    /** Target tag of an application with a configured main class. */
    static final String APPLICATION = "java-application";
    /** Target property holding the fully qualified main class, when known. */
    static final String MAIN_CLASS = "mainClass";

    static final int MAX_FILES = 20_000;
    static final int HEAD_BYTES = 64 * 1024;

    private static final Pattern ANNOTATION = Pattern.compile(
            "@(?:org\\.springframework\\.boot\\.autoconfigure\\.)?SpringBootApplication\\b");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    private Applications() {}

    /** One application: its target name, tag and main class (null when unknown). */
    static final class Application {
        final String name;
        final String tag;
        final String mainClass;

        Application(String name, String tag, String mainClass) {
            this.name = name;
            this.tag = tag;
            this.mainClass = mainClass;
        }

        Map<String, Object> target(String project) {
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("project", project);
            target.put("name", name);
            List<String> tags = new ArrayList<>();
            tags.add(tag);
            target.put("tags", tags);
            Map<String, Object> properties = new LinkedHashMap<>();
            if (mainClass != null) {
                properties.put(MAIN_CLASS, mainClass);
            }
            target.put("properties", properties);
            return target;
        }
    }

    /** The applications of project, in a stable order. */
    static List<Application> of(MavenProject project) {
        List<Application> result = new ArrayList<>();
        if ("pom".equals(project.getPackaging())) {
            return result;
        }
        List<String> annotated = annotatedClasses(project);
        for (String main : annotated) {
            result.add(new Application(name(main, annotated), SPRING_BOOT, main));
        }
        if (!annotated.isEmpty()) {
            return result;
        }
        Plugin boot = plugin(project, "org.springframework.boot", "spring-boot-maven-plugin");
        if (boot != null) {
            String main = configured(boot, "mainClass");
            if (main == null) {
                main = valid(project.getProperties().getProperty("start-class"));
            }
            result.add(new Application(project.getArtifactId(), SPRING_BOOT, main));
            return result;
        }
        String main = configured(plugin(project, "org.codehaus.mojo", "exec-maven-plugin"), "mainClass");
        if (main == null) {
            main = manifestMainClass(plugin(project, "org.apache.maven.plugins", "maven-jar-plugin"));
        }
        if (main != null) {
            result.add(new Application(simpleName(main), APPLICATION, main));
        }
        return result;
    }

    /**
     * The simple class name, or the full one when another application of
     * the project has the same simple name: Lithe identifies a target by its
     * project and name.
     */
    private static String name(String main, List<String> all) {
        String simple = simpleName(main);
        int same = 0;
        for (String other : all) {
            if (simpleName(other).equals(simple)) {
                same++;
            }
        }
        return same > 1 ? main : simple;
    }

    static String simpleName(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    /** Fully qualified names of the annotated top-level classes, sorted. */
    static List<String> annotatedClasses(MavenProject project) {
        Map<String, Boolean> found = new TreeMap<>();
        int[] budget = {MAX_FILES};
        for (String root : project.getCompileSourceRoots()) {
            File directory = new File(root);
            if (!directory.isAbsolute()) {
                directory = new File(project.getBasedir(), root);
            }
            scan(directory.toPath(), found, budget);
        }
        return new ArrayList<>(found.keySet());
    }

    private static void scan(Path root, Map<String, Boolean> found, int[] budget) {
        if (!Files.isDirectory(root)) {
            return;
        }
        Deque<File> pending = new ArrayDeque<>();
        pending.push(root.toFile());
        while (!pending.isEmpty() && budget[0] > 0) {
            File[] children = pending.pop().listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    pending.push(child);
                } else if (child.getName().endsWith(".java") && budget[0]-- > 0) {
                    String main = annotatedClass(child);
                    if (main != null) {
                        found.put(main, Boolean.TRUE);
                    }
                }
            }
        }
    }

    /** The class file declares, when its head carries the annotation. */
    static String annotatedClass(File file) {
        String text;
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] head = new byte[HEAD_BYTES];
            int length = 0;
            for (int read; length < head.length && (read = input.read(head, length, head.length - length)) > 0; ) {
                length += read;
            }
            text = new String(head, 0, length, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
        if (!text.contains("SpringBootApplication")) {
            return null;
        }
        text = LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(text).replaceAll(" ")).replaceAll(" ");
        if (!ANNOTATION.matcher(text).find()) {
            return null;
        }
        String simple = file.getName().substring(0, file.getName().length() - ".java".length());
        if (!Pattern.compile("\\b(?:class|record)\\s+" + Pattern.quote(simple) + "\\b").matcher(text).find()) {
            return null;
        }
        Matcher pkg = PACKAGE.matcher(text);
        return pkg.find() ? pkg.group(1) + "." + simple : simple;
    }

    private static Plugin plugin(MavenProject project, String groupId, String artifactId) {
        for (Plugin plugin : project.getBuildPlugins()) {
            if (groupId.equals(plugin.getGroupId()) && artifactId.equals(plugin.getArtifactId())) {
                return plugin;
            }
        }
        return null;
    }

    /** A plugin's top-level configuration value, when it is a plain class name. */
    private static String configured(Plugin plugin, String name) {
        if (plugin == null || !(plugin.getConfiguration() instanceof Xpp3Dom)) {
            return null;
        }
        Xpp3Dom child = ((Xpp3Dom) plugin.getConfiguration()).getChild(name);
        return child == null ? null : valid(child.getValue());
    }

    private static String manifestMainClass(Plugin jar) {
        if (jar == null || !(jar.getConfiguration() instanceof Xpp3Dom)) {
            return null;
        }
        Xpp3Dom node = (Xpp3Dom) jar.getConfiguration();
        for (String name : new String[] {"archive", "manifest", "mainClass"}) {
            node = node.getChild(name);
            if (node == null) {
                return null;
            }
        }
        return valid(node.getValue());
    }

    /** A Java class name, or null for anything else, such as an unresolved ${property}. */
    static String valid(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*") ? trimmed : null;
    }
}
