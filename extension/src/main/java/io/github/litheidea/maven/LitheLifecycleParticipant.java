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
 * bound to by default, so the build that follows does no work.
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
