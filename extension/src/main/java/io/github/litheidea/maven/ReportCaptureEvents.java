package io.github.litheidea.maven;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MojoExecutionEvent;
import org.apache.maven.execution.ExecutionEvent;
import org.apache.maven.eventspy.AbstractEventSpy;
import org.apache.maven.execution.MojoExecutionListener;
import org.apache.maven.plugin.MojoExecutionException;

/** Captures only fresh reports from the configured upstream Surefire/Failsafe mojo. */
@Named("lithe-operation-reports")
@Singleton
public final class ReportCaptureEvents extends AbstractEventSpy implements MojoExecutionListener {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_REPORTS = 10000;
    private final Map<Key, Capture> active = new HashMap<>();
    private final List<Object> reports = new ArrayList<>();
    private int copiedBytes;
    private int activeBytes;
    private String failure;

    @Override
    public synchronized void beforeMojoExecution(MojoExecutionEvent event) throws MojoExecutionException {
        String output = output(event);
        if (output == null || !testMojo(event)) return;
        try {
            Path state = Paths.get(output).toAbsolutePath().normalize().getParent().toRealPath();
            Path workspace = Paths.get(event.getSession().getUserProperties().getProperty("lithe.reportWorkspace")).toRealPath();
            Path project = event.getProject().getBasedir().toPath().toRealPath();
            if (!project.startsWith(workspace)) throw new IOException("Report project leaves the workspace.");
            // The actual configured mojo owns interpolation and -D precedence.
            // Both upstream plugins expose this public getter; there is no POM
            // parser, default-directory guess or parallel parameter evaluator.
            Object directory = event.getMojo().getClass().getMethod("getReportsDirectory").invoke(event.getMojo());
            if (!(directory instanceof File)) throw new IOException("The test mojo supplied no report directory.");
            Path path = ((File) directory).toPath().toAbsolutePath().normalize();
            Map<Path, Stamp> before = snapshot(path, workspace);
            int bytes = 0;
            for (Stamp stamp : before.values()) bytes += stamp.bytes.length;
            if (bytes > MAX_BYTES - activeBytes) throw new IOException("Concurrent report snapshots exceed the byte budget.");
            activeBytes += bytes;
            active.put(new Key(event.getExecution(), event.getProject()), new Capture(state, workspace, project, path, before));
        } catch (Exception error) {
            failure = "Could not inspect the configured test report directory.";
            publish(output);
            throw new MojoExecutionException(failure, error);
        }
    }

    @Override
    public synchronized void afterMojoExecutionSuccess(MojoExecutionEvent event) throws MojoExecutionException {
        finish(event);
    }

    @Override
    public synchronized void afterExecutionFailure(MojoExecutionEvent event) {
        try { finish(event); }
        catch (MojoExecutionException error) { /* The published failure is read by the backend after Maven exits. */ }
    }

    private void finish(MojoExecutionEvent event) throws MojoExecutionException {
        finish(new Key(event.getExecution(), event.getProject()));
    }

    @Override
    public synchronized void onEvent(Object event) throws Exception {
        // Maven 3.9 does not notify MojoExecutionListener for checked test
        // failures. Its execution event still covers every failed mojo, after
        // release of the configured instance whose directory we captured.
        if (event instanceof ExecutionEvent && ((ExecutionEvent) event).getType() == ExecutionEvent.Type.MojoFailed) {
            ExecutionEvent failed = (ExecutionEvent) event;
            finish(new Key(failed.getMojoExecution(), failed.getProject()));
        }
    }

    private void finish(Key key) throws MojoExecutionException {
        Capture capture = active.remove(key);
        if (capture == null) return;
        for (Stamp stamp : capture.before.values()) activeBytes -= stamp.bytes.length;
        String output = capture.state.resolve("index.json").toString();
        try {
            for (Map.Entry<Path, Stamp> entry : snapshot(capture.directory, capture.workspace).entrySet()) {
                if (entry.getValue().same(capture.before.get(entry.getKey()))) continue;
                byte[] bytes = entry.getValue().bytes;
                if (reports.size() >= MAX_REPORTS || bytes.length > MAX_BYTES - copiedBytes) throw new IOException("Report budget exceeded.");
                String name = "report-" + reports.size() + ".xml";
                Files.write(capture.state.resolve(name), bytes, StandardOpenOption.CREATE_NEW);
                copiedBytes += bytes.length;
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("file", name);
                report.put("sha256", hash(bytes));
                report.put("project", capture.workspace.relativize(capture.project).toString().replace(File.separatorChar, '/'));
                reports.add(report);
            }
            publish(output);
        } catch (Exception error) {
            failure = "Could not capture the current Maven test reports.";
            publish(output);
            throw new MojoExecutionException(failure, error);
        }
    }

    private void publish(String output) throws MojoExecutionException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("format", "litheMavenOperationReports"); value.put("version", 1);
        value.put("reports", reports);
        if (failure != null) value.put("error", failure);
        try { LitheLifecycleParticipant.write(output, Json.write(value)); }
        catch (org.apache.maven.MavenExecutionException error) { throw new MojoExecutionException("Could not publish Maven report capture.", error); }
    }

    private static boolean testMojo(MojoExecutionEvent event) {
        if (!"org.apache.maven.plugins".equals(event.getExecution().getGroupId())) return false;
        String artifact = event.getExecution().getArtifactId();
        String goal = event.getExecution().getGoal();
        return "maven-surefire-plugin".equals(artifact) && "test".equals(goal)
            || "maven-failsafe-plugin".equals(artifact) && "integration-test".equals(goal);
    }

    private static String output(MojoExecutionEvent event) {
        return event.getSession().getUserProperties().getProperty("lithe.reportOutput");
    }

    private static Map<Path, Stamp> snapshot(Path directory, Path workspace) throws IOException {
        Map<Path, Stamp> result = new LinkedHashMap<>();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return result;
        Path real = directory.toRealPath();
        if (!real.startsWith(workspace) || !Files.isDirectory(real)) throw new IOException("Reports leave the workspace.");
        int total = 0;
        try (Stream<Path> files = Files.list(real)) {
            int visited = 0;
            for (Path file : (Iterable<Path>) files.limit(MAX_REPORTS + 1L).sorted()::iterator) {
                if (++visited > MAX_REPORTS) throw new IOException("Report count exceeded.");
                String name = file.getFileName().toString();
                if (!name.startsWith("TEST-") || !name.endsWith(".xml")) continue;
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Report is not a regular file.");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.size() > MAX_BYTES - total) throw new IOException("Report byte budget exceeded.");
                byte[] bytes = read(file, MAX_BYTES - total); total += bytes.length;
                result.put(file, new Stamp(attributes, bytes));
            }
        }
        return result;
    }

    private static byte[] read(Path file, int remaining) throws IOException {
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192]; int count;
            while ((count = input.read(chunk)) >= 0) {
                if (count > remaining - output.size()) throw new IOException("Report byte budget exceeded.");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder value = new StringBuilder();
        for (byte part : MessageDigest.getInstance("SHA-256").digest(bytes)) value.append(String.format("%02x", part & 255));
        return value.toString();
    }

    private static final class Key {
        final Object execution, project;
        Key(Object execution, Object project) { this.execution = execution; this.project = project; }
        @Override public boolean equals(Object other) { return other instanceof Key && ((Key) other).execution == execution && ((Key) other).project == project; }
        @Override public int hashCode() { return 31 * System.identityHashCode(execution) + System.identityHashCode(project); }
    }

    private static final class Capture {
        final Path state, workspace, project, directory;
        final Map<Path, Stamp> before;
        Capture(Path state, Path workspace, Path project, Path directory, Map<Path, Stamp> before) {
            this.state = state; this.workspace = workspace; this.project = project; this.directory = directory; this.before = before;
        }
    }
    private static final class Stamp {
        final BasicFileAttributes attributes;
        final byte[] bytes;
        Stamp(BasicFileAttributes attributes, byte[] bytes) { this.attributes = attributes; this.bytes = bytes; }
        boolean same(Stamp other) {
            return other != null && attributes.lastModifiedTime().equals(other.attributes.lastModifiedTime())
                && java.util.Objects.equals(attributes.fileKey(), other.attributes.fileKey()) && Arrays.equals(bytes, other.bytes);
        }
    }
}
