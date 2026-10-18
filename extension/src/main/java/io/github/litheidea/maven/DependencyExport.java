package io.github.litheidea.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@code litheDependencies} tree the way {@code dependency:tree}
 * shows it: Maven's own resolver collects the graph and mediates versions,
 * with the reactor supplying sibling modules that are not installed.
 *
 * <p>Only POMs are read. The resolution filter accepts no node, so no jar is
 * downloaded just to list the tree.
 */
final class DependencyExport {
    private static final Logger LOG = LoggerFactory.getLogger(DependencyExport.class);

    private final MavenSession session;
    private final ProjectDependenciesResolver resolver;

    DependencyExport(MavenSession session, ProjectDependenciesResolver resolver) {
        this.session = session;
        this.resolver = resolver;
    }

    /**
     * The direct dependencies of the project at {@code projectPath} (relative
     * to the directory Maven runs in), or every reactor project as a node of
     * its own when the path is null.
     */
    Map<String, Object> document(String projectPath) throws MavenExecutionException {
        List<Object> dependencies = new ArrayList<>();
        if (projectPath == null) {
            for (MavenProject project : session.getProjects()) {
                Map<String, Object> node = new LinkedHashMap<>();
                node.put("name", project.getArtifactId());
                node.put("group", project.getGroupId());
                node.put("version", project.getVersion());
                Map<String, Object> properties = new LinkedHashMap<>();
                properties.put("kind", "project");
                properties.put("packaging", project.getPackaging());
                node.put("properties", properties);
                node.put("children", children(graph(project)));
                dependencies.add(node);
            }
        } else {
            dependencies.addAll(children(graph(project(projectPath))));
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "litheDependencies");
        document.put("version", 1);
        document.put("dependencies", dependencies);
        return document;
    }

    private MavenProject project(String projectPath) throws MavenExecutionException {
        String wanted = ModelExport.path(new File(session.getExecutionRootDirectory(), projectPath));
        for (MavenProject project : session.getProjects()) {
            if (ModelExport.path(project.getBasedir()).equals(wanted)) {
                return project;
            }
        }
        throw new MavenExecutionException(
                "Lithe found no project at " + projectPath + " in this reactor.", (File) null);
    }

    private DependencyNode graph(MavenProject project) throws MavenExecutionException {
        DefaultDependencyResolutionRequest request =
                new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
        request.setResolutionFilter((node, parents) -> false);
        try {
            return resolver.resolve(request).getDependencyGraph();
        } catch (DependencyResolutionException error) {
            // A missing artifact leaves the rest of the tree usable; Maven
            // has already reported which one.
            DependencyResolutionResult result = error.getResult();
            if (result == null || result.getDependencyGraph() == null) {
                throw new MavenExecutionException(
                        "Lithe could not collect the dependencies of " + project.getId() + ": " + error.getMessage(),
                        error);
            }
            LOG.warn("Lithe listed the dependencies of {} partially: {}", project.getId(), error.getMessage());
            return result.getDependencyGraph();
        }
    }

    private static List<Object> children(DependencyNode node) {
        if (node == null) {
            return Collections.emptyList();
        }
        List<Object> result = new ArrayList<>();
        for (DependencyNode child : node.getChildren()) {
            Dependency dependency = child.getDependency();
            if (dependency == null) {
                continue;
            }
            Artifact artifact = dependency.getArtifact();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", artifact.getArtifactId());
            entry.put("group", artifact.getGroupId());
            entry.put("version", artifact.getBaseVersion());
            if (dependency.getScope() != null && !dependency.getScope().isEmpty()) {
                entry.put("scope", dependency.getScope());
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("type", artifact.getProperty("type", artifact.getExtension()));
            if (!artifact.getClassifier().isEmpty()) {
                properties.put("classifier", artifact.getClassifier());
            }
            if (dependency.isOptional()) {
                properties.put("optional", "true");
            }
            entry.put("properties", properties);
            entry.put("children", children(child));
            result.add(entry);
        }
        return result;
    }
}
