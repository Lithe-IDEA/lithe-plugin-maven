package io.github.litheidea.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.version.DefaultPluginVersionRequest;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the {@code litheBuildModel} document from the reactor Maven has
 * just read: every project with its coordinates and source folders, the
 * goals of every build plugin as model tasks, and the profile IDs the
 * Profiles option offers.
 *
 * <p>Paths are absolute; Lithe makes them relative to the build root and
 * drops any outside the workspace.
 */
final class ModelExport {
    /** The fromModel task in plugin.json that runs a plugin goal. */
    static final String GOAL_TEMPLATE = "plugin-goal";

    private static final Logger LOG = LoggerFactory.getLogger(ModelExport.class);

    private final MavenSession session;
    private final MavenPluginManager pluginManager;
    private final LifecycleExecutionPlanCalculator executionPlans;
    private final PluginVersionResolver versionResolver;
    /** Goals by plugin coordinates, so each descriptor is read once per run. */
    private final Map<String, Goals> goalsByPlugin = new LinkedHashMap<>();

    ModelExport(MavenSession session, MavenPluginManager pluginManager, PluginVersionResolver versionResolver, LifecycleExecutionPlanCalculator executionPlans) {
        this.session = session;
        this.executionPlans = executionPlans;
        this.pluginManager = pluginManager;
        this.versionResolver = versionResolver;
    }

    Map<String, Object> document() {
        List<Object> projects = new ArrayList<>();
        List<Object> tasks = new ArrayList<>();
        List<Object> contentRoots = new ArrayList<>();
        List<Object> targets = new ArrayList<>();
        Map<String, File> aggregators = aggregators();
        Map<String, Object> wrapper = new LinkedHashMap<>();
        WrapperRequirements.add(session, wrapper);
        for (MavenProject project : session.getProjects()) {
            String path = path(project.getBasedir());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("name", project.getArtifactId());
            String name = project.getName();
            if (name != null && !name.trim().isEmpty() && !name.equals(project.getArtifactId())) {
                entry.put("displayName", name.trim());
            }
            File aggregator = aggregators.get(path);
            if (aggregator != null) {
                entry.put("parent", path(aggregator));
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("groupId", project.getGroupId());
            properties.put("artifactId", project.getArtifactId());
            properties.put("version", project.getVersion());
            properties.put("packaging", project.getPackaging());
            properties.put("run.modelVersion", "1");
            properties.putAll(wrapper);
            String minimumJava = CompilerRequirements.minimumJava(session, project, executionPlans);
            if (minimumJava != null) properties.put("java.minimumVersion", minimumJava);
            FrameworkLaunch framework = FrameworkLaunch.find(project);
            if (framework != null) {
                properties.put("run.provider", framework.provider);
                properties.put("run.goal", goals(project, framework.plugin).coordinates + ":" + framework.goal);
                properties.put("run.runtimeArgumentsProperty", framework.runtimeProperty);
                properties.put("run.argumentsProperty", framework.argumentsProperty);
                if (framework.entrypointProperty != null) properties.put("run.entrypointProperty", framework.entrypointProperty);
                properties.put("run.preparationVersion", "1");
                if ("spring-boot.maven".equals(framework.provider)) properties.put("run.workingDirectoryParameter", "workingDirectory");
                addTarget(targets, path, framework.provider, "maven-service", properties);
            }
            addTarget(targets, path, "maven.module", "maven-module", properties);
            entry.put("properties", properties);
            projects.add(entry);
            if (!"pom".equals(project.getPackaging())) {
                addContentRoots(project, path, contentRoots);
            }
            addGoals(project, path, tasks);
        }
        Map<String, Object> optionValues = new LinkedHashMap<>();
        optionValues.put("profiles", new ArrayList<>(profileIds()));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "litheBuildModel");
        document.put("version", 1);
        document.put("projects", projects);
        document.put("tasks", tasks);
        document.put("targets", targets);
        document.put("contentRoots", contentRoots);
        document.put("optionValues", optionValues);
        return document;
    }

    /**
     * The aggregator that lists each project as a module, keyed by the
     * module's directory. Maven's parent is the inheritance parent, which
     * need not be the project that builds the module, and the tree follows
     * the {@code <modules>} sections like the Maven tool window in IntelliJ.
     */
    private Map<String, File> aggregators() {
        Map<String, File> result = new LinkedHashMap<>();
        for (MavenProject project : session.getProjects()) {
            for (String module : project.getModules()) {
                File directory = new File(project.getBasedir(), module);
                if (directory.isFile()) {
                    directory = directory.getParentFile();
                }
                result.put(path(directory), project.getBasedir());
            }
        }
        return result;
    }

    /** Effective build plugins include active profiles and inherited plugins.
     * Plugin management alone does not apply a plugin. Coordinate precedence
     * preserves the host's existing service identity if several are applied.
     */
    private static void addTarget(List<Object> targets, String project, String name, String tag, Map<String, Object> properties) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("project", project); target.put("path", project); target.put("name", name);
        target.put("tags", Collections.singletonList(tag)); target.put("properties", new LinkedHashMap<>(properties));
        targets.add(target);
    }

    private void addContentRoots(MavenProject project, String owner, List<Object> roots) {
        File basedir = project.getBasedir();
        for (String root : project.getCompileSourceRoots()) {
            addRoot(roots, owner, resolve(basedir, root), "source");
        }
        for (Resource resource : project.getResources()) {
            addRoot(roots, owner, resolve(basedir, resource.getDirectory()), "resource");
        }
        for (String root : project.getTestCompileSourceRoots()) {
            addRoot(roots, owner, resolve(basedir, root), "testSource");
        }
        for (Resource resource : project.getTestResources()) {
            addRoot(roots, owner, resolve(basedir, resource.getDirectory()), "testResource");
        }
        File build = resolve(basedir, project.getBuild().getDirectory());
        if (build == null) {
            return;
        }
        // The compiler writes annotation-processor output here by default;
        // other generators add their own folders beside it.
        addGenerated(roots, owner, new File(build, "generated-sources"), "annotations", "generatedSource");
        addGenerated(roots, owner, new File(build, "generated-test-sources"), "test-annotations", "generatedTestSource");
    }

    private static void addGenerated(List<Object> roots, String owner, File parent, String standard, String kind) {
        Set<File> folders = new TreeSet<>();
        folders.add(new File(parent, standard));
        File[] children = parent.listFiles(File::isDirectory);
        if (children != null) {
            Collections.addAll(folders, children);
        }
        for (File folder : folders) {
            addRoot(roots, owner, folder, kind);
        }
    }

    private static void addRoot(List<Object> roots, String owner, File path, String kind) {
        if (path == null) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("project", owner);
        root.put("path", path(path));
        root.put("kind", kind);
        roots.add(root);
    }

    private void addGoals(MavenProject project, String owner, List<Object> tasks) {
        for (Plugin plugin : project.getBuildPlugins()) {
            Goals goals = goals(project, plugin);
            for (String goal : goals.names) {
                Map<String, Object> task = new LinkedHashMap<>();
                task.put("project", owner);
                task.put("name", goals.prefix + ":" + goal);
                task.put("template", GOAL_TEMPLATE);
                task.put("description", goals.coordinates);
                List<String> category = new ArrayList<>();
                category.add("Plugins");
                category.add(goals.prefix);
                task.put("category", category);
                Map<String, Object> properties = new LinkedHashMap<>();
                // The fully qualified goal runs the version the model shows,
                // whatever plugin groups the user's settings list.
                properties.put("goal", goals.coordinates + ":" + goal);
                task.put("properties", properties);
                tasks.add(task);
            }
        }
    }

    /**
     * The goal prefix and goals of a plugin. The descriptor needs the plugin
     * jar, which Maven downloads like any build would; offline or without
     * it, the plugin still lists the goals its executions name.
     */
    private Goals goals(MavenProject project, Plugin declared) {
        Plugin plugin = declared.clone();
        if (plugin.getVersion() == null) {
            try {
                plugin.setVersion(versionResolver.resolve(new DefaultPluginVersionRequest(plugin, session)).getVersion());
            } catch (Exception error) {
                LOG.warn("Lithe could not resolve the version of {}: {}", plugin.getKey(), error.getMessage());
            }
        }
        String coordinates = plugin.getGroupId() + ":" + plugin.getArtifactId()
                + (plugin.getVersion() == null ? "" : ":" + plugin.getVersion());
        Goals cached = goalsByPlugin.get(coordinates);
        if (cached != null) {
            return cached;
        }
        Goals goals = null;
        if (plugin.getVersion() != null) {
            try {
                PluginDescriptor descriptor = pluginManager.getPluginDescriptor(
                        plugin, project.getRemotePluginRepositories(), session.getRepositorySession());
                Set<String> names = new TreeSet<>();
                for (MojoDescriptor mojo : descriptor.getMojos()) {
                    names.add(mojo.getGoal());
                }
                goals = new Goals(descriptor.getGoalPrefix(), coordinates, names);
            } catch (Exception error) {
                LOG.warn("Lithe could not read the goals of {}: {}", coordinates, error.getMessage());
            }
        }
        if (goals == null) {
            Set<String> names = new LinkedHashSet<>();
            for (PluginExecution execution : plugin.getExecutions()) {
                names.addAll(execution.getGoals());
            }
            goals = new Goals(PluginDescriptor.getGoalPrefixFromArtifactId(plugin.getArtifactId()), coordinates, names);
        }
        goalsByPlugin.put(coordinates, goals);
        return goals;
    }

    /** Profiles of the reactor, of every parent POM above it, and of settings.xml. */
    private Set<String> profileIds() {
        Set<String> ids = new TreeSet<>();
        for (MavenProject project : session.getProjects()) {
            for (MavenProject current = project; current != null; current = current.getParent()) {
                if (current.getOriginalModel() != null) {
                    for (Profile profile : current.getOriginalModel().getProfiles()) {
                        addProfile(ids, profile.getId());
                    }
                }
            }
        }
        if (session.getSettings() != null) {
            for (org.apache.maven.settings.Profile profile : session.getSettings().getProfiles()) {
                addProfile(ids, profile.getId());
            }
        }
        return ids;
    }

    /** A comma would split the ID in {@code -P}, so such a profile is left out. */
    private static void addProfile(Set<String> ids, String id) {
        if (id != null && !id.trim().isEmpty() && id.indexOf(',') < 0) {
            ids.add(id.trim());
        }
    }

    private static File resolve(File basedir, String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File file = new File(path);
        return file.isAbsolute() ? file : new File(basedir, path);
    }

    static String path(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    private static final class Goals {
        final String prefix;
        final String coordinates;
        final Set<String> names;

        Goals(String prefix, String coordinates, Set<String> names) {
            this.prefix = prefix;
            this.coordinates = coordinates;
            this.names = names;
        }
    }
}
