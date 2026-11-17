package io.github.litheidea.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.apache.maven.project.ProjectDependenciesResolver;

/**
 * Writes what Lithe asked for once Maven has read the reactor, before any
 * goal runs. Lithe's commands pass {@code pre-clean}, a phase nothing is
 * bound to by default, so the build that follows does no work. Run
 * preparation is the exception: it compiles, and its document is written
 * after the build, in {@link #afterSessionEnd}.
 *
 * <p>Without the properties below the extension does nothing, so loading it
 * into an ordinary build changes nothing.
 */
@Named("lithe")
@Singleton
public final class LitheLifecycleParticipant extends AbstractMavenLifecycleParticipant {
    /** Where the {@code litheBuildModel} document goes. */
    static final String MODEL_OUTPUT = "lithe.modelOutput";
    /** Where the {@code litheDependencies} document goes. */
    static final String DEPENDENCIES_OUTPUT = "lithe.dependenciesOutput";
    /** The project whose dependencies are listed; every project without it. */
    static final String DEPENDENCIES_PROJECT = "lithe.dependenciesProject";
    /** Where the run document of {@link #RUN_PROJECT} goes once the build is done. */
    static final String RUN_OUTPUT = "lithe.runOutput";
    /** The project run preparation compiled, relative to the directory Maven runs in. */
    static final String RUN_PROJECT = "lithe.runProject";

    private final MavenPluginManager pluginManager;
    private final PluginVersionResolver versionResolver;
    private final ProjectDependenciesResolver dependenciesResolver;

    @Inject
    public LitheLifecycleParticipant(
            MavenPluginManager pluginManager,
            PluginVersionResolver versionResolver,
            ProjectDependenciesResolver dependenciesResolver) {
        this.pluginManager = pluginManager;
        this.versionResolver = versionResolver;
        this.dependenciesResolver = dependenciesResolver;
    }

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String modelOutput = property(session, MODEL_OUTPUT);
        if (modelOutput != null) {
            write(modelOutput, Json.write(new ModelExport(session, pluginManager, versionResolver).document()));
        }
        String dependenciesOutput = property(session, DEPENDENCIES_OUTPUT);
        if (dependenciesOutput != null) {
            String project = property(session, DEPENDENCIES_PROJECT);
            write(dependenciesOutput,
                    Json.write(new DependencyExport(session, dependenciesResolver).document(project)));
        }
    }

    /**
     * Writes the run document after the goals have run, so the classes it
     * lists exist. A failed build writes nothing: Maven's own output says
     * why, and run preparation reports the missing document.
     */
    @Override
    public void afterSessionEnd(MavenSession session) throws MavenExecutionException {
        String runOutput = property(session, RUN_OUTPUT);
        String runProject = property(session, RUN_PROJECT);
        if (runOutput == null || runProject == null || session.getResult().hasExceptions()) {
            return;
        }
        write(runOutput, Json.write(new RunExport(session, dependenciesResolver).document(runProject)));
    }

    private static String property(MavenSession session, String key) {
        String value = session.getUserProperties().getProperty(key);
        if (value == null) {
            value = session.getSystemProperties().getProperty(key);
        }
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static void write(String output, String json) throws MavenExecutionException {
        Path path = Paths.get(output);
        try {
            Files.write(path, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new MavenExecutionException("Lithe could not write " + path + ": " + error.getMessage(), error);
        }
    }
}
