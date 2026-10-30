package io.github.litheidea.maven;

import java.util.Collections;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.internal.LifecycleExecutionPlanCalculator;
import org.apache.maven.lifecycle.internal.LifecycleTask;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Reads configured compiler parameters from Maven's own execution plan.
 * No goal is executed and no POM interpretation is duplicated in the host.
 */
final class CompilerRequirements {
    private static final Logger LOG = LoggerFactory.getLogger(CompilerRequirements.class);

    static String minimumJava(MavenSession session, MavenProject project, LifecycleExecutionPlanCalculator plans) {
        if ("pom".equals(project.getPackaging())) return null;
        MavenSession selected = session.clone();
        selected.setCurrentProject(project);
        int minimum = 0;
        try {
            for (MojoExecution execution : plans.calculateExecutionPlan(selected, project,
                    Collections.<Object>singletonList(new LifecycleTask("test-compile")), true).getMojoExecutions()) {
                if (!"org.apache.maven.plugins".equals(execution.getGroupId())
                        || !"maven-compiler-plugin".equals(execution.getArtifactId())
                        || !("compile".equals(execution.getGoal()) || "testCompile".equals(execution.getGoal()))) continue;
                PluginParameterExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(selected, execution);
                boolean test = "testCompile".equals(execution.getGoal());
                String release = value(execution, evaluator, test ? "testRelease" : "release");
                if (test && release == null) release = value(execution, evaluator, "release");
                int required;
                if (release != null) required = feature(release);
                else {
                    String source = value(execution, evaluator, test ? "testSource" : "source");
                    String target = value(execution, evaluator, test ? "testTarget" : "target");
                    if (test && source == null) source = value(execution, evaluator, "source");
                    if (test && target == null) target = value(execution, evaluator, "target");
                    required = Math.max(source == null ? 0 : feature(source), target == null ? 0 : feature(target));
                }
                minimum = Math.max(minimum, required);
            }
            return minimum == 0 ? null : Integer.toString(minimum);
        } catch (Exception error) {
            // Unavailable plugin descriptors or unresolved expressions leave the
            // requirement unknown. They must never trigger a second POM parser.
            LOG.warn("Lithe could not determine Java compiler requirements for {}: {}", project.getArtifactId(), error.getMessage());
            return null;
        }
    }

    private static String value(MojoExecution execution, PluginParameterExpressionEvaluator evaluator, String name) throws Exception {
        Xpp3Dom configuration = execution.getConfiguration();
        Xpp3Dom parameter = configuration == null ? null : configuration.getChild(name);
        if (parameter == null) return null;
        Object value = evaluator.evaluate(parameter.getValue());
        if (value == null) value = evaluator.evaluate(parameter.getAttribute("default-value"));
        return value == null || value.toString().trim().isEmpty() ? null : value.toString().trim();
    }

    private static int feature(String version) {
        if (!version.matches("(?:1\\.)?[0-9]+")) throw new IllegalArgumentException("Unresolved compiler language version");
        int value = Integer.parseInt(version.startsWith("1.") ? version.substring(2) : version);
        if (value < 1 || value > 999) throw new IllegalArgumentException("Compiler language version is out of bounds");
        return value;
    }
}
