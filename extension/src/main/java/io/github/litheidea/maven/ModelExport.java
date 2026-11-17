package io.github.litheidea.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
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
 * goals of every build plugin as model tasks, the applications Lithe offers
 * as run configurations (see {@link Applications}) as model targets, and the
 * profile IDs the Profiles option offers.
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
    private final PluginVersionResolver versionResolver;
    /** Goals by plugin coordinates, so each descriptor is read once per run. */
    private final Map<String, Goals> goalsByPlugin = new LinkedHashMap<>();

    ModelExport(MavenSession session, MavenPluginManager pluginManager, PluginVersionResolver versionResolver) {
        this.session = session;
        this.pluginManager = pluginManager;
        this.versionResolver = versionResolver;
    }

    Map<String, Object> document() {
        List<Object> projects = new ArrayList<>();
        List<Object> tasks = new ArrayList<>();
        List<Object> contentRoots = new ArrayList<>();
        List<Object> targets = new ArrayList<>();
        Map<String, File> aggregators = aggregators();
        for (MavenProject project : session.getProjects()) {
            String path = path(project.getBasedir());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", path);
            entry.put("name", project.getArtifactId());
            String name = project.getName();
            if (name != null && !name.trim().isEmpty() && !name.equals(project.getArtifactId())) {
                entry.put("displayName", name.trim());
            }
            File aggregator = aggregators.get(key(project.getBasedir()));
            if (aggregator != null) {
                entry.put("parent", path(aggregator));
            }
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("groupId", project.getGroupId());
            properties.put("artifactId", project.getArtifactId());
            properties.put("version", project.getVersion());
            properties.put("packaging", project.getPackaging());
            compilerFacts(project, properties);
            if(project.getParent()!=null){
                Map<String,Object> inherited=new LinkedHashMap<>();compilerFacts(project.getParent(),inherited);
                for(String key:new String[]{"target","releaseEnabled","debug","showDeprecation","showWarnings","arguments"})if(inherited.containsKey("compiler."+key))properties.put("compiler.inherited."+key,inherited.get("compiler."+key));
            }
            entry.put("properties", properties);
            projects.add(entry);
            if (!"pom".equals(project.getPackaging())) {
                addContentRoots(project, path, contentRoots);
            }
            for (Applications.Application application : Applications.of(project)) {
                targets.add(application.target(path));
            }
            addGoals(project, path, tasks);
        }
        Map<String, Object> optionValues = new LinkedHashMap<>();
        optionValues.put("profiles", new ArrayList<>(profileIds()));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "litheBuildModel");
        document.put("version", 0);
        document.put("projects", projects);
        document.put("targets", targets);
        document.put("tasks", tasks);
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
                result.put(key(directory), project.getBasedir());
            }
        }
        return result;
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

    /** Query Maven's merged plugin model and native parameter evaluator. No
     * module/configuration model is inferred by the desktop or the backend. */
    private void compilerFacts(MavenProject project, Map<String,Object> output) {
        Plugin compiler=null;
        for(Plugin candidate:project.getBuildPlugins()) if("maven-compiler-plugin".equals(candidate.getArtifactId())) {compiler=candidate.clone();break;}
        if(compiler==null) return;
        try {
            if(compiler.getVersion()==null) compiler.setVersion(versionResolver.resolve(new DefaultPluginVersionRequest(compiler,session)).getVersion());
            PluginDescriptor descriptor=pluginManager.getPluginDescriptor(compiler,project.getRemotePluginRepositories(),session.getRepositorySession());
            MojoDescriptor mojo=descriptor.getMojo("compile");
            org.apache.maven.plugin.MojoExecution execution=new org.apache.maven.plugin.MojoExecution(mojo,"default-compile");
            org.codehaus.plexus.util.xml.Xpp3Dom merged=project.getGoalConfiguration("org.apache.maven.plugins","maven-compiler-plugin","default-compile","compile");
            execution.setConfiguration(merged);
            org.apache.maven.plugin.PluginParameterExpressionEvaluator evaluator=new org.apache.maven.plugin.PluginParameterExpressionEvaluator(session,execution);
            for(String key:new String[]{"source","target","release","debug","showDeprecation","showWarnings"}) {
                org.codehaus.plexus.util.xml.Xpp3Dom node=merged==null?null:merged.getChild(key);
                Object value=node==null?null:evaluator.evaluate(node.getValue());
                if(value==null) value=project.getProperties().getProperty("maven.compiler."+key);
                if(value==null && mojo.getMojoConfiguration()!=null) {
                    org.codehaus.plexus.configuration.PlexusConfiguration defaults=mojo.getMojoConfiguration().getChild(key,false);
                    if(defaults!=null) {
                        String expression=defaults.getValue(null);
                        if(expression!=null) value=evaluator.evaluate(expression);
                        if(value==null || value.toString().contains("${")) value=defaults.getAttribute("default-value",null);
                    }
                }
                if(value!=null&&!value.toString().contains("${")) output.put("compiler."+key,value.toString());
            }
            List<String> arguments=new ArrayList<>();
            org.codehaus.plexus.util.xml.Xpp3Dom args=merged==null?null:merged.getChild("compilerArgs");
            if(args!=null) for(org.codehaus.plexus.util.xml.Xpp3Dom arg:args.getChildren()) {
                Object evaluated=evaluator.evaluate(arg.getValue());
                if(evaluated!=null) arguments.add(evaluated.toString());
            }
            output.put("compiler.arguments",Json.write(arguments));
            Object release=output.get("compiler.release");
            output.put("compiler.releaseEnabled",Boolean.toString(release!=null&&!release.toString().isEmpty()));
            if(release!=null&&!release.toString().isEmpty()) output.put("compiler.target",release.toString());
        } catch(Exception failure) {
            throw new IllegalStateException("Maven compiler settings could not be evaluated.",failure);
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

    /**
     * The reactor project at projectPath, relative to the directory Maven
     * runs in.
     */
    static MavenProject project(MavenSession session, String projectPath) throws MavenExecutionException {
        String wanted = key(new File(session.getExecutionRootDirectory(), projectPath));
        for (MavenProject project : session.getProjects()) {
            if (key(project.getBasedir()).equals(wanted)) {
                return project;
            }
        }
        throw new MavenExecutionException(
                "Lithe found no project at " + projectPath + " in this reactor.", (File) null);
    }

    static String path(File file) {
        return file.toPath().toAbsolutePath().normalize().toString();
    }

    /**
     * Identifies a directory for comparison. On Windows Maven canonicalizes
     * module directories but not the directory it runs in, so the same folder
     * can differ in drive-letter case, component case, or 8.3 short names.
     */
    static String key(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException error) {
            return path(file);
        }
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
