package io.github.litheidea.maven.backend;

import io.github.litheidea.plugin.sdk.Json;
import io.github.litheidea.plugin.sdk.PluginError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Picks one project's subtree out of the workspace dependency document.
 *
 * <p>The extension lists every reactor project as a node with its
 * coordinates but without its path; the model has both. A selected path is
 * matched to a model project, and that project's coordinates to its node.
 */
final class Projects {
    private Projects() {}

    static List<Object> dependenciesOf(MavenRuns.Loaded loaded, Path workspace, String path) {
        Path wanted = workspace.resolve(path).toAbsolutePath().normalize();
        List<Object> projects = list(loaded.model().get("projects"));
        List<Object> nodes = list(loaded.dependencies().get("dependencies"));
        for (int index = 0; index < projects.size(); index++) {
            Map<String, Object> project = Json.object(projects.get(index));
            String projectPath = Json.string(project, "path");
            if (projectPath == null || !samePath(wanted, workspace.resolve(projectPath))) {
                continue;
            }
            Map<String, Object> properties = Json.object(project.get("properties"));
            for (Object candidate : nodes) {
                Map<String, Object> node = Json.object(candidate);
                if (Objects.equals(node.get("group"), properties.get("groupId"))
                        && Objects.equals(node.get("name"), properties.get("artifactId"))
                        && Objects.equals(node.get("version"), properties.get("version"))) {
                    return new ArrayList<>(list(node.get("children")));
                }
            }
            // Same order as the reactor, when coordinates are missing.
            if (nodes.size() == projects.size()
                    && Objects.equals(Json.object(nodes.get(index)).get("name"), project.get("name"))) {
                return new ArrayList<>(list(Json.object(nodes.get(index)).get("children")));
            }
        }
        throw new PluginError("maven.unknownProject", "Maven reports no project at " + path + ".");
    }

    private static boolean samePath(Path wanted, Path candidate) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.equals(wanted)) {
            return true;
        }
        try {
            return normalized.toRealPath().equals(wanted.toRealPath());
        } catch (IOException error) {
            return false;
        }
    }

    private static List<Object> list(Object value) {
        List<Object> result = new ArrayList<>();
        if (value instanceof List) {
            result.addAll((List<?>) value);
        }
        return result;
    }
}
