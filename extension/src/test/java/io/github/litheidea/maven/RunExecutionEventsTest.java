package io.github.litheidea.maven;

import static org.junit.Assert.*;
import java.util.*;
import java.io.StringReader;
import org.junit.Test;
import org.apache.maven.model.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.*;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.*;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.cli.CommandLineUtils;

/** Descriptor-bound overrides protect framework versions with incompatible debug parameters. */
public class RunExecutionEventsTest {
    @Test(timeout = 5000) public void springPreservesConfiguredArgumentsBeforeAddingOneSuspendedLoopbackAgent() throws Exception {
        FrameworkLaunch framework = framework("org.springframework.boot", "spring-boot-maven-plugin");
        MojoExecution execution = execution(parameter("jvmArguments", "java.lang.String", framework.runtimeProperty));
        set(execution, "jvmArguments", "-Dmessage=\"hello world\" -Dkept=true");
        configureDebug(execution, framework, "51235");
        assertArrayEquals(new String[] {"-Dmessage=hello world", "-Dkept=true", "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:51235"},
            CommandLineUtils.translateCommandline(value(execution, "jvmArguments")));
        assertThrows(IllegalArgumentException.class, () -> configureDebug(execution, framework, "51235"));
    }

    @Test(timeout = 5000) public void quarkusOverridesClientModeAndRemoteBindWithOneManagedAgent() throws Exception {
        MojoExecution execution = execution(parameter("jvmArgs", "java.lang.String", "jvm.args"), parameter("debug", "java.lang.String", null), parameter("suspend", "java.lang.String", null), parameter("debugHost", "java.lang.String", null));
        set(execution, "debug", "client"); set(execution, "debugHost", "0.0.0.0");
        configureDebug(execution, framework("io.quarkus.platform", "quarkus-maven-plugin"), "51235");
        assertEquals("51235", value(execution, "debug")); assertEquals("y", value(execution, "suspend"));
        assertEquals("127.0.0.1", value(execution, "debugHost"));
    }

    @Test(timeout = 5000) public void quarkusDefaultValueBindingsAreReadThroughMavensDescriptorBuilder() throws Exception {
        PluginDescriptor plugin = new PluginDescriptorBuilder().build(new StringReader(
            "<plugin><groupId>io.quarkus.platform</groupId><artifactId>quarkus-maven-plugin</artifactId>"
            + "<version>3.15.5</version><goalPrefix>quarkus</goalPrefix><mojos><mojo><goal>dev</goal><parameters>"
            + "<parameter><name>jvmArgs</name><type>java.lang.String</type></parameter>"
            + "<parameter><name>debug</name><type>java.lang.String</type></parameter>"
            + "<parameter><name>suspend</name><type>java.lang.String</type></parameter>"
            + "<parameter><name>debugHost</name><type>java.lang.String</type></parameter>"
            + "</parameters><configuration><jvmArgs implementation=\"java.lang.String\" default-value=\"${jvm.args}\"/>"
            + "</configuration></mojo></mojos></plugin>"));
        MojoExecution execution = new MojoExecution(new Plugin(), "dev", "default-cli");
        execution.setMojoDescriptor(plugin.getMojo("dev")); execution.setConfiguration(new Xpp3Dom("configuration"));
        assertNull(execution.getMojoDescriptor().getParameters().get(0).getExpression());
        configureDebug(execution, framework("io.quarkus.platform", "quarkus-maven-plugin"), "51235");
        assertEquals("51235", value(execution, "debug")); assertEquals("y", value(execution, "suspend"));
    }

    @Test(timeout = 5000) public void micronautUsesItsBooleanAndIntegerParameters() throws Exception {
        MojoExecution execution = execution(parameter("jvmArgs", "java.lang.String", "mn.jvmArgs"), parameter("debug", "boolean", null), parameter("debugSuspend", "boolean", null), parameter("debugPort", "int", null), parameter("debugHost", "java.lang.String", null));
        configureDebug(execution, framework("io.micronaut.maven", "micronaut-maven-plugin"), "51235");
        assertEquals("true", value(execution, "debug")); assertEquals("true", value(execution, "debugSuspend"));
        assertEquals("51235", value(execution, "debugPort")); assertEquals("127.0.0.1", value(execution, "debugHost"));
    }

    @Test(timeout = 5000) public void missingOrIncompatibleHostParameterRefusesDebugInsteadOfLaunchingUnconfigured() throws Exception {
        for (String type : new String[] {null, "boolean"}) {
            MojoExecution execution = execution(parameter("jvmArgs", "java.lang.String", "jvm.args"), parameter("debug", "java.lang.String", null), parameter("suspend", "java.lang.String", null));
            if (type != null) execution.getMojoDescriptor().getParameters().add(parameter("debugHost", type, null));
            assertThrows(IllegalArgumentException.class, () -> configureDebug(execution, framework("io.quarkus", "quarkus-maven-plugin"), "51235"));
        }
    }

    @Test(timeout = 5000) public void mavenEvaluatesConfiguredAndDefaultArgumentsBeforeDuplicateAgentChecks() throws Exception {
        for (boolean defaultValue : new boolean[] { false, true }) {
            MojoExecution execution = execution(parameter("jvmArguments", "java.lang.String", "spring-boot.run.jvmArguments"));
            set(execution, "jvmArguments", "${fixture.jvmArgs}");
            if (defaultValue) {
                execution.getConfiguration().getChild("jvmArguments").setValue(null);
                execution.getConfiguration().getChild("jvmArguments").setAttribute("default-value", "${fixture.jvmArgs}");
            }
            MavenSession session = session();
            session.getUserProperties().setProperty("fixture.jvmArgs", "-Dkept=true");
            FrameworkLaunch framework = framework("org.springframework.boot", "spring-boot-maven-plugin");
            RunExecutionEvents.configureDebug(execution, framework, "51235", session);
            assertTrue(value(execution, "jvmArguments").contains("-Dkept=true"));
            set(execution, "jvmArguments", "${fixture.jvmArgs}");
            if (defaultValue) execution.getConfiguration().getChild("jvmArguments").setValue(null);
            session.getUserProperties().setProperty("fixture.jvmArgs", "-agentlib:jdwp=transport=dt_socket");
            assertThrows(IllegalArgumentException.class, () -> RunExecutionEvents.configureDebug(execution, framework, "51235", session));
        }
    }

    private static void configureDebug(MojoExecution execution, FrameworkLaunch framework, String port) throws Exception {
        RunExecutionEvents.configureDebug(execution, framework, port, session());
    }
    private static MavenSession session() {
        MavenSession session = new MavenSession(null, null, new DefaultMavenExecutionRequest(), new DefaultMavenExecutionResult());
        session.setCurrentProject(new MavenProject()); return session;
    }

    private static FrameworkLaunch framework(String group, String artifact) {
        Plugin plugin = new Plugin(); plugin.setGroupId(group); plugin.setArtifactId(artifact); plugin.setVersion("1");
        Build build = new Build(); build.addPlugin(plugin); Model model = new Model(); model.setPackaging("jar"); model.setBuild(build);
        return FrameworkLaunch.find(new MavenProject(model));
    }
    private static Parameter parameter(String name, String type, String property) {
        Parameter parameter = new Parameter(); parameter.setName(name); parameter.setType(type);
        if (property != null) parameter.setExpression("${" + property + "}"); return parameter;
    }
    private static MojoExecution execution(Parameter... parameters) throws Exception {
        MojoDescriptor descriptor = new MojoDescriptor(); descriptor.setParameters(new ArrayList<>(Arrays.asList(parameters)));
        MojoExecution execution = new MojoExecution(new Plugin(), "run", "default-cli"); execution.setMojoDescriptor(descriptor);
        execution.setConfiguration(new Xpp3Dom("configuration")); return execution;
    }
    private static void set(MojoExecution execution, String key, String text) {
        Xpp3Dom value = execution.getConfiguration().getChild(key);
        if (value == null) { value = new Xpp3Dom(key); execution.getConfiguration().addChild(value); }
        value.setValue(text);
    }
    private static String value(MojoExecution execution, String key) { return execution.getConfiguration().getChild(key).getValue(); }
}
