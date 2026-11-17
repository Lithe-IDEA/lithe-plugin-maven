package io.github.litheidea.maven.provider;

import io.github.litheidea.plugin.sdk.Backend;
import io.github.litheidea.plugin.sdk.PluginError;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * The Maven build provider process: Lithe starts it on the first run of a
 * Maven application and talks to it over stdio. It answers only
 * {@code lithe/build/prepareRun}; the model, dependencies, tasks and tests
 * stay commands Lithe runs itself.
 */
public final class RunProvider {
    private RunProvider() {}

    public static void main(String[] args) throws Exception {
        Path lib = Paths.get(RunProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Path extension = lib.resolve("lithe-maven-extension.jar");
        Backend backend = Backend.create("lithe-maven-provider", RunProvider.class.getPackage().getImplementationVersion());
        backend.onPrepareRun((params, context) -> preparation(backend, extension).prepare(params, context));
        backend.listen();
    }

    private static RunPreparation preparation(Backend backend, Path extension) {
        Map<String, Object> initialize = backend.initializeParams();
        Object root = initialize == null ? null : initialize.get("rootUri");
        if (!(root instanceof String)) {
            throw new PluginError("maven.run.noWorkspace", "Lithe sent no workspace folder.");
        }
        Object locale = initialize.get("locale");
        return new RunPreparation(Paths.get(URI.create((String) root)), extension, new Messages(locale instanceof String ? (String) locale : null));
    }
}
