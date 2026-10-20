package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Backend;
import io.github.litheidea.plugin.sdk.Cancellation;
import io.github.litheidea.plugin.sdk.CancelledException;
import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import io.github.litheidea.plugin.sdk.Processes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Answers a request from the snapshot, or from one Maven run shared by every
 * request that needs it.
 *
 * <p>A run belongs to no single request: it goes on while any request still
 * waits for it and is stopped once all of them were cancelled. Its result is
 * saved only when the inputs did not change while Maven ran, so a POM edited
 * mid-run never hides behind a snapshot of the old one.
 */
final class MavenRuns {
    /** The model and the dependency tree of every module of one root. */
    static final class Loaded {
        private final Map<String, Object> model;
        private final Map<String, Object> dependencies;

        Loaded(Map<String, Object> model, Map<String, Object> dependencies) {
            this.model = model;
            this.dependencies = dependencies;
        }

        Map<String, Object> model() {
            return model;
        }

        Map<String, Object> dependencies() {
            return dependencies;
        }
    }

    /** One Maven run and the requests waiting for it. */
    private static final class Flight {
        final String key;
        final CompletableFuture<Loaded> result = new CompletableFuture<>();
        final Cancellation cancellation = new Cancellation();
        int waiters;

        Flight(String key) {
            this.key = key;
        }
    }

    private static final int ERROR_LINES = 40;

    private final Snapshots snapshots;
    private final Backend backend;
    private final Path workDirectory;
    private final Map<String, Flight> flights = new HashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "maven-run");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @param workDirectory where a run's output files go; the system's
     *     temporary directory when null
     */
    MavenRuns(Snapshots snapshots, Backend backend, Path workDirectory) {
        this.snapshots = snapshots;
        this.backend = backend;
        this.workDirectory = workDirectory;
    }

    Loaded load(Inputs inputs, boolean refresh, Cancellation request) throws Exception {
        String fingerprint = Fingerprint.of(inputs);
        if (!refresh && snapshots != null) {
            Loaded stored = snapshots.read(inputs.root, fingerprint);
            if (stored != null) {
                return stored;
            }
        }
        request.throwIfCancelled();
        Flight flight;
        synchronized (flights) {
            String key = inputs.root + "\u0000" + fingerprint;
            flight = flights.get(key);
            if (flight == null) {
                Flight started = new Flight(key);
                flights.put(key, started);
                executor.execute(() -> fly(started, inputs, fingerprint));
                flight = started;
            }
            flight.waiters++;
        }
        Flight joined = flight;
        CompletableFuture<Loaded> mine = new CompletableFuture<>();
        joined.result.whenComplete((value, error) -> {
            if (error != null) {
                mine.completeExceptionally(error);
            } else {
                mine.complete(value);
            }
        });
        Cancellation.Registration registration = request.onCancel(() -> {
            leave(joined);
            mine.completeExceptionally(new CancelledException("The request was cancelled."));
        });
        try {
            return mine.get();
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw error;
        } finally {
            registration.remove();
        }
    }

    /** The last waiter leaving stops the run. */
    private void leave(Flight flight) {
        synchronized (flights) {
            if (--flight.waiters > 0 || flight.result.isDone()) {
                return;
            }
            flights.remove(flight.key, flight);
        }
        flight.cancellation.cancel("Every request waiting for Maven was cancelled.");
    }

    private void fly(Flight flight, Inputs inputs, String before) {
        try {
            Loaded loaded = run(inputs, flight.cancellation);
            if (snapshots != null) {
                if (before.equals(Fingerprint.of(inputs))) {
                    snapshots.write(inputs.root, before, loaded);
                } else {
                    backend.log("The Maven project changed while it was read; the result is not saved.");
                }
            }
            flight.result.complete(loaded);
        } catch (Throwable error) {
            flight.result.completeExceptionally(error);
        } finally {
            synchronized (flights) {
                flights.remove(flight.key, flight);
            }
        }
    }

    private Loaded run(Inputs inputs, Cancellation cancellation) throws IOException {
        Path work = workDirectory == null
                ? Files.createTempDirectory("lithe-maven-")
                : Files.createTempDirectory(workDirectory, "run-");
        try {
            Path model = work.resolve("model.json");
            Path dependencies = work.resolve("dependencies.json");
            List<String> command = new ArrayList<>();
            command.add(inputs.maven.toString());
            command.add("-B");
            command.add("-ntp");
            command.addAll(inputs.optionArguments);
            command.add("-Dmaven.ext.class.path=" + inputs.extensionJar);
            command.add("-Dlithe.modelOutput=" + model);
            // No lithe.dependenciesProject: every module's tree, in the same run.
            command.add("-Dlithe.dependenciesOutput=" + dependencies);
            command.add("pre-clean");
            Tail tail = new Tail(ERROR_LINES);
            Processes.Result result = Processes.run(command, new Processes.Options()
                    .directory(inputs.root)
                    .environment(inputs.environment)
                    .cancellation(cancellation)
                    .maxOutputBytes(64 * 1024)
                    .onStdout(tail)
                    .onStderr(tail));
            if (result.exitCode() != 0) {
                String output = tail.errors();
                backend.log("Maven exited with status " + result.exitCode() + ":\n" + output);
                throw new PluginError("maven.loadFailed", "Maven could not read the project (exit status "
                        + result.exitCode() + ")." + (output.isEmpty() ? "" : "\n" + output));
            }
            return new Loaded(document(model, "model"), document(dependencies, "dependency tree"));
        } finally {
            delete(work);
        }
    }

    private static Map<String, Object> document(Path file, String what) {
        try {
            Object value = Json.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            if (value instanceof Map) {
                return Json.object(value);
            }
        } catch (IOException | RuntimeException error) {
            throw new PluginError("maven.invalidOutput", "Maven finished without writing the " + what + ".", error);
        }
        throw new PluginError("maven.invalidOutput", "Maven wrote an unreadable " + what + ".");
    }

    private static void delete(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // A leftover file in the state directory is harmless.
        }
    }

    /** The last lines of Maven's output, for the error a failed run reports. */
    private static final class Tail implements Consumer<String> {
        private final int limit;
        private final Deque<String> lines = new ArrayDeque<>();
        private final StringBuilder partial = new StringBuilder();

        Tail(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void accept(String text) {
            partial.append(text);
            int end;
            while ((end = partial.indexOf("\n")) >= 0) {
                lines.addLast(partial.substring(0, end));
                partial.delete(0, end + 1);
                if (lines.size() > limit) {
                    lines.removeFirst();
                }
            }
        }

        /** {@code [ERROR]} lines when there are any, otherwise the whole tail. */
        synchronized String errors() {
            List<String> all = new ArrayList<>(lines);
            if (partial.length() > 0) {
                all.add(partial.toString());
            }
            List<String> errors = new ArrayList<>();
            for (String line : all) {
                if (line.startsWith("[ERROR]")) {
                    errors.add(line);
                }
            }
            String text = String.join("\n", errors.isEmpty() ? all : errors).trim();
            return text.length() > 4000 ? text.substring(text.length() - 4000) : text;
        }
    }
}
