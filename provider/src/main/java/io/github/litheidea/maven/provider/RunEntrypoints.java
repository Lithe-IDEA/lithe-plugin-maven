package io.github.litheidea.maven.provider;

import io.github.litheidea.plugin.sdk.PluginError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers {@code lithe/build/runEntrypoints} (buildProviders 1): the Java
 * class with a {@code main} method in one source file, which Run on Current
 * File starts through the {@code main} run with the class as
 * {@code launchOptions.entrypoint}.
 *
 * <p>Nothing is compiled: the file's text decides. The class is the file's
 * top-level class, named after the file, in the file's package, and it has a
 * {@code static void main(String[])} or {@code static void main(String...)}
 * method. The project is the nearest directory above the file that has a
 * {@code pom.xml}, inside the build root. Test sources are left out, since a
 * run starts on the runtime classpath, which holds no test classes.
 */
final class RunEntrypoints {
    static final String RUN = "main";
    private static final long MAX_SOURCE_BYTES = 4L * 1024 * 1024;
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern VOID_MAIN = Pattern.compile(
            "\\bvoid\\s+main\\s*\\(\\s*([^)]*)\\)\\s*(?:throws\\s+[\\w$.]+(?:\\s*,\\s*[\\w$.]+)*)?\\s*\\{");
    private static final Pattern STRING_ARG = Pattern.compile(
            "^(?:final\\s+)?(?:java\\.lang\\.)?String\\s*(?:\\[\\s*]\\s*[\\w$]+|\\.\\.\\.\\s*[\\w$]+|[\\w$]+\\s*\\[\\s*])$");
    private static final Pattern DISALLOWED_MODIFIER = Pattern.compile("\\b(?:private|abstract|default)\\b");
    private static final Pattern ANY_TYPE_DECL = Pattern.compile("\\b(?:class|record|enum|interface)\\b");
    private static final Pattern ARTIFACT = Pattern.compile("<artifactId>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern PARENT = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);

    private final Path workspace;

    RunEntrypoints(Path workspace) {
        this.workspace = workspace;
    }

    Map<String, Object> find(Map<String, Object> params) throws IOException {
        Object rootParam = params.get("root");
        Object fileParam = params.get("file");
        if (!(fileParam instanceof String)) {
            throw new PluginError("maven.entrypoints.noFile", "Lithe sent no source file.");
        }
        Path root = workspace.resolve(rootParam instanceof String ? (String) rootParam : ".").normalize();
        Path file = workspace.resolve((String) fileParam).normalize();
        List<Object> entrypoints = new ArrayList<>();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("entrypoints", entrypoints);
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (!file.startsWith(root) || !fileName.endsWith(".java") || !Files.isRegularFile(file) || Files.size(file) > MAX_SOURCE_BYTES) {
            return answer;
        }
        Path module = module(root, file);
        if (module == null || isTestSource(module.relativize(file))) {
            return answer;
        }
        String text = stripComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        String simple = fileName.substring(0, fileName.length() - ".java".length());
        int mainIndex = firstValidMainIndex(text);
        if (mainIndex < 0) {
            return answer;
        }
        Matcher pkg = PACKAGE.matcher(text);
        boolean hasPackage = pkg.find();
        boolean declaresSimple = Pattern.compile("(?<!@)\\b(?:class|record|enum|interface)\\s+" + Pattern.quote(simple) + "\\b")
                .matcher(text).find();
        boolean declaresTypeBeforeMain = ANY_TYPE_DECL.matcher(text.substring(0, mainIndex)).find();
        if (!declaresSimple && (hasPackage || declaresTypeBeforeMain)) {
            return answer;
        }
        String className = declaresSimple && hasPackage ? pkg.group(1) + "." + simple : simple;
        String projectPath = root.relativize(module).toString().replace('\\', '/');
        if (projectPath.isEmpty()) {
            projectPath = ".";
        }
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("name", artifactId(module));
        project.put("path", projectPath);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("run", RUN);
        entry.put("name", simple);
        entry.put("entrypoint", className);
        entry.put("project", project);
        entrypoints.add(entry);
        return answer;
    }

    /** The nearest directory from the file up to the root that has a pom.xml, or null. */
    static Path module(Path root, Path file) {
        for (Path directory = file.getParent(); directory != null && directory.startsWith(root); directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("pom.xml"))) {
                return directory;
            }
        }
        return null;
    }

    /** Whether a module-relative path is under Maven's test source tree. */
    static boolean isTestSource(Path relative) {
        return relative.getNameCount() > 1 && "src".equals(relative.getName(0).toString()) && "test".equals(relative.getName(1).toString());
    }

    /** The module's own artifactId, or its directory name when the POM names none. */
    static String artifactId(Path module) {
        try {
            String pom = stripXmlComments(new String(Files.readAllBytes(module.resolve("pom.xml")), StandardCharsets.UTF_8));
            Matcher matcher = ARTIFACT.matcher(PARENT.matcher(pom).replaceAll(""));
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (IOException ignored) {
            // The directory name still names the project.
        }
        Path name = module.getFileName();
        return name == null ? "project" : name.toString();
    }

    /**
     * The source without comments and with string and character literals
     * emptied, so a {@code main} in a comment or a string does not count.
     */
    static String stripComments(String source) {
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

    private static int firstValidMainIndex(String text) {
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
                return matcher.start();
            }
        }
        return -1;
    }

    private static String stripXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", "");
    }
}
