package io.github.litheidea.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String SPRING_CLOUD_APPLICATION = "org.springframework.cloud.client.SpringCloudApplication";
    private static final String ENABLE_AUTO_CONFIGURATION = "org.springframework.boot.autoconfigure.EnableAutoConfiguration";
    private static final Set<String> BUILTIN_BOOT_ANNOTATIONS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(SPRING_BOOT_APPLICATION, SPRING_CLOUD_APPLICATION)));

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_DECL = Pattern.compile("^\\s*import\\s+(?!static\\s+)([\\w.]+)(\\.\\*)?\\s*;", Pattern.MULTILINE);
    private static final Pattern ANNOTATION_USE = Pattern.compile("@([A-Za-z_][\\w.$]*)");
    private static final Pattern VOID_MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*([^)]*)\\)\\s*(?:throws\\s+[\\w$.]+(?:\\s*,\\s*[\\w$.]+)*)?\\s*\\{");
    private static final Pattern STRING_ARG = Pattern.compile(
            "^(?:final\\s+)?(?:java\\.lang\\.)?String\\s*(?:\\[\\s*]\\s*[\\w$]+|\\.\\.\\.\\s*[\\w$]+|[\\w$]+\\s*\\[\\s*])$");
    private static final Pattern DISALLOWED_MODIFIER = Pattern.compile("\\b(?:private|abstract|default)\\b");

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
        return of(project, Collections.<String>emptySet());
    }

    /** The applications of project, including any reactor custom Boot meta-annotations. */
    static List<Application> of(MavenProject project, Set<String> extraBootAnnotations) {
        List<Application> result = new ArrayList<>();
        if ("pom".equals(project.getPackaging())) {
            return result;
        }
        List<String> annotated = annotatedClasses(project, extraBootAnnotations);
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

    static boolean hasSpringBootPlugin(MavenProject project) {
        return plugin(project, "org.springframework.boot", "spring-boot-maven-plugin") != null;
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

    /** Discovers custom {@code @interface} Boot meta-annotations declared anywhere in the reactor. */
    static Set<String> customBootAnnotations(List<MavenProject> projects) {
        List<MetaAnnotation> candidates = new ArrayList<>();
        int[] budget = {MAX_FILES};
        for (MavenProject project : projects) {
            if ("pom".equals(project.getPackaging())) {
                continue;
            }
            scanMetaAnnotations(project, candidates, budget);
        }
        Set<String> known = new LinkedHashSet<>(BUILTIN_BOOT_ANNOTATIONS);
        known.add(ENABLE_AUTO_CONFIGURATION);
        Set<String> custom = new LinkedHashSet<>();
        boolean changed;
        do {
            changed = false;
            for (MetaAnnotation meta : candidates) {
                if (!custom.contains(meta.fqn) && meta.header.matchesAny(known)) {
                    custom.add(meta.fqn);
                    known.add(meta.fqn);
                    changed = true;
                }
            }
        } while (changed);
        return custom;
    }

    private static void scanMetaAnnotations(MavenProject project, List<MetaAnnotation> candidates, int[] budget) {
        forEachJavaSource(project, budget, file -> {
            MetaAnnotation meta = readMetaAnnotation(file);
            if (meta != null) {
                candidates.add(meta);
            }
        });
    }

    /** Fully qualified names of the annotated top-level classes, sorted. */
    static List<String> annotatedClasses(MavenProject project) {
        return annotatedClasses(project, Collections.<String>emptySet());
    }

    /** Fully qualified names of the annotated top-level classes, sorted. */
    static List<String> annotatedClasses(MavenProject project, Set<String> extraBootAnnotations) {
        Map<String, Boolean> found = new TreeMap<>();
        int[] budget = {MAX_FILES};
        forEachJavaSource(project, budget, file -> {
            String main = annotatedClass(file, extraBootAnnotations);
            if (main != null) {
                found.put(main, Boolean.TRUE);
            }
        });
        return new ArrayList<>(found.keySet());
    }

    private interface JavaSourceVisitor {
        void visit(File file);
    }

    private static void forEachJavaSource(MavenProject project, int[] budget, JavaSourceVisitor visitor) {
        for (String root : project.getCompileSourceRoots()) {
            File directory = new File(root);
            if (!directory.isAbsolute()) {
                directory = new File(project.getBasedir(), root);
            }
            Path path = directory.toPath();
            if (!Files.isDirectory(path)) {
                continue;
            }
            Deque<File> pending = new ArrayDeque<>();
            pending.push(directory);
            while (!pending.isEmpty() && budget[0] > 0) {
                File[] children = pending.pop().listFiles();
                if (children == null) {
                    continue;
                }
                for (File child : children) {
                    if (child.isDirectory()) {
                        pending.push(child);
                    } else if (child.getName().endsWith(".java") && budget[0]-- > 0) {
                        visitor.visit(child);
                    }
                }
            }
        }
    }

    /** The class file declares, when its head carries a recognized Boot annotation. */
    static String annotatedClass(File file) {
        return annotatedClass(file, Collections.<String>emptySet());
    }

    /** The class file declares, when its head carries a recognized or custom Boot annotation. */
    static String annotatedClass(File file, Set<String> extraBootAnnotations) {
        String raw = readHead(file);
        if (raw == null || raw.indexOf('@') < 0) {
            return null;
        }
        String text = stripCommentsAndStrings(raw);
        String simple = file.getName().substring(0, file.getName().length() - ".java".length());
        Matcher decl = Pattern.compile("(?<!@)\\b(?:class|record)\\s+" + Pattern.quote(simple) + "\\b").matcher(text);
        if (!decl.find()) {
            return null;
        }
        SourceHeader header = SourceHeader.parse(text, decl.start());
        Set<String> bootAnnotations = new LinkedHashSet<>(BUILTIN_BOOT_ANNOTATIONS);
        if (extraBootAnnotations != null) {
            bootAnnotations.addAll(extraBootAnnotations);
        }
        if (header.matchesAny(bootAnnotations)) {
            return header.qualified(simple);
        }
        if (header.matchesAny(Collections.singleton(ENABLE_AUTO_CONFIGURATION)) && hasValidMain(text)) {
            return header.qualified(simple);
        }
        return null;
    }

    private static MetaAnnotation readMetaAnnotation(File file) {
        String raw = readHead(file);
        if (raw == null || !raw.contains("@interface")) {
            return null;
        }
        String text = stripCommentsAndStrings(raw);
        String simple = file.getName().substring(0, file.getName().length() - ".java".length());
        Matcher decl = Pattern.compile("@interface\\s+" + Pattern.quote(simple) + "\\b").matcher(text);
        if (!decl.find()) {
            return null;
        }
        SourceHeader header = SourceHeader.parse(text, decl.start());
        if (header.annotations.isEmpty()) {
            return null;
        }
        return new MetaAnnotation(header.qualified(simple), header);
    }

    private static String readHead(File file) {
        try (InputStream input = Files.newInputStream(file.toPath())) {
            byte[] head = new byte[HEAD_BYTES];
            int length = 0;
            for (int read; length < head.length && (read = input.read(head, length, head.length - length)) > 0; ) {
                length += read;
            }
            return new String(head, 0, length, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean hasValidMain(String text) {
        Matcher matcher = VOID_MAIN.matcher(text);
        while (matcher.find()) {
            String parameter = matcher.group(1).trim();
            if (!parameter.isEmpty() && !STRING_ARG.matcher(parameter).matches()) {
                continue;
            }
            int boundary = Math.max(Math.max(text.lastIndexOf('{', matcher.start()), text.lastIndexOf('}', matcher.start())),
                    text.lastIndexOf(';', matcher.start()));
            String prefix = text.substring(boundary + 1, matcher.start());
            if (!DISALLOWED_MODIFIER.matcher(prefix).find()) {
                return true;
            }
        }
        return false;
    }

    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int length = source.length();
        for (int index = 0; index < length; index++) {
            char current = source.charAt(index);
            char next = index + 1 < length ? source.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                while (index < length && source.charAt(index) != '\n') {
                    index++;
                }
                out.append('\n');
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index < length && !(source.charAt(index) == '*' && index + 1 < length && source.charAt(index + 1) == '/')) {
                    index++;
                }
                index++;
                out.append(' ');
            } else if (current == '"' || current == '\'') {
                boolean block = current == '"' && source.startsWith("\"\"\"", index);
                String close = block ? "\"\"\"" : String.valueOf(current);
                index += close.length();
                while (index < length && !source.startsWith(close, index)) {
                    if (source.charAt(index) == '\\') {
                        index++;
                    }
                    index++;
                }
                index += close.length() - 1;
                out.append(current).append(current);
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static final class MetaAnnotation {
        final String fqn;
        final SourceHeader header;

        MetaAnnotation(String fqn, SourceHeader header) {
            this.fqn = fqn;
            this.header = header;
        }
    }

    private static final class SourceHeader {
        private static final Pattern ANY_IMPORT = Pattern.compile("^\\s*import\\s+[^;]+;", Pattern.MULTILINE);

        final String pkg;
        final Map<String, String> explicitImports;
        final Set<String> wildcardPackages;
        final List<String> annotations;

        private SourceHeader(String pkg, Map<String, String> explicitImports, Set<String> wildcardPackages, List<String> annotations) {
            this.pkg = pkg;
            this.explicitImports = explicitImports;
            this.wildcardPackages = wildcardPackages;
            this.annotations = annotations;
        }

        static SourceHeader parse(String text, int declStart) {
            int headerEnd = 0;
            String pkg = "";
            Matcher pkgMatcher = PACKAGE.matcher(text);
            if (pkgMatcher.find() && pkgMatcher.end() <= declStart) {
                pkg = pkgMatcher.group(1);
                headerEnd = Math.max(headerEnd, pkgMatcher.end());
            }
            Matcher allImports = ANY_IMPORT.matcher(text);
            while (allImports.find() && allImports.end() <= declStart) {
                headerEnd = Math.max(headerEnd, allImports.end());
            }
            Map<String, String> explicit = new LinkedHashMap<>();
            Set<String> wildcards = new LinkedHashSet<>();
            Matcher importMatcher = IMPORT_DECL.matcher(text);
            while (importMatcher.find() && importMatcher.end() <= declStart) {
                String target = importMatcher.group(1);
                if (importMatcher.group(2) != null) {
                    wildcards.add(target);
                } else {
                    explicit.put(simpleName(target), target);
                }
            }
            List<String> annotations = new ArrayList<>();
            Matcher annotationMatcher = ANNOTATION_USE.matcher(text.substring(headerEnd, declStart));
            while (annotationMatcher.find()) {
                annotations.add(annotationMatcher.group(1));
            }
            return new SourceHeader(pkg, explicit, wildcards, annotations);
        }

        String qualified(String simple) {
            return pkg.isEmpty() ? simple : pkg + "." + simple;
        }

        boolean matchesAny(Set<String> targets) {
            for (String raw : annotations) {
                if (raw.indexOf('.') >= 0) {
                    if (targets.contains(raw)) {
                        return true;
                    }
                    continue;
                }
                if (explicitImports.containsKey(raw)) {
                    if (targets.contains(explicitImports.get(raw))) {
                        return true;
                    }
                    continue;
                }
                if (targets.contains(qualified(raw))) {
                    return true;
                }
                for (String pkgWildcard : wildcardPackages) {
                    if (targets.contains(pkgWildcard + "." + raw)) {
                        return true;
                    }
                }
            }
            return false;
        }
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
