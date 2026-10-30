package io.github.litheidea.maven;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.Parameter;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.ProjectExecutionEvent;
import org.apache.maven.execution.ProjectExecutionListener;
import org.apache.maven.lifecycle.LifecycleExecutionException;
import org.apache.maven.lifecycle.internal.GoalTask;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.lifecycle.internal.MojoExecutor;
import org.apache.maven.lifecycle.internal.ProjectIndex;

/** Maven builds the selected reactor first, then executes a framework only in its owner project. */
@Named
@Singleton
public final class RunExecutionEvents implements ProjectExecutionListener {
    private final LifecycleExecutionPlanCalculator plans;
    private final MojoExecutor executor;
    private final AtomicBoolean launched = new AtomicBoolean();

    @Inject
    public RunExecutionEvents(LifecycleExecutionPlanCalculator plans, MojoExecutor executor) {
        this.plans = plans; this.executor = executor;
    }

    @Override public void beforeProjectExecution(ProjectExecutionEvent event) {}
    @Override public void beforeProjectLifecycleExecution(ProjectExecutionEvent event) {}
    @Override public void afterProjectExecutionFailure(ProjectExecutionEvent event) {}

    @Override public void afterProjectExecutionSuccess(ProjectExecutionEvent event) throws LifecycleExecutionException {
        String owner = event.getSession().getUserProperties().getProperty("lithe.run.project");
        String goal = event.getSession().getUserProperties().getProperty("lithe.run.goal");
        if (owner == null || goal == null) return;
        try {
            if (!event.getProject().getBasedir().getCanonicalFile().equals(new File(owner).getCanonicalFile())) return;
            FrameworkLaunch framework = FrameworkLaunch.find(event.getProject());
            if (framework == null || !framework.accepts(goal)) throw new IllegalArgumentException("The framework goal is absent from the current Maven project.");
            if (!launched.compareAndSet(false, true)) return;
            // Maven retains its reactor workspace reader, dependency scopes, lifecycle
            // forks and plugin configuration. No second classpath or artifact model.
            List<MojoExecution> executions = plans.calculateExecutionPlan(event.getSession(), event.getProject(),
                    Collections.<Object>singletonList(new GoalTask(goal)), true).getMojoExecutions();
            for (MojoExecution execution : executions) {
                if (goal.endsWith(":" + execution.getGoal()) && framework.plugin.getArtifactId().equals(execution.getArtifactId())) {
                    configure(execution, framework.runtimeProperty, arguments(event, "runtime"));
                    configure(execution, framework.argumentsProperty, arguments(event, "arguments"));
                    String cwd = event.getSession().getUserProperties().getProperty("lithe.run.workingDirectory");
                    if (cwd != null) {
                        if (!"spring-boot.maven".equals(framework.provider) || execution.getMojoDescriptor().getParameters().stream()
                                .noneMatch(parameter -> "workingDirectory".equals(parameter.getName()) && "java.io.File".equals(parameter.getType()))) {
                            throw new IllegalArgumentException("The framework has no application working directory parameter.");
                        }
                        Xpp3Dom directory = execution.getConfiguration().getChild("workingDirectory");
                        if (directory == null) { directory = new Xpp3Dom("workingDirectory"); execution.getConfiguration().addChild(directory); }
                        directory.setValue(cwd);
                    }
                }
            }
            executor.execute(event.getSession(), executions, new ProjectIndex(event.getSession().getProjects()));
        } catch (Exception error) {
            if (error instanceof LifecycleExecutionException) throw (LifecycleExecutionException) error;
            throw new LifecycleExecutionException("Lithe could not prepare the current Maven framework goal.", null, event.getProject(), error);
        }
    }
    private static List<String> arguments(ProjectExecutionEvent event, String kind) {
        Properties properties = event.getSession().getUserProperties();
        int count = Integer.parseInt(properties.getProperty("lithe.run." + kind + ".count", "0"));
        if (count < 0 || count > 1024) throw new IllegalArgumentException("Invalid run argument count.");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String value = properties.getProperty("lithe.run." + kind + "." + index);
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("A framework cannot preserve empty run arguments.");
            values.add(value);
        }
        return values;
    }

    /** Maven's descriptor selects string versus collection conversion for each plugin version. */
    private static void configure(MojoExecution execution, String property, List<String> values) throws Exception {
        if (values.isEmpty()) return;
        Parameter selected = null;
        for (Parameter parameter : execution.getMojoDescriptor().getParameters()) {
            if (("${" + property + "}").equals(parameter.getExpression())) selected = parameter;
        }
        if (selected == null) throw new IllegalArgumentException("The framework has no parameter for " + property);
        Xpp3Dom configuration = execution.getConfiguration();
        Xpp3Dom value = configuration.getChild(selected.getName());
        if (value == null) { value = new Xpp3Dom(selected.getName()); configuration.addChild(value); }
        while (value.getChildCount() > 0) value.removeChild(0);
        if ("java.lang.String[]".equals(selected.getType()) || "java.util.List".equals(selected.getType())) {
            value.setValue(null);
            for (String argument : values) { Xpp3Dom child = new Xpp3Dom("argument"); child.setValue(argument); value.addChild(child); }
        } else if ("java.lang.String".equals(selected.getType())) {
            String encoded = CommandLineUtils.toString(values.toArray(new String[0]));
            if (!Arrays.equals(values.toArray(new String[0]), CommandLineUtils.translateCommandline(encoded))
                    || values.stream().anyMatch(argument -> argument.indexOf('\n') >= 0 || argument.indexOf('\t') >= 0 || argument.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("The framework string parameter cannot preserve the requested argument boundaries.");
            }
            value.setValue(encoded);
        } else { throw new IllegalArgumentException("Unsupported framework run parameter type: " + selected.getType()); }
    }

}
