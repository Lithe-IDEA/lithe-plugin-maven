package workbench

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/1lck/Lithe-IDEA/desktop/internal/core"
	"github.com/1lck/Lithe-IDEA/desktop/internal/i18n"
	"github.com/1lck/Lithe-IDEA/desktop/internal/plugininstall"
	"github.com/1lck/Lithe-IDEA/desktop/internal/pluginservice"
	"github.com/1lck/Lithe-IDEA/desktop/internal/pluginsettings"
	"github.com/1lck/Lithe-IDEA/desktop/internal/plugintrust"
)

// This opt-in integration test installs the released Maven Build archive through the real
// installer, enables it and drives the workbench as a user does: detection, the reactor model
// the packaged core extension prints, a module's dependency tree, the Maven executable setting
// and a real compile. It runs the user's own mvn, so it skips unless an archive is named, and
// fails rather than skips once a named archive cannot do its work.

// installOfficialPluginArchive installs and enables archive in an isolated
// plugin root and returns the installed package directory.
func installOfficialPluginArchive(t *testing.T, h *harness, archive, id string) string {
	t.Helper()
	service, err := pluginservice.Open(core.NewClient(), h.store, t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	// The app gives its service these resolvers when it opens it (app.go); a provider or backend
	// that runs on a tool, such as a jar on java, cannot start without them, and a language
	// server whose launch uses ${logDir} cannot start without the log directory.
	service.Backend.SetToolResolver(h.app.resolveBackendTool)
	service.Backend.SetToolEntryResolver(h.app.resolveBackendToolEntry)
	service.LanguageTool = h.app.resolveLanguageTool
	service.LogDirectory = h.app.pluginLogDirectory
	h.app.plugins = service
	return installPluginArchiveInto(t, service, archive, id)
}

// installPluginArchiveInto installs and enables archive in service's plugin root, next to the
// plugins already there, and returns the installed package directory.
func installPluginArchiveInto(t *testing.T, service *pluginservice.Service, archive, id string) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	plan, err := service.Installer.Prepare(ctx, plugininstall.Request{ArchivePath: archive, Root: service.Root, Source: plugintrust.Disk})
	if err != nil {
		t.Fatalf("prepare %s: %v", archive, err)
	}
	if plan.ID != id {
		_ = service.Installer.Discard(plan)
		t.Fatalf("archive holds %s, want %s", plan.ID, id)
	}
	installed, err := service.Installer.Commit(plan)
	if err != nil {
		_ = service.Installer.Discard(plan)
		t.Fatalf("install %s: %v", id, err)
	}
	if err := service.Manager.SetEnabled(id, true); err != nil {
		t.Fatal(err)
	}
	return installed.Path
}

// packageDigest hashes every file of an installed package, so a test can prove a
// workflow left the package byte-for-byte as installed.
func packageDigest(t *testing.T, root string) map[string]string {
	t.Helper()
	digest := map[string]string{}
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return err
		}
		file, err := os.Open(path)
		if err != nil {
			return err
		}
		// JDK module images are large; stream the unchanged-package proof so
		// test buffers do not inflate the headless host's memory observations.
		sum := sha256.New()
		_, readErr := io.Copy(sum, file)
		closeErr := file.Close()
		if readErr != nil {
			return readErr
		}
		if closeErr != nil {
			return closeErr
		}
		relative, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		digest[relative] = hex.EncodeToString(sum.Sum(nil))
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	return digest
}

func assertPackageUnchanged(t *testing.T, root string, before map[string]string) {
	t.Helper()
	after := packageDigest(t, root)
	if len(after) != len(before) {
		t.Fatalf("the installed package now has %d files, it had %d", len(after), len(before))
	}
	for name, sum := range before {
		if after[name] != sum {
			t.Fatalf("the workflow changed the installed package file %s", name)
		}
	}
}

// writePluginFixture writes files (relative path to content) below root.
func writePluginFixture(t *testing.T, root string, files map[string]string) {
	t.Helper()
	for name, content := range files {
		path := filepath.Join(root, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
			t.Fatal(err)
		}
	}
}

// awaitBuildModel waits until detection found an instance of buildSystemID and its
// model is loaded, failing with the model error when the plugin's command failed.
func awaitBuildModel(t *testing.T, h *harness, p *project, buildSystemID string, limit time.Duration) *buildInstance {
	t.Helper()
	var instance *buildInstance
	awaitStateWithin(t, h, limit, buildSystemID+" detection and model", func() bool {
		for index := range p.build.instances {
			candidate := &p.build.instances[index]
			if candidate.BuildSystemID != buildSystemID {
				continue
			}
			if candidate.modelError != "" {
				t.Fatalf("the %s model failed: %s", buildSystemID, candidate.modelError)
			}
			if candidate.modelLoaded {
				p.build.selected = index
				instance = candidate
				return true
			}
		}
		if p.build.failed != "" {
			t.Fatalf("build detection failed: %s", p.build.failed)
		}
		return false
	})
	return instance
}

// runBuildTaskToEnd runs one task of the selected instance and waits for it to end.
func runBuildTaskToEnd(t *testing.T, h *harness, p *project, projectID, taskID string, limit time.Duration) string {
	t.Helper()
	p.build.projectID, p.build.targetID, p.build.taskID = projectID, "", taskID
	op, err := h.app.runBuildTask(p, projectID, "", taskID, nil)
	if err != nil {
		t.Fatalf("start %s: %v", taskID, err)
	}
	awaitStateWithin(t, h, limit, "the "+taskID+" task", func() bool { return p.build.operation == nil })
	output := op.session.output.all()
	if p.build.status != h.app.text.Build.Succeeded {
		t.Fatalf("%s did not succeed: status=%q output=%q", taskID, p.build.status, output)
	}
	return output
}

func modelProjectNamed(model core.BuildModel, name string) *core.BuildProject {
	for index := range model.Projects {
		if model.Projects[index].Name == name {
			return &model.Projects[index]
		}
	}
	return nil
}

// markingWrapper writes an executable script that records each start in a marker file and
// then runs the named tool found on PATH, so a test can tell that a configured path ran.
func markingWrapper(t *testing.T, name string) (wrapper, marker string) {
	t.Helper()
	target, err := exec.LookPath(name)
	if err != nil {
		t.Fatalf("%s is not on PATH: %v", name, err)
	}
	dir := t.TempDir()
	marker = filepath.Join(dir, name+".started")
	wrapper = filepath.Join(dir, name)
	script := "#!/bin/sh\n: > '" + marker + "'\nexec '" + target + "' \"$@\"\n"
	if err := os.WriteFile(wrapper, []byte(script), 0o700); err != nil {
		t.Fatal(err)
	}
	return wrapper, marker
}

// saveGlobalPluginSettings saves values to the plugin's global settings layer the way the
// settings window does, then dispatches the effects Core answered for the change.
func saveGlobalPluginSettings(t *testing.T, h *harness, instance *buildInstance, values map[string]any) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	store := pluginsettings.New(h.app.kernel, h.store.Dir())
	snapshot, err := store.Load(ctx, instance.Manifest, h.app.project.root)
	if err != nil {
		t.Fatalf("load the plugin settings: %v", err)
	}
	ids := make([]string, 0, len(values))
	for id := range values {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	edits := make([]core.PluginSettingEdit, 0, len(ids))
	for _, id := range ids {
		value, err := json.Marshal(values[id])
		if err != nil {
			t.Fatal(err)
		}
		edits = append(edits, core.PluginSettingEdit{ID: id, Value: value})
	}
	result, err := store.Save(ctx, snapshot, "global", edits, nil)
	if err != nil || !result.Committed {
		t.Fatalf("save the plugin settings: committed=%v err=%v", result.Committed, err)
	}
	h.app.applyPluginSettingsEffects(core.PluginSettingsOwner{PluginID: instance.PluginID, Version: instance.Version}, result.Effects)
}

// TestOfficialMavenPluginBuildsAReactor covers the Maven archive: the user's mvn
// loads the packaged core extension from the read-only package, which prints the
// reactor model (projects with their aggregator, plugin goals as model tasks)
// and a module's dependency tree, and a lifecycle task compiles the reactor. It
// uses the user's local repository, so the first run may download Maven plugins.
func TestOfficialMavenPluginBuildsAReactor(t *testing.T) {
	archive := os.Getenv("LITHE_TEST_MAVEN_PLUGIN_ARCHIVE")
	if archive == "" {
		t.Skip("set LITHE_TEST_MAVEN_PLUGIN_ARCHIVE to a maven-build-<version>-darwin.zip")
	}
	if _, err := exec.LookPath("mvn"); err != nil {
		t.Fatalf("mvn is not on PATH: %v", err)
	}
	h := newHarness(t, &testKernel{Client: core.NewClient()})
	installed := installOfficialPluginArchive(t, h, archive, "io.github.lithe-idea.maven-build")
	before := packageDigest(t, installed)
	root := t.TempDir()
	module := func(name, dependencies string) string {
		return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n" +
			"  <parent><groupId>demo</groupId><artifactId>reactor</artifactId><version>1.0.0-SNAPSHOT</version></parent>\n" +
			"  <artifactId>" + name + "</artifactId>\n" + dependencies + "</project>\n"
	}
	writePluginFixture(t, root, map[string]string{
		"pom.xml": "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n" +
			"  <groupId>demo</groupId><artifactId>reactor</artifactId><version>1.0.0-SNAPSHOT</version><packaging>pom</packaging>\n" +
			"  <properties><maven.compiler.release>11</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>\n" +
			"  <modules><module>core</module><module>app</module></modules>\n</project>\n",
		"core/pom.xml": module("core", ""),
		"core/src/main/java/demo/core/Greeter.java": "package demo.core;\n\npublic final class Greeter {\n    public static String greet(String name) { return \"Hello, \" + name; }\n}\n",
		"app/pom.xml":                          module("app", "  <dependencies><dependency><groupId>demo</groupId><artifactId>core</artifactId><version>${project.version}</version></dependency></dependencies>\n"),
		"app/src/main/java/demo/app/Main.java": "package demo.app;\n\npublic final class Main {\n    public static void main(String[] args) { System.out.println(demo.core.Greeter.greet(\"Lithe\")); }\n}\n",
	})
	h.open(root)
	p := h.app.project
	if p == nil {
		t.Fatal("the reactor did not open")
	}
	h.app.loadBuildSystems(p)
	instance := awaitBuildModel(t, h, p, "maven", 3*time.Minute)
	reactor, library, application := modelProjectNamed(instance.Model, "reactor"), modelProjectNamed(instance.Model, "core"), modelProjectNamed(instance.Model, "app")
	if reactor == nil || library == nil || application == nil {
		t.Fatalf("the reactor model is missing a project: %+v", instance.Model.Projects)
	}
	if library.ParentID != reactor.ID || application.ParentID != reactor.ID || library.Path != "core" {
		t.Fatalf("the modules are not below their aggregator: %+v", instance.Model.Projects)
	}
	goals := 0
	for _, task := range instance.Model.Tasks {
		if task.Template == "plugin-goal" {
			goals++
		}
	}
	if goals == 0 {
		t.Fatalf("the model lists no plugin goals: %+v", instance.Model.Tasks)
	}

	p.build.projectID = application.ID
	deps := h.app.dependenciesOf(p, instance, application.ID)
	awaitStateWithin(t, h, 3*time.Minute, "the app dependency tree", func() bool { return !deps.loading })
	if deps.failed != "" {
		t.Fatalf("the app dependencies failed: %s", deps.failed)
	}
	if len(deps.tree.Dependencies) != 1 || deps.tree.Dependencies[0].Name != "core" || deps.tree.Dependencies[0].Scope != "compile" {
		t.Fatalf("the app dependency tree = %+v", deps.tree.Dependencies)
	}

	// The Maven executable setting is the first candidate: a path set there reloads the
	// model and runs every later command instead of mvn on PATH.
	mvnWrapper, mvnMarker := markingWrapper(t, "mvn")
	saveGlobalPluginSettings(t, h, instance, map[string]any{"mavenExecutable": mvnWrapper})
	instance = awaitBuildModel(t, h, p, "maven", 3*time.Minute)
	if _, err := os.Stat(mvnMarker); err != nil {
		t.Fatalf("the model reload did not run the configured mvn: %v", err)
	}
	if err := os.Remove(mvnMarker); err != nil {
		t.Fatal(err)
	}
	output := runBuildTaskToEnd(t, h, p, "", "compile", 5*time.Minute)
	if !strings.Contains(output, "BUILD SUCCESS") {
		t.Fatalf("mvn compile printed no success: %q", output)
	}
	if _, err := os.Stat(mvnMarker); err != nil {
		t.Fatalf("compile did not run the configured mvn: %v", err)
	}
	for _, class := range []string{"core/target/classes/demo/core/Greeter.class", "app/target/classes/demo/app/Main.class"} {
		if _, err := os.Stat(filepath.Join(root, filepath.FromSlash(class))); err != nil {
			t.Fatalf("mvn compile did not produce %s: %v", class, err)
		}
	}
	h.app.closeProject()
	awaitStateWithin(t, h, 30*time.Second, "the closed project", func() bool { return h.app.project == nil })
	h.settle()
	assertPackageUnchanged(t, installed, before)
}

// TestOfficialMavenPluginSwitchesActiveProfilesInBuildTree installs the Maven Build archive,
// verifies <activeByDefault> profiles show checked in the Build tool window's Profiles node,
// toggles from dev to prod through the real checkbox UI, and checks that model reload,
// compile and project reopen all honor -P -dev,prod.
func TestOfficialMavenPluginSwitchesActiveProfilesInBuildTree(t *testing.T) {
	archive := os.Getenv("LITHE_TEST_MAVEN_PLUGIN_ARCHIVE")
	if archive == "" {
		t.Skip("set LITHE_TEST_MAVEN_PLUGIN_ARCHIVE to a maven-build-<version>-darwin.zip")
	}
	if _, err := exec.LookPath("mvn"); err != nil {
		t.Fatalf("mvn is not on PATH: %v", err)
	}
	h := newHarness(t, &testKernel{Client: core.NewClient()})
	installed := installOfficialPluginArchive(t, h, archive, "io.github.lithe-idea.maven-build")
	before := packageDigest(t, installed)
	root := t.TempDir()
	modulePom := func(name string) string {
		return "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n" +
			"  <parent><groupId>demo</groupId><artifactId>reactor</artifactId><version>1.0.0-SNAPSHOT</version></parent>\n" +
			"  <artifactId>" + name + "</artifactId>\n</project>\n"
	}
	writePluginFixture(t, root, map[string]string{
		"pom.xml": "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n  <modelVersion>4.0.0</modelVersion>\n" +
			"  <groupId>demo</groupId><artifactId>reactor</artifactId><version>1.0.0-SNAPSHOT</version><packaging>pom</packaging>\n" +
			"  <properties><maven.compiler.release>11</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>\n" +
			"  <profiles>\n" +
			"    <profile><id>dev</id><activation><activeByDefault>true</activeByDefault></activation><modules><module>dev-mod</module></modules></profile>\n" +
			"    <profile><id>prod</id><modules><module>prod-mod</module></modules></profile>\n" +
			"  </profiles>\n</project>\n",
		"dev-mod/pom.xml":                              modulePom("dev-mod"),
		"dev-mod/src/main/java/demo/dev/DevOnly.java":  "package demo.dev;\npublic final class DevOnly {}\n",
		"prod-mod/pom.xml":                             modulePom("prod-mod"),
		"prod-mod/src/main/java/demo/prod/ProdOk.java": "package demo.prod;\npublic final class ProdOk {}\n",
	})
	h.open(root)
	p := h.app.project
	if p == nil {
		t.Fatal("the reactor did not open")
	}
	h.app.reduceChrome(core.ToggleChrome(itemBuild))
	instance := awaitBuildModel(t, h, p, "maven", 3*time.Minute)
	if modelProjectNamed(instance.Model, "dev-mod") == nil || modelProjectNamed(instance.Model, "prod-mod") != nil {
		t.Fatalf("initial projects = %+v, want dev-mod active by default", instance.Model.Projects)
	}

	awaitStateWithin(t, h, 30*time.Second, "the Build tree layout", func() bool {
		return p.build.instances[0].trees.whole != nil && !p.build.instances[0].trees.wholeOut
	})
	h.settle()
	h.click(center(h.labelBounds("Profiles")))
	h.settle()

	language := h.app.language()
	toggle := func(choice string) {
		t.Helper()
		awaitStateWithin(t, h, 30*time.Second, "tree ready before toggling "+choice, func() bool {
			return !p.build.optionSaving && !p.build.loading && len(p.build.instances) == 1 &&
				p.build.instances[0].modelLoaded && !p.build.instances[0].trees.wholeOut
		})
		h.settle()
		label := i18n.SharedFormat(language, "desktop.build.toggleOptionChoice", map[string]string{
			"option": "Profiles",
			"choice": choice,
		})
		h.clickNamed(label)
		awaitStateWithin(t, h, 3*time.Minute, "reload after toggling "+choice, func() bool {
			return !p.build.optionSaving && p.build.loaded && !p.build.loading && !p.build.reloadPending &&
				len(p.build.instances) == 1 && p.build.instances[0].modelLoaded && !p.build.instances[0].modelLoading
		})
	}

	toggle("prod")
	toggle("dev")
	instance = &p.build.instances[0]
	if modelProjectNamed(instance.Model, "prod-mod") == nil || modelProjectNamed(instance.Model, "dev-mod") != nil {
		t.Fatalf("projects after switching to prod = %+v, want prod-mod only", instance.Model.Projects)
	}

	output := runBuildTaskToEnd(t, h, p, "", "compile", 5*time.Minute)
	if !strings.Contains(output, "BUILD SUCCESS") {
		t.Fatalf("mvn compile printed no success: %q", output)
	}
	if _, err := os.Stat(filepath.Join(root, "prod-mod/target/classes/demo/prod/ProdOk.class")); err != nil {
		t.Fatalf("prod-mod did not compile: %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, "dev-mod/target/classes/demo/dev/DevOnly.class")); !os.IsNotExist(err) {
		t.Fatalf("inactive dev-mod was compiled: %v", err)
	}

	// Reopening the workspace keeps -P -dev,prod on cold start before decodeModel runs.
	h.app.closeProject()
	awaitStateWithin(t, h, 30*time.Second, "the closed project", func() bool { return h.app.project == nil })
	h.open(root)
	p = h.app.project
	h.app.loadBuildSystems(p)
	reopened := awaitBuildModel(t, h, p, "maven", 3*time.Minute)
	if modelProjectNamed(reopened.Model, "prod-mod") == nil || modelProjectNamed(reopened.Model, "dev-mod") != nil {
		t.Fatalf("projects after reopen = %+v, want prod-mod only", reopened.Model.Projects)
	}
	h.app.closeProject()
	awaitStateWithin(t, h, 30*time.Second, "the closed project", func() bool { return h.app.project == nil })
	h.settle()
	assertPackageUnchanged(t, installed, before)
}
