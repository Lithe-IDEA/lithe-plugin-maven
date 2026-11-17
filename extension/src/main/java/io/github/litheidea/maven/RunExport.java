package io.github.litheidea.maven;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyFilter;

/**
 * What Lithe needs to launch one project after Maven has compiled it: the
 * runtime classpath, and the classes with a {@code main} method.
 *
 * <p>Run preparation runs {@code -pl <project> -am process-classes}, so the
 * project and the reactor modules it depends on are compiled in this
 * session, and Maven's reactor resolves those modules to their output
 * folders without installing them. The classpath is Maven's runtime one:
 * the project's classes, then the {@code compile}, {@code runtime} and
 * {@code system} dependencies, in the order Maven resolves them.
 *
 * <p>Main classes are read from the compiled class files, as
 * {@code spring-boot-maven-plugin} finds its start class: every top-level
 * class with {@code public static void main(String[])}, marked when its
 * constant pool names {@code @SpringBootApplication}. At most
 * {@link #MAX_CLASSES} class files are read.
 */
final class RunExport {
    static final int MAX_CLASSES = 50_000;
    private static final String SPRING_BOOT_APPLICATION = "Lorg/springframework/boot/autoconfigure/SpringBootApplication;";
    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_STATIC = 0x0008;

    private final MavenSession session;
    private final ProjectDependenciesResolver resolver;

    RunExport(MavenSession session, ProjectDependenciesResolver resolver) {
        this.session = session;
        this.resolver = resolver;
    }

    Map<String, Object> document(String projectPath) throws MavenExecutionException {
        MavenProject project = ModelExport.project(session, projectPath);
        File output = new File(project.getBuild().getOutputDirectory());
        List<String> classpath = new ArrayList<>();
        classpath.add(ModelExport.path(output));
        classpath.addAll(dependencies(project));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "litheMavenRun");
        document.put("version", 0);
        document.put("outputDirectory", ModelExport.path(output));
        document.put("classpath", classpath);
        document.put("mainClasses", mainClasses(output));
        return document;
    }

    private List<String> dependencies(MavenProject project) throws MavenExecutionException {
        DefaultDependencyResolutionRequest request =
                new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
        DependencyFilter runtime = (node, parents) -> {
            Dependency dependency = node.getDependency();
            if (dependency == null) {
                return true;
            }
            String scope = dependency.getScope();
            return !"test".equals(scope) && !"provided".equals(scope);
        };
        request.setResolutionFilter(runtime);
        DependencyResolutionResult result;
        try {
            result = resolver.resolve(request);
        } catch (DependencyResolutionException error) {
            throw new MavenExecutionException(
                    "Lithe could not resolve the runtime classpath of " + project.getArtifactId() + ": " + error.getMessage(),
                    error);
        }
        Set<String> paths = new LinkedHashSet<>();
        for (Dependency dependency : result.getDependencies()) {
            String scope = dependency.getScope();
            if ("test".equals(scope) || "provided".equals(scope)) {
                continue;
            }
            File file = dependency.getArtifact().getFile();
            if (file != null) {
                paths.add(ModelExport.path(file));
            }
        }
        return new ArrayList<>(paths);
    }

    /** The main classes under output, sorted by name. */
    static List<Object> mainClasses(File output) {
        Map<String, Boolean> found = new TreeMap<>();
        if (!output.isDirectory()) {
            return new ArrayList<>();
        }
        Deque<File> pending = new ArrayDeque<>();
        pending.push(output);
        int budget = MAX_CLASSES;
        while (!pending.isEmpty() && budget > 0) {
            File[] children = pending.pop().listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child.isDirectory()) {
                    pending.push(child);
                } else if (child.getName().endsWith(".class") && child.getName().indexOf('$') < 0 && budget-- > 0) {
                    ClassFacts facts = read(child);
                    if (facts != null && facts.main) {
                        found.put(facts.name, facts.springBoot);
                    }
                }
            }
        }
        List<Object> result = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : found.entrySet()) {
            Map<String, Object> main = new LinkedHashMap<>();
            main.put("name", entry.getKey());
            main.put("springBoot", entry.getValue());
            result.add(main);
        }
        return result;
    }

    static final class ClassFacts {
        final String name;
        final boolean main;
        final boolean springBoot;

        ClassFacts(String name, boolean main, boolean springBoot) {
            this.name = name;
            this.main = main;
            this.springBoot = springBoot;
        }
    }

    /** The facts of one class file, or null when it is not one. */
    static ClassFacts read(File file) {
        try (InputStream stream = Files.newInputStream(file.toPath());
                DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(stream))) {
            if (in.readInt() != 0xCAFEBABE) {
                return null;
            }
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[] classNames = new int[count];
            boolean springBoot = false;
            for (int index = 1; index < count; index++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1:
                        utf8[index] = in.readUTF();
                        springBoot |= SPRING_BOOT_APPLICATION.equals(utf8[index]);
                        break;
                    case 7:
                        classNames[index] = in.readUnsignedShort();
                        break;
                    case 8: case 16: case 19: case 20:
                        in.readUnsignedShort();
                        break;
                    case 15:
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                        in.readInt();
                        break;
                    case 5: case 6:
                        in.readLong();
                        index++;
                        break;
                    default:
                        return null;
                }
            }
            in.readUnsignedShort();
            int self = in.readUnsignedShort();
            in.readUnsignedShort();
            skip(in, in.readUnsignedShort() * 2);
            int fields = in.readUnsignedShort();
            for (int index = 0; index < fields; index++) {
                skip(in, 6);
                skipAttributes(in);
            }
            boolean main = false;
            int methods = in.readUnsignedShort();
            for (int index = 0; index < methods; index++) {
                int access = in.readUnsignedShort();
                String name = utf8[in.readUnsignedShort()];
                String descriptor = utf8[in.readUnsignedShort()];
                skipAttributes(in);
                main |= (access & (ACC_PUBLIC | ACC_STATIC)) == (ACC_PUBLIC | ACC_STATIC)
                        && "main".equals(name) && "([Ljava/lang/String;)V".equals(descriptor);
            }
            String internal = self > 0 && self < count ? utf8[classNames[self]] : null;
            return internal == null ? null : new ClassFacts(internal.replace('/', '.'), main, springBoot);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int index = 0; index < attributes; index++) {
            in.readUnsignedShort();
            skip(in, in.readInt());
        }
    }

    private static void skip(DataInputStream in, int bytes) throws IOException {
        if (bytes < 0) {
            throw new IOException("negative length");
        }
        int left = bytes;
        while (left > 0) {
            int skipped = in.skipBytes(left);
            if (skipped <= 0) {
                throw new IOException("truncated class file");
            }
            left -= skipped;
        }
    }
}
