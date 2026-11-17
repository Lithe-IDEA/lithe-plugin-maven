package workbench

import (
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/1lck/Lithe-IDEA/desktop/internal/core"
)

// springBootReactor writes a two-module Spring Boot web application below root: a plain
// library module and a web module that depends on it. The web endpoint answers with what the
// run configuration passed in, so one request proves the JVM argument, the program argument
// and the environment variable reached the process.
func springBootReactor(t *testing.T, root string) {
	t.Helper()
	writePluginFixture(t, root, map[string]string{
		"pom.xml": `<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.13</version>
    <relativePath/>
  </parent>
  <groupId>demo</groupId>
  <artifactId>shop</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>pom</packaging>
  <properties><java.version>17</java.version></properties>
  <modules><module>common</module><module>web</module></modules>
</project>
`,
		"common/pom.xml": `<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>demo</groupId><artifactId>shop</artifactId><version>1.0.0-SNAPSHOT</version></parent>
  <artifactId>common</artifactId>
</project>
`,
		"common/src/main/java/demo/common/Greeting.java": `package demo.common;

public final class Greeting {
    private Greeting() {}

    public static String of(String name) {
        return "Hello, " + name;
    }
}
`,
		"web/pom.xml": `<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>demo</groupId><artifactId>shop</artifactId><version>1.0.0-SNAPSHOT</version></parent>
  <artifactId>web</artifactId>
  <dependencies>
    <dependency><groupId>demo</groupId><artifactId>common</artifactId><version>${project.version}</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
    </plugins>
  </build>
</project>
`,
		"web/src/main/java/demo/web/ShopApplication.java": `package demo.web;

import demo.common.Greeting;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class ShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }

    @GetMapping("/hello")
    public String hello() {
        return Greeting.of(System.getProperty("shop.name")) + " from " + System.getenv("SHOP_REGION");
    }
}
`,
	})
}

// freePort returns a loopback TCP port nothing listens on right now.
func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	return port
}

// TestOfficialMavenPluginRunsASpringBootApplication covers the Maven archive's run
// configurations with the released Java Support archive, which declares the java tool the
// launch plan names: the Spring Boot application of a real reactor appears as a run
// configuration, the options saved in its editor reach the process, the run compiles the
// module and its reactor dependency and serves HTTP, and Stop ends the application. It uses
// the user's mvn and local repository, so the first run may download Spring Boot.
func TestOfficialMavenPluginRunsASpringBootApplication(t *testing.T) {
	archive := os.Getenv("LITHE_TEST_MAVEN_PLUGIN_ARCHIVE")
	if archive == "" {
		t.Skip("set LITHE_TEST_MAVEN_PLUGIN_ARCHIVE to a maven-build-<version>-darwin.zip")
	}
	javaArchive := os.Getenv("LITHE_TEST_JAVA_PLUGIN_ARCHIVE")
	if javaArchive == "" {
		t.Fatal("set LITHE_TEST_JAVA_PLUGIN_ARCHIVE to a Lithe-Java-Support-<version>-darwin-<arch>.zip: its java tool launches the run")
	}
	if _, err := exec.LookPath("mvn"); err != nil {
		t.Fatalf("mvn is not on PATH: %v", err)
	}
	h := newHarness(t, &testKernel{Client: core.NewClient()})
	installed := installOfficialPluginArchive(t, h, archive, "io.github.lithe-idea.maven-build")
	installPluginArchiveInto(t, h.app.plugins, javaArchive, "io.github.lithe-idea.java-support")
	before := packageDigest(t, installed)
	root := t.TempDir()
	springBootReactor(t, root)
	h.open(root)
	p := h.app.project
	if p == nil {
		t.Fatal("the reactor did not open")
	}

	h.app.post(func(a *App) { a.loadTitleRunConfigs(p) })
	awaitBuildModel(t, h, p, "maven", 5*time.Minute)
	awaitStateWithin(t, h, time.Minute, "the run configurations", func() bool {
		return p.runConfigsSettled() && p.runConfigs.generation > 0
	})
	if p.runConfigs.failure != "" {
		t.Fatalf("the run configurations failed: %s", p.runConfigs.failure)
	}
	var configuration *core.RunConfiguration
	for index := range p.runConfigs.list {
		if p.runConfigs.list[index].Name == "ShopApplication" {
			configuration = &p.runConfigs.list[index]
		}
	}
	if configuration == nil || len(p.runConfigs.list) != 1 {
		t.Fatalf("the run configurations are %+v, want only ShopApplication", p.runConfigs.list)
	}
	if configuration.Execution != "service" {
		t.Fatalf("ShopApplication runs as %q, want service", configuration.Execution)
	}
	if notice := p.noRunConfigurationsNotice(h.app.language()); notice != "" {
		t.Fatalf("a project with a configuration explains that it has none: %q", notice)
	}
	id := configuration.ID

	// The editor's Save writes the local layer through Core, as a user does.
	port := freePort(t)
	h.app.post(func(a *App) { a.openRunConfigEditor(p, id) })
	awaitStateWithin(t, h, 30*time.Second, "the run configuration editor", func() bool {
		return p.runConfigs.editor.open && !p.runConfigs.editor.loading
	})
	h.app.post(func(a *App) {
		e := &p.runConfigs.editor
		e.runtimeArguments = "-Dshop.name=Lithe"
		e.arguments = "--server.port=" + strconv.Itoa(port) + " --server.address=127.0.0.1"
		e.environment = "SHOP_REGION=eu-west"
		a.saveRunConfigEditor(p)
	})
	awaitStateWithin(t, h, 30*time.Second, "the saved run configuration", func() bool {
		return !p.runConfigs.editor.open && !p.runConfigs.loading && p.runConfigs.configuration(id) != nil
	})
	if failure := p.runConfigs.editor.failure; failure != "" {
		t.Fatalf("saving the run configuration failed: %s", failure)
	}

	var op *buildOperation
	var startErr error
	h.app.post(func(a *App) { op, startErr = a.startRunConfiguration(p, id) })
	awaitStateWithin(t, h, 10*time.Second, "the run operation", func() bool { return op != nil || startErr != nil })
	if startErr != nil {
		t.Fatalf("start ShopApplication: %v", startErr)
	}
	// The Run window's console of this run; it stays readable after the run ends.
	output := func() string { return op.session.output.all() }
	t.Cleanup(func() {
		if t.Failed() {
			t.Logf("status %q, run console:\n%s", p.build.status, output())
		}
	})

	url := "http://127.0.0.1:" + strconv.Itoa(port) + "/hello"
	client := &http.Client{Timeout: 2 * time.Second}
	var body string
	deadline := time.Now().Add(5 * time.Minute)
	for body == "" {
		if time.Now().After(deadline) {
			t.Fatalf("ShopApplication did not answer within 5 minutes; status=%q output:\n%s", p.build.status, output())
		}
		// Pump only: settle waits for every background task, and the running application is one.
		if err := h.driver.Pump(); err != nil {
			t.Fatal(err)
		}
		if p.build.operation == nil {
			t.Fatalf("the run ended before the application answered; status=%q output:\n%s", p.build.status, output())
		}
		response, err := client.Get(url)
		if err != nil {
			time.Sleep(500 * time.Millisecond)
			continue
		}
		data, readErr := io.ReadAll(io.LimitReader(response.Body, 4096))
		_ = response.Body.Close()
		if readErr != nil || response.StatusCode != http.StatusOK {
			t.Fatalf("GET %s = %d %q (%v)", url, response.StatusCode, data, readErr)
		}
		body = string(data)
	}
	if body != "Hello, Lithe from eu-west" {
		t.Fatalf("GET /hello = %q, want the JVM argument and the environment variable", body)
	}
	// The console shows the Maven build the provider ran before the launch, then the
	// application's own log; the log line after the one that opened the port may still be
	// on its way when the endpoint first answers.
	wants := []string{"BUILD SUCCESS", "common", "Tomcat started on port " + strconv.Itoa(port), "Started ShopApplication"}
	awaitStateWithin(t, h, time.Minute, "the run console", func() bool {
		text := output()
		for _, want := range wants {
			if !strings.Contains(text, want) {
				return false
			}
		}
		return true
	})

	h.app.post(func(a *App) { a.stopBuildOperation(p) })
	awaitStateWithin(t, h, time.Minute, "the stopped run", func() bool { return p.build.operation == nil })
	if _, err := client.Get(url); err == nil {
		t.Fatal("ShopApplication still answers after Stop")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:"+strconv.Itoa(port))
	if err != nil {
		t.Fatalf("the application's port is still taken after Stop: %v", err)
	}
	_ = listener.Close()

	h.app.closeProject()
	awaitStateWithin(t, h, 30*time.Second, "the closed project", func() bool { return h.app.project == nil })
	h.settle()
	assertPackageUnchanged(t, installed, before)
}
