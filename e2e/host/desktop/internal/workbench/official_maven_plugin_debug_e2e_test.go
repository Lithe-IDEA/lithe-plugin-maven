package workbench

import (
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/1lck/Lithe-IDEA/desktop/internal/core"
)

// TestOfficialMavenPluginDebugsASpringBootApplication covers Debug on the Maven archive's
// Spring Boot run configuration with the released Java Support archive, whose java adapter the
// run's debug type selects: the service starts suspended on a host-chosen port, the Java
// debugger attaches, a request to the endpoint stops on a breakpoint in the application's
// source, Continue lets the request finish, and Stop in the Debug window ends the service. It
// uses the user's mvn and local repository.
func TestOfficialMavenPluginDebugsASpringBootApplication(t *testing.T) {
	archive := os.Getenv("LITHE_TEST_MAVEN_PLUGIN_ARCHIVE")
	if archive == "" {
		t.Skip("set LITHE_TEST_MAVEN_PLUGIN_ARCHIVE to a maven-build-<version>-darwin.zip")
	}
	javaArchive := os.Getenv("LITHE_TEST_JAVA_PLUGIN_ARCHIVE")
	if javaArchive == "" {
		t.Fatal("set LITHE_TEST_JAVA_PLUGIN_ARCHIVE to a Lithe-Java-Support-<version>-darwin-<arch>.zip: its java adapter debugs the run")
	}
	if _, err := exec.LookPath("mvn"); err != nil {
		t.Fatalf("mvn is not on PATH: %v", err)
	}
	h := newHarness(t, &testKernel{Client: core.NewClient()})
	// Java Support starts its language server, which runs the java adapter, with ${logDir}: the
	// client always has a log store, which the harness replaces with one that gives a directory.
	h.app.logSink = &pluginFilesLog{directory: t.TempDir()}
	installed := installOfficialPluginArchive(t, h, archive, "io.github.lithe-idea.maven-build")
	installPluginArchiveInto(t, h.app.plugins, javaArchive, "io.github.lithe-idea.java-support")
	before := packageDigest(t, installed)
	root := t.TempDir()
	springBootReactor(t, root)
	source := filepath.Join(root, "web", "src", "main", "java", "demo", "web", "ShopApplication.java")
	line := sourceLine(t, source, "return Greeting.of(")
	h.open(root)
	p := h.app.project
	if p == nil {
		t.Fatal("the reactor did not open")
	}
	var console func() string
	t.Cleanup(func() {
		if t.Failed() {
			text := ""
			if console != nil {
				text = console()
			}
			t.Logf("build status %q, debug state %q status %q failure %q, notice %q, debug output:\n%s\nrun console:\n%s",
				p.build.status, p.debug.state, p.debug.status, p.debug.failure, lastNotice(h), strings.Join(p.debug.output, ""), text)
		}
	})

	h.app.post(func(a *App) { a.loadTitleRunConfigs(p) })
	awaitBuildModel(t, h, p, "maven", 5*time.Minute)
	awaitStateWithin(t, h, time.Minute, "the run configurations", func() bool {
		return p.runConfigsSettled() && p.runConfigs.generation > 0
	})
	if len(p.runConfigs.list) != 1 || p.runConfigs.list[0].Name != "ShopApplication" {
		t.Fatalf("the run configurations are %+v, want only ShopApplication", p.runConfigs.list)
	}
	id := p.runConfigs.list[0].ID
	if facts := p.titleConfigurationFacts(p.buildInstanceIndex(mustBuildReference(t, p, id)), mustBuildReference(t, p, id)); facts.Debuggable == nil || !*facts.Debuggable {
		t.Fatal("ShopApplication is not debuggable")
	}

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

	h.app.post(func(a *App) {
		a.toggleBreakpointAt(p, source, line)
		a.chooseTitleConfiguration(p, id)
		a.titleDebug(p)
	})
	awaitStateWithin(t, h, 30*time.Second, "the debugged run", func() bool { return p.build.operation != nil })
	op := p.build.operation
	console = func() string { return op.session.output.all() }
	awaitStateWithin(t, h, 5*time.Minute, "the attached debugger", func() bool {
		session := p.debug.activeSession()
		return session != nil && session.adapter.Declaration.ID == "java" && p.debug.state == "running"
	})

	// The request runs on the server's thread and stops on the breakpoint until Continue.
	url := "http://127.0.0.1:" + strconv.Itoa(port) + "/hello"
	type answer struct {
		body string
		err  error
	}
	answers := make(chan answer, 1)
	go func() {
		deadline := time.Now().Add(5 * time.Minute)
		client := &http.Client{Timeout: 5 * time.Minute}
		for {
			response, err := client.Get(url)
			if err != nil {
				if time.Now().After(deadline) {
					answers <- answer{err: err}
					return
				}
				time.Sleep(500 * time.Millisecond)
				continue
			}
			data, err := io.ReadAll(io.LimitReader(response.Body, 4096))
			_ = response.Body.Close()
			answers <- answer{body: string(data), err: err}
			return
		}
	}()
	awaitStateWithin(t, h, 5*time.Minute, "the stop on the breakpoint", func() bool {
		return p.debug.pausedThread != nil && len(p.debug.frames) > 0
	})
	top := p.debug.frames[0]
	if filepath.Base(top.SourcePath) != "ShopApplication.java" || top.Line != line {
		t.Fatalf("stopped at %s:%d in %s, want ShopApplication.java:%d", top.SourcePath, top.Line, top.Name, line)
	}
	select {
	case got := <-answers:
		t.Fatalf("the request finished while the server was stopped on the breakpoint: %+v", got)
	default:
	}

	h.app.post(func(a *App) { a.continueDebug(p) })
	got := awaitValueWithin(t, h, time.Minute, answers, "the request after Continue")
	if got.err != nil || got.body != "Hello, Lithe from eu-west" {
		t.Fatalf("GET /hello after Continue = %q, %v", got.body, got.err)
	}

	// Stop in the Debug window ends the service it attached to.
	h.app.post(func(a *App) { a.stopDebugByUser(p) })
	awaitStateWithin(t, h, time.Minute, "the stopped service", func() bool {
		return p.build.operation == nil && p.debug.activeSession() == nil
	})
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

// sourceLine is the one-based line of path that contains text.
func sourceLine(t *testing.T, path, text string) int64 {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	for index, line := range strings.Split(string(data), "\n") {
		if strings.Contains(line, text) {
			return int64(index + 1)
		}
	}
	t.Fatalf("%s has no line with %q", path, text)
	return 0
}

// mustBuildReference is the build provider reference of configuration id.
func mustBuildReference(t *testing.T, p *project, id string) core.RunProviderReference {
	t.Helper()
	configuration := p.runConfigs.configuration(id)
	if configuration == nil {
		t.Fatalf("no configuration %q", id)
	}
	reference, ok := configuration.BuildReference()
	if !ok {
		t.Fatalf("configuration %q does not start through a build provider", id)
	}
	return reference
}
