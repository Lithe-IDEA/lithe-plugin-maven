package io.github.litheidea.maven;

import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/** Framework invocation metadata comes only from Maven's effective applied plugins. */
final class FrameworkLaunch {
    final Plugin plugin;
    final String provider, goal, runtimeProperty, argumentsProperty, entrypointProperty;

    private FrameworkLaunch(Plugin plugin, String[] kind) {
        this.plugin = plugin; provider = kind[2]; goal = kind[3]; runtimeProperty = kind[4];
        argumentsProperty = kind[5]; entrypointProperty = kind.length > 6 ? kind[6] : null;
    }

    static FrameworkLaunch find(MavenProject project) {
        if ("pom".equals(project.getPackaging())) return null;
        String[][] kinds = {
            {"org.springframework.boot", "spring-boot-maven-plugin", "spring-boot.maven", "run", "spring-boot.run.jvmArguments", "spring-boot.run.arguments", "spring-boot.run.main-class"},
            {"io.quarkus", "quarkus-maven-plugin", "quarkus.maven", "dev", "jvm.args", "quarkus.args"},
            {"io.quarkus.platform", "quarkus-maven-plugin", "quarkus.maven", "dev", "jvm.args", "quarkus.args"},
            {"io.micronaut.build", "micronaut-maven-plugin", "micronaut.maven", "run", "mn.jvmArgs", "mn.appArgs"},
            {"io.micronaut.maven", "micronaut-maven-plugin", "micronaut.maven", "run", "mn.jvmArgs", "mn.appArgs"}
        };
        for (String[] kind : kinds) for (Plugin plugin : project.getBuildPlugins()) {
            if (kind[0].equals(plugin.getGroupId()) && kind[1].equals(plugin.getArtifactId())) return new FrameworkLaunch(plugin, kind);
        }
        return null;
    }

    boolean accepts(String qualifiedGoal) {
        String coordinates = plugin.getGroupId() + ":" + plugin.getArtifactId();
        return (coordinates + ":" + goal).equals(qualifiedGoal)
                || plugin.getVersion() != null && (coordinates + ":" + plugin.getVersion() + ":" + goal).equals(qualifiedGoal);
    }
}
