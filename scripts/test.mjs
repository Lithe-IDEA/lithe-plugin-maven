#!/usr/bin/env node
// Runs test/fixtures/reactor with a real Maven, the way Lithe runs the
// commands plugin.json declares: the model and dependency commands load
// lib/lithe-maven-extension.jar into Maven and write the documents Lithe
// decodes to ${outputFile}; the test command runs a filtered suite. It checks
// the documents Lithe receives; Lithe's own tests decode them, this script
// makes sure Maven produces them.
//
// Run scripts/package.mjs first. MVN selects the Maven executable (default
// `mvn`), so CI can also check the oldest supported Maven; JAVA_HOME selects
// the Java that runs it, as the JDK build option does in Lithe.
//
// LITHE_TEST_PLUGIN_DIR=<dir> tests an unpacked release archive instead of
// dist/package, as the Windows CI job does on a machine that did not build it.
//
// LITHE_CAPTURE_DIR=<dir> also copies the documents there, with the
// scratch workspace written as `${workspace}`, for Lithe's Core tests.
//
// Usage: node scripts/test.mjs

import assert from "node:assert/strict";
import { mavenTimeout, runCommand, withInterrupts } from "./process.mjs";
import { createReport } from "./report.mjs";
import { BackendClient } from "./compiler/backend-client.mjs";
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { homedir, tmpdir } from "node:os";
import { delimiter, join, resolve } from "node:path";

const PACKAGE_DIR = "io.github.lithe-idea.maven-build";
const pluginDir = resolve(process.env.LITHE_TEST_PLUGIN_DIR || join("dist", "package", PACKAGE_DIR));
const maven = process.env.MVN ?? "mvn";
if (!existsSync(join(pluginDir, "lib", "lithe-maven-extension.jar"))) {
  console.error(`✗ ${pluginDir} is missing; run node scripts/package.mjs first`);
  process.exit(1);
}
const manifest = JSON.parse(readFileSync(join(pluginDir, "plugin.json"), "utf8"));
const buildSystem = manifest.buildSystems[0];

/**
 * Expands a declared argument list the way Lithe does for this test: no
 * option has a value, so `${optionArguments:…}` contributes nothing, and a
 * conditional argument is kept when its conditions hold.
 */
function expand(list, { project, suite, outputFile }) {
  const conditions = {
    projectSelected: project !== undefined,
    noProjectSelected: project === undefined,
    nestedProjectSelected: project !== undefined && project !== ".",
    suiteSelected: suite !== undefined,
    caseSelected: false,
    noCaseSelected: true,
  };
  const result = [];
  for (const item of list) {
    const value = typeof item === "string" ? item : item.value;
    const when = typeof item === "string" ? [] : [item.when].flat();
    if (!when.every((condition) => conditions[condition])) continue;
    if (/^\$\{optionArguments(:[A-Za-z]+)?\}$/.test(value)) continue;
    result.push(
      value
        .replaceAll("${pluginDir}", pluginDir)
        .replaceAll("${outputFile}", outputFile ?? "")
        .replaceAll("${project.path}", project ?? "")
        .replaceAll("${test.suite}", suite ?? ""),
    );
  }
  return result;
}

const report = createReport("maven-integration");
const timeoutMs = mavenTimeout();
function run(name, root, list, signal, values = {}) {
  const argv = [...expand(buildSystem.invocation.globalArguments, values), ...expand(list, values)];
  return report.step(name, () => runCommand(maven, argv, {
    cwd: root, stdio: ["ignore", "ignore", "inherit"], timeoutMs, signal,
  }));
}

// The native real path expands Windows 8.3 short names (the runners' TEMP is
// C:\Users\RUNNER~1\...): Maven canonicalizes module directories on Windows
// but resolves `-pl core` against the working directory as given, so under a
// short name it finds no selected project. Lithe passes the long path it opened.
const scratch = realpathSync.native(mkdtempSync(join(tmpdir(), "lithe-maven-test-")));
const root = join(scratch, "reactor");
const outputs = join(scratch, "outputs");
const captureDir = process.env.LITHE_CAPTURE_DIR;
function capture(name, document) {
  if (!captureDir) return;
  let text = JSON.stringify(document);
  // The real path first: on macOS it extends the temporary path.
  // As JSON text, so Windows backslashes match their escaped form.
  for (const prefix of new Set([realpathSync(root), root])) text = text.replaceAll(JSON.stringify(prefix).slice(1, -1), "${workspace}");
  mkdirSync(captureDir, { recursive: true });
  writeFileSync(join(captureDir, name), text);
}
let failed = false;
const check = async (name, body) => {
  try {
    await report.step(name, body);
  } catch (error) {
    failed = true;
    console.error(error.stack);
  }
};

/**
 * Runs a declared command that writes its document to ${outputFile}, as the
 * model and dependency commands do, and returns the parsed document. Like
 * Lithe, it reads the file only after Maven exits successfully.
 */
async function document(name, declaration, signal, values = {}) {
  assert.equal(declaration.source, "command");
  assert.equal(declaration.output, "outputFile");
  const outputFile = join(outputs, `${name.replaceAll(/[^A-Za-z0-9]+/g, "-")}.json`);
  await run(name, root, declaration.arguments, signal, { ...values, outputFile });
  return JSON.parse(readFileSync(outputFile, "utf8"));
}

/**
 * Starts the packaged build provider the way Lithe does and asks it to prepare runs, then
 * launches the plans it returns with the java on PATH, as Lithe launches them with the
 * project's JDK.
 */
async function prepareRuns(signal) {
  const client = new BackendClient({ jar: join(pluginDir, "lib", "lithe-maven-provider.jar"), args: [], timeoutMs: timeoutMs });
  try {
    await report.step("start the build provider", () => client.initialize(root));
    let operation = 0;
    const request = (target, extra = {}) => client.request("lithe/build/prepareRun", {
      operationID: `run-${++operation}`, buildSystemID: "maven", root: ".", options: {}, optionArguments: [], environment: {},
      tool: { executable: maven }, run: target.tags.includes("spring-boot") ? "spring-boot" : "application",
      project: { name: target.path, path: target.path }, target, userArguments: ["typed"], ...extra,
    });
    const prepare = (target, extra) => report.step(`prepare ${target.name}`, () => request(target, extra));
    const launch = (plan) => {
      assert.deepEqual(plan.executable, { kind: "toolchain", value: "java" });
      return execFileSync("java", plan.arguments, {
        cwd: join(root, plan.workingDirectory), env: { ...process.env, ...plan.environment }, encoding: "utf8", timeout: timeoutMs,
      });
    };

    const service = await prepare({ name: "ServiceApplication", path: "service", tags: ["spring-boot"], properties: { mainClass: "demo.service.ServiceApplication" } }, {
      launchOptions: { execution: "service", runtimeArguments: ["-Dgreeting=hi there"], arguments: ["--port=0"], environment: { LITHE_FIXTURE: "set" } },
    });
    await check("a service run compiles its reactor dependencies and launches its main class from the build root", () => {
      assert.equal(service.workingDirectory, ".");
      assert.deepEqual(service.environment, { LITHE_FIXTURE: "set" });
      const classpath = service.arguments[service.arguments.indexOf("-cp") + 1].split(delimiter);
      assert.equal(classpath[0], join(root, "service", "target", "classes"));
      assert.ok(classpath.includes(join(root, "core", "target", "classes")), "the uninstalled sibling comes from the reactor");
      assert.ok(!classpath.some((entry) => entry.includes("junit")), "test dependencies stay off the runtime classpath");
      assert.equal(launch(service).trim(), "Hello, service greeting=hi there arguments=--port=0,typed env=set");
    });

    const explicitWorkingDir = await prepare({ name: "ServiceApplication", path: "service", tags: ["spring-boot"], properties: { mainClass: "demo.service.ServiceApplication" } }, {
      launchOptions: { execution: "service", runtimeArguments: [], arguments: [], environment: {}, workingDirectory: "service" },
    });
    await check("an explicit launchOptions workingDirectory overrides the build-root default", () => {
      assert.equal(explicitWorkingDir.workingDirectory, "service");
    });

    // Without a main class in the model, the compiled classes decide, as spring-boot-maven-plugin does.
    const discovered = await prepare({ name: "Main", path: "app", tags: ["java-application"], properties: {} });
    await check("a target without a main class runs the one the build compiled", () => {
      assert.equal(discovered.arguments[discovered.arguments.indexOf("-cp") + 2], "demo.app.Main");
      assert.equal(launch(discovered).trim(), "Hello, Maven");
    });

    // Run on Current File: the provider names the class with a main method in one source file,
    // and an entrypoint run prepares it without a target.
    const entrypoints = (file) => report.step(`find the entrypoints of ${file}`, () => client.request("lithe/build/runEntrypoints", {
      operationID: `entrypoints-${++operation}`, buildSystemID: "maven", root: ".", file,
    }));
    const mainEntrypoints = await entrypoints("app/src/main/java/demo/app/Main.java");
    const libraryEntrypoints = await entrypoints("core/src/main/java/demo/core/Greeter.java");
    const testEntrypoints = await entrypoints("core/src/test/java/demo/core/GreeterTest.java");
    await check("a source file with a main method names its class and module", () => {
      assert.deepEqual(mainEntrypoints, { format: "litheRunEntrypoints", version: 0, entrypoints: [
        { run: "main", name: "Main", entrypoint: "demo.app.Main", project: { name: "app", path: "app" } },
      ] });
      assert.deepEqual(libraryEntrypoints.entrypoints, [], "a class without main has no entrypoint");
      assert.deepEqual(testEntrypoints.entrypoints, [], "test sources are not on the runtime classpath");
    });
    const entrypoint = await report.step("prepare the Main entrypoint", () => client.request("lithe/build/prepareRun", {
      operationID: `run-${++operation}`, buildSystemID: "maven", root: ".", options: {}, optionArguments: [], environment: {},
      tool: { executable: maven }, run: "main", project: { name: "app", path: "app" }, userArguments: [],
      launchOptions: { execution: "application", runtimeArguments: [], arguments: [], environment: {}, entrypoint: "demo.app.Main" },
    }));
    await check("an entrypoint run starts the chosen class without a target", () => {
      assert.equal(entrypoint.arguments[entrypoint.arguments.indexOf("-cp") + 2], "demo.app.Main");
      assert.equal(launch(entrypoint).trim(), "Hello, Maven");
    });

    const debug = await prepare({ name: "ServiceApplication", path: "service", tags: ["spring-boot"], properties: { mainClass: "demo.service.ServiceApplication" } }, {
      launchOptions: { execution: "service", runtimeArguments: [], arguments: [], environment: {},
        debug: { adapterID: "dev.example/java", configurationType: "java", target: { kind: "connection", host: "127.0.0.1", port: 51235 } } },
    });
    await check("a debug run suspends the service until the debugger attaches on the host's port", () => {
      assert.ok(debug.arguments.includes("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:51235"));
      assert.ok(debug.arguments.indexOf("-cp") > debug.arguments.findIndex((argument) => argument.startsWith("-agentlib:jdwp")));
    });

    await check("a failed build ends preparation with the provider's error", async () => {
      writeFileSync(join(root, "service", "src", "main", "java", "demo", "service", "Broken.java"), "package demo.service; class Broken {");
      try {
        await assert.rejects(request({ name: "ServiceApplication", path: "service", tags: ["spring-boot"], properties: {} }, {
          launchOptions: { execution: "service", runtimeArguments: [], arguments: [], environment: {} },
        }), (error) => error.response?.data?.pluginErrorCode === "maven.run.buildFailed" || /Maven build failed/.test(error.message));
      } finally {
        rmSync(join(root, "service", "src", "main", "java", "demo", "service", "Broken.java"));
      }
    });
    await check("a Maven command that is not on PATH ends preparation with a clear error", async () => {
      await assert.rejects(request({ name: "Main", path: "app", tags: ["java-application"], properties: {} }, {
        tool: { executable: "lithe-no-such-maven" },
      }), (error) => error.response?.data?.pluginErrorCode === "maven.run.noMaven" || /not on PATH/.test(error.message));
    });
    await report.step("stop the build provider", () => client.close());
  } catch (error) {
    client.kill();
    throw error;
  }
}

async function test(signal) {
  try {
    cpSync(resolve("test", "fixtures", "reactor"), root, { recursive: true });
    mkdirSync(outputs);

    const model = await document("read the reactor model", buildSystem.model, signal);
    capture("model.json", model);
    const byName = (name) => model.projects.find((project) => project.name === name);

    await check("the model lists the reactor and its aggregator tree", () => {
      assert.equal(model.format, "litheBuildModel");
      assert.equal(model.version, 0);
      assert.deepEqual(model.projects.map((project) => project.name), ["reactor", "core", "app", "service"]);
      assert.equal(byName("reactor").displayName, "Demo Reactor");
      assert.equal(byName("core").parent, byName("reactor").path);
      assert.deepEqual(Object.fromEntries(["groupId","artifactId","version","packaging"].map(key=>[key,byName("app").properties[key]])), {
        groupId: "demo",
        artifactId: "app",
        version: "1.0.0-SNAPSHOT",
        packaging: "jar",
      });
    });

    await check("profiles become choices of the Profiles option", () => {
      assert.deepEqual(model.optionValues.profiles, ["ci", "release"]);
      assert.equal(model.optionDefaults.profiles, undefined);
      assert.deepEqual(model.optionActiveChoices, { profiles: [] });
      assert.deepEqual(model.optionExcludePrefixes, { profiles: "-" });
    });

    const profileRoot = join(scratch, "profiles-reactor");
    const emptySettings = join(scratch, "profile-empty-settings.xml");
    writeFileSync(emptySettings, "<settings/>");
    const readProfileModel = async (tag, profileArg, settings = emptySettings) => {
      const outputFile = join(outputs, `profiles-${tag}.json`);
      const extra = profileArg ? ["-P", profileArg] : [];
      const argv = [
        ...expand(buildSystem.invocation.globalArguments, { outputFile }),
        "-gs", emptySettings, "-s", settings,
        ...extra,
        ...expand(buildSystem.model.arguments, { outputFile }),
      ];
      await runCommand(maven, argv, {
        cwd: profileRoot, env: { ...process.env, MAVEN_ARGS: "" },
        stdio: ["ignore", "ignore", "inherit"], timeoutMs, signal,
      });
      return JSON.parse(readFileSync(outputFile, "utf8"));
    };

    await check("default-active profiles stay reported across -P switches and deactivations", async () => {
      mkdirSync(join(profileRoot, "dev-module"), { recursive: true });
      mkdirSync(join(profileRoot, "prod-module"), { recursive: true });
      writeFileSync(join(profileRoot, "pom.xml"), [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<project xmlns="http://maven.apache.org/POM/4.0.0">',
        "  <modelVersion>4.0.0</modelVersion>",
        "  <groupId>demo</groupId>",
        "  <artifactId>profiles-reactor</artifactId>",
        "  <version>1.0.0-SNAPSHOT</version>",
        "  <packaging>pom</packaging>",
        "  <profiles>",
        "    <profile>",
        "      <id>dev</id>",
        "      <activation><activeByDefault>true</activeByDefault></activation>",
        "      <modules><module>dev-module</module></modules>",
        "    </profile>",
        "    <profile>",
        "      <id>prod</id>",
        "      <modules><module>prod-module</module></modules>",
        "    </profile>",
        "  </profiles>",
        "</project>",
      ].join("\n"));
      for (const name of ["dev-module", "prod-module"]) {
        writeFileSync(join(profileRoot, name, "pom.xml"), [
          '<?xml version="1.0" encoding="UTF-8"?>',
          '<project xmlns="http://maven.apache.org/POM/4.0.0">',
          "  <modelVersion>4.0.0</modelVersion>",
          "  <parent><groupId>demo</groupId><artifactId>profiles-reactor</artifactId><version>1.0.0-SNAPSHOT</version></parent>",
          `  <artifactId>${name}</artifactId>`,
          "</project>",
        ].join("\n"));
      }
      const initial = await readProfileModel("default", "");
      assert.deepEqual(initial.optionValues.profiles, ["dev", "prod"]);
      assert.deepEqual(initial.optionDefaults.profiles, ["dev"]);
      assert.deepEqual(initial.optionActiveChoices, { profiles: ["dev"] });
      assert.deepEqual(initial.optionExcludePrefixes, { profiles: "-" });
      assert.deepEqual(initial.projects.map((project) => project.name), ["profiles-reactor", "dev-module"]);

      const switched = await readProfileModel("switched", "-dev,prod");
      assert.deepEqual(switched.optionDefaults.profiles, ["dev"]);
      assert.deepEqual(switched.optionActiveChoices, { profiles: ["prod"] });
      assert.deepEqual(switched.projects.map((project) => project.name), ["profiles-reactor", "prod-module"]);

      const cleared = await readProfileModel("cleared", "-dev");
      assert.deepEqual(cleared.optionDefaults.profiles, ["dev"]);
      assert.deepEqual(cleared.optionActiveChoices, { profiles: [] });
      assert.deepEqual(cleared.projects.map((project) => project.name), ["profiles-reactor"]);

      const settings = join(profileRoot, "settings.xml");
      writeFileSync(settings, "<settings><activeProfiles><activeProfile>dev</activeProfile></activeProfiles></settings>");
      const fromBothSources = await readProfileModel("settings-and-cli", "prod", settings);
      assert.deepEqual(fromBothSources.optionActiveChoices, { profiles: ["dev", "prod"] });
      assert.deepEqual(fromBothSources.projects.map((project) => project.name),
        ["profiles-reactor", "dev-module", "prod-module"]);
    });

    await check("the model reports the settings file and local repository Maven uses", async () => {
      // Shown greyed until Override, as IDEA's Maven settings: the user settings file is always
      // ~/.m2/settings.xml, and the repository is the one the settings read name, else ~/.m2/repository.
      const m2 = join(homedir(), ".m2");
      const plain = await readProfileModel("paths-default", "");
      assert.deepEqual(plain.optionDefaults.settingsFile, [join(m2, "settings.xml")]);
      assert.deepEqual(plain.optionDefaults.localRepository, [join(m2, "repository")]);
      const repository = join(scratch, "settings-repository");
      const settings = join(scratch, "paths-settings.xml");
      writeFileSync(settings, `<settings><localRepository>${repository}</localRepository></settings>`);
      const named = await readProfileModel("paths-settings", "", settings);
      assert.deepEqual(named.optionDefaults.settingsFile, [join(m2, "settings.xml")]);
      assert.deepEqual(named.optionDefaults.localRepository, [repository]);
    });

    await check("packaging activation is reflected in baseline defaults and can be disabled", async () => {
      const pom = join(profileRoot, "pom.xml");
      const original = readFileSync(pom, "utf8");
      writeFileSync(pom, original.replace("<activeByDefault>true</activeByDefault>",
        "<property><name>packaging</name><value>pom</value></property>"));
      try {
        const baseline = await readProfileModel("packaging", "");
        const active = baseline.projects.some((project) => project.name === "dev-module") ? ["dev"] : [];
        assert.deepEqual(baseline.optionDefaults?.profiles ?? [], active);
        assert.deepEqual(baseline.optionActiveChoices?.profiles, active);
        const disabled = await readProfileModel("packaging-disabled", "-dev");
        assert.deepEqual(disabled.optionDefaults?.profiles ?? [], active);
        assert.deepEqual(disabled.optionActiveChoices?.profiles, []);
        assert.equal(disabled.projects.some((project) => project.name === "dev-module"), false);

        // A parent Profile evaluates packaging in the child's model context.
        writeFileSync(pom, original
          .replace("<profiles>", "<modules><module>prod-module</module></modules><profiles>")
          .replace("<activeByDefault>true</activeByDefault>",
            "<property><name>packaging</name><value>jar</value></property>")
          .replace("<modules><module>dev-module</module></modules>", ""));
        const inherited = await readProfileModel("packaging-parent", "");
        assert.deepEqual(inherited.optionDefaults?.profiles ?? [], active);
        assert.deepEqual(inherited.optionActiveChoices?.profiles, active);
      } finally {
        writeFileSync(pom, original);
      }
    });

    await check("project Maven config keeps its active and inactive profiles in the baseline", async () => {
      mkdirSync(join(profileRoot, ".mvn"));
      writeFileSync(join(profileRoot, ".mvn", "maven.config"), "--activate-profiles\n+prod\n-P\n!dev\n");
      const baseline = await readProfileModel("project-config", "");
      assert.deepEqual(baseline.optionDefaults?.profiles, ["prod"]);
      assert.deepEqual(baseline.optionActiveChoices?.profiles, ["prod"]);
      assert.deepEqual(baseline.projects.map((project) => project.name), ["profiles-reactor", "prod-module"]);

      const cleared = await readProfileModel("project-config-disabled", "-prod");
      assert.deepEqual(cleared.optionDefaults?.profiles, ["prod"]);
      assert.deepEqual(cleared.optionActiveChoices?.profiles, []);
      assert.deepEqual(cleared.projects.map((project) => project.name), ["profiles-reactor"]);
    });

    await check("plugin goals are model tasks filed under Plugins", () => {
      const compile = model.tasks.find((task) => task.project === byName("core").path && task.name === "compiler:compile");
      assert.ok(compile, "core has compiler:compile");
      assert.equal(compile.template, "plugin-goal");
      assert.deepEqual(compile.category, ["Plugins", "compiler"]);
      assert.match(compile.properties.goal, /^org\.apache\.maven\.plugins:maven-compiler-plugin:[^:]+:compile$/);
      const templates = new Set(buildSystem.tasks.filter((task) => task.fromModel).map((task) => task.id));
      assert.ok(model.tasks.every((task) => templates.has(task.template)), "every task names a fromModel template");
    });

    await check("jar modules have content roots and the pom aggregator has none", () => {
      const core = byName("core").path;
      const kinds = model.contentRoots.filter((entry) => entry.project === core).map((entry) => [entry.kind, entry.path]);
      assert.deepEqual(kinds.slice(0, 4), [
        ["source", join(core, "src", "main", "java")],
        ["resource", join(core, "src", "main", "resources")],
        ["testSource", join(core, "src", "test", "java")],
        ["testResource", join(core, "src", "test", "resources")],
      ]);
      assert.ok(!model.contentRoots.some((entry) => entry.project === byName("reactor").path));
    });

    await check("applications become the targets the run declarations select", () => {
      const targets = model.targets.map((target) => [target.project, target.name, target.tags, target.properties]);
      assert.deepEqual(targets, [
        [byName("app").path, "Main", ["java-application"], { mainClass: "demo.app.Main" }],
        [byName("service").path, "ServiceApplication", ["spring-boot"], { mainClass: "demo.service.ServiceApplication" }],
      ]);
      const runs = Object.fromEntries(buildSystem.run.map((run) => [run.id, run]));
      assert.deepEqual(runs["spring-boot"].fromTargets.tags, ["spring-boot"]);
      assert.equal(runs["spring-boot"].execution, "service");
      assert.deepEqual(runs.application.fromTargets.tags, ["java-application"]);
      assert.ok(buildSystem.run.every((run) => run.source === "provider"));
      assert.equal(runs.main.entrypoints, true, "Run on Current File starts any main class through the main run");
      assert.equal(manifest.requires.buildProviders, 1);
    });

    const projectDependencies = await document("read project dependencies", buildSystem.dependencies, signal, { project: "app" });
    capture("app-dependencies.json", projectDependencies);
    await check("a project's tree resolves an uninstalled sibling from the reactor", () => {
      const tree = projectDependencies;
      assert.equal(tree.format, "litheDependencies");
      assert.equal(tree.version, 0);
      assert.deepEqual(tree.dependencies.map((node) => [node.group, node.name, node.scope]), [["demo", "core", "compile"]]);
    });

    const workspaceDependencies = await document("read workspace dependencies", buildSystem.dependencies, signal);
    capture("dependencies.json", workspaceDependencies);
    await check("the workspace tree has one node per project with transitive children", () => {
      const tree = workspaceDependencies;
      assert.deepEqual(tree.dependencies.map((node) => node.name), ["reactor", "core", "app", "service"]);
      const junit = tree.dependencies[1].children[0];
      assert.deepEqual([junit.name, junit.version, junit.scope], ["junit", "4.13.2", "test"]);
      assert.equal(junit.children[0].name, "hamcrest-core");
    });

    await check("reading the model and dependencies builds nothing", () => {
      for (const directory of ["target", "core/target", "app/target", "service/target"]) {
        assert.ok(!existsSync(join(root, directory)), `${directory} was not created`);
      }
    });

    await prepareRuns(signal);

    await run("run the filtered Maven test suite", root, buildSystem.test.arguments, signal, { project: "core", suite: "demo.core.GreeterTest" });
    await check("a filtered test run writes the report the results glob reads", () => {
      const report = readFileSync(join(root, "core", "target", "surefire-reports", "TEST-demo.core.GreeterTest.xml"), "utf8");
      assert.match(report, /<testsuite[^>]*name="demo\.core\.GreeterTest"[^>]*tests="2"/);
    });

    await check("without Lithe's properties the extension does nothing", async () => {
      rmSync(join(root, "core", "target"), { recursive: true, force: true });
      await runCommand(maven, ["-B", "-ntp", "-q", `-Dmaven.ext.class.path=${pluginDir}/lib/lithe-maven-extension.jar`, "validate"], {
        cwd: root, stdio: ["ignore", "ignore", "inherit"], timeoutMs, signal,
      });
    });

    await verifyExtendedBootAndProvided(signal);

    await check("the manifest declares Maven task run configurations, before-launch tasks and setting regions", () => {
      assert.equal(manifest.requires?.runConfigurationTypes, 0);
      assert.equal(manifest.requires?.beforeLaunchTasks, 0);

      const mavenConfig = (buildSystem.runConfigurationTypes ?? []).find((item) => item.id === "maven");
      assert.ok(mavenConfig, "maven runConfigurationType must exist");
      assert.equal(mavenConfig.execution, "task");
      assert.deepEqual(
        mavenConfig.fields.map((field) => field.id),
        ["goals", "profiles", "skipTests", "offline"],
      );

      const goalTask = (buildSystem.beforeLaunchTasks ?? []).find((item) => item.id === "goal");
      assert.ok(goalTask, "goal beforeLaunchTask must exist");
      assert.deepEqual(goalTask.fields.map((field) => field.id), ["goals"]);

      const regions = Object.fromEntries((manifest.settingRegions ?? []).map((region) => [region.id, region]));
      assert.equal(regions.build?.page?.buildSystemID, "maven");
      assert.ok(Array.isArray(regions.build?.keywords) && regions.build.keywords.length > 0);
      assert.equal(regions.compiler?.page?.compilerID, "maven");
      assert.ok(Array.isArray(regions.compiler?.keywords) && regions.compiler.keywords.length > 0);

      const zh = JSON.parse(readFileSync(join(pluginDir, "l10n", "zh-Hans.json"), "utf8"));
      assert.ok(zh.settings?.mavenExecutable?.title, "zh-Hans settings.mavenExecutable");
      assert.ok(zh.sections?.Maven, "zh-Hans sections.Maven");
      assert.ok(Array.isArray(zh.settingRegions?.build?.keywords) && zh.settingRegions.build.keywords.length > 0);
      assert.ok(Array.isArray(zh.settingRegions?.compiler?.keywords) && zh.settingRegions.compiler.keywords.length > 0);
      assert.ok(zh.runConfigurationTypes?.maven?.maven?.displayName, "zh-Hans runConfigurationTypes.maven.maven");
      assert.ok(zh.beforeLaunchTasks?.maven?.goal?.displayName, "zh-Hans beforeLaunchTasks.maven.goal");
    });
  } catch (error) {
    failed = true;
    report.failure("integration lane failed", error);
    console.error(error.stack);
  } finally {
    try { await report.step("remove the scratch reactor", () => rmSync(scratch, { recursive: true, force: true })); }
    finally { report.write(); }
  }
  process.exitCode = failed ? 1 : 0;
}

/**
 * Covers composed/custom @interface Spring Boot annotations across reactor modules,
 * provided-scope runtime classpath handling, and Java 21+/25 main() entrypoint forms.
 */
async function verifyExtendedBootAndProvided(signal) {
  const extRoot = join(scratch, "extended-reactor");
  const write = (rel, content) => {
    const file = join(extRoot, rel);
    mkdirSync(join(file, ".."), { recursive: true });
    writeFileSync(file, content);
  };
  write("pom.xml", [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<project xmlns="http://maven.apache.org/POM/4.0.0">',
    "  <modelVersion>4.0.0</modelVersion>",
    "  <groupId>demo.ext</groupId>",
    "  <artifactId>extended-reactor</artifactId>",
    "  <version>1.0.0-SNAPSHOT</version>",
    "  <packaging>pom</packaging>",
    "  <modules>",
    "    <module>provided-lib</module>",
    "    <module>meta</module>",
    "    <module>apps</module>",
    "    <module>custom-boot</module>",
    "    <module>plain</module>",
    "  </modules>",
    "  <properties>",
    "    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>",
    "    <maven.compiler.release>8</maven.compiler.release>",
    "  </properties>",
    "  <build><pluginManagement><plugins>",
    "    <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.13.0</version></plugin>",
    "  </plugins></pluginManagement></build>",
    "</project>",
  ].join("\n"));
  for (const [mod, deps] of [
    ["provided-lib", ""],
    ["meta", ""],
    ["apps", [
      "<dependency><groupId>demo.ext</groupId><artifactId>meta</artifactId><version>1.0.0-SNAPSHOT</version></dependency>",
      "<dependency><groupId>demo.ext</groupId><artifactId>provided-lib</artifactId><version>1.0.0-SNAPSHOT</version><scope>provided</scope></dependency>",
    ].join("\n    ")],
    ["custom-boot", "<dependency><groupId>demo.ext</groupId><artifactId>meta</artifactId><version>1.0.0-SNAPSHOT</version></dependency>"],
    ["plain", "<dependency><groupId>demo.ext</groupId><artifactId>provided-lib</artifactId><version>1.0.0-SNAPSHOT</version><scope>provided</scope></dependency>"],
  ]) {
    write(`${mod}/pom.xml`, [
      '<?xml version="1.0" encoding="UTF-8"?>',
      '<project xmlns="http://maven.apache.org/POM/4.0.0">',
      "  <modelVersion>4.0.0</modelVersion>",
      "  <parent><groupId>demo.ext</groupId><artifactId>extended-reactor</artifactId><version>1.0.0-SNAPSHOT</version></parent>",
      `  <artifactId>${mod}</artifactId>`,
      deps ? `  <dependencies>\n    ${deps}\n  </dependencies>` : "",
      "</project>",
    ].join("\n"));
  }
  write("provided-lib/src/main/java/demo/provided/ProvidedBanner.java",
    "package demo.provided;\npublic class ProvidedBanner { public static String text() { return \"provided-ok\"; } }\n");
  write("meta/src/main/java/org/springframework/boot/autoconfigure/SpringBootApplication.java",
    "package org.springframework.boot.autoconfigure;\nimport java.lang.annotation.*;\n@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface SpringBootApplication {}\n");
  write("meta/src/main/java/org/springframework/boot/autoconfigure/EnableAutoConfiguration.java",
    "package org.springframework.boot.autoconfigure;\nimport java.lang.annotation.*;\n@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface EnableAutoConfiguration {}\n");
  write("meta/src/main/java/org/springframework/cloud/client/SpringCloudApplication.java",
    "package org.springframework.cloud.client;\nimport java.lang.annotation.*;\n@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.RUNTIME)\npublic @interface SpringCloudApplication {}\n");
  write("meta/src/main/java/demo/meta/MyBootApp.java",
    "package demo.meta;\nimport java.lang.annotation.*;\nimport org.springframework.boot.autoconfigure.SpringBootApplication;\n@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.CLASS)\n@SpringBootApplication\npublic @interface MyBootApp {}\n");
  write("meta/src/main/java/demo/meta/TransitiveBootApp.java",
    "package demo.meta;\nimport java.lang.annotation.*;\n@Target(ElementType.TYPE)\n@Retention(RetentionPolicy.CLASS)\n@MyBootApp\npublic @interface TransitiveBootApp {}\n");
  write("apps/src/main/java/demo/apps/CloudApp.java",
    "package demo.apps;\nimport org.springframework.cloud.client.SpringCloudApplication;\n@SpringCloudApplication\npublic class CloudApp {\n  public static void main(String[] args) {}\n}\n");
  write("apps/src/main/java/demo/apps/AutoBootApp.java",
    "package demo.apps;\nimport org.springframework.boot.autoconfigure.EnableAutoConfiguration;\n@EnableAutoConfiguration\npublic class AutoBootApp {\n  void main() { System.out.println(\"auto-main\"); }\n}\n");
  write("apps/src/main/java/demo/apps/HelperAutoConfig.java",
    "package demo.apps;\nimport org.springframework.boot.autoconfigure.EnableAutoConfiguration;\n@EnableAutoConfiguration\npublic class HelperAutoConfig {}\n");
  write("apps/src/main/java/demo/apps/CustomMetaApp.java",
    "package demo.apps;\nimport demo.meta.TransitiveBootApp;\nimport demo.provided.ProvidedBanner;\n@TransitiveBootApp\npublic class CustomMetaApp {\n  public static void main(String[] args) { System.out.println(ProvidedBanner.text()); }\n}\n");
  write("plain/src/main/java/demo/plain/InstanceVoidMain.java",
    "package demo.plain;\npublic class InstanceVoidMain {\n  void main() {}\n}\n");
  write("custom-boot/src/main/java/demo/custom/HelperMain.java",
    "package demo.custom;\nimport demo.meta.MyBootApp;\n@MyBootApp\npublic class HelperMain {\n  public static void main(String[] args) {}\n}\n");
  write("custom-boot/src/main/java/demo/custom/PlainExtraMain.java",
    "package demo.custom;\npublic class PlainExtraMain {\n  public static void main(String[] args) {}\n}\n");
  write("plain/src/main/java/demo/plain/PrivateMain.java",
    "package demo.plain;\npublic class PrivateMain {\n  private static void main(String[] args) {}\n}\n");
  // Compact source file (no package/class header) for source-level entrypoint detection only (not compiled on Java 8).
  const compactFile = "plain/src/main/java/CompactMain.java";
  write(compactFile, "void main() {\n  System.out.println(\"compact\");\n}\n");

  const extOut = join(outputs, "extended-model.json");
  await run("read extended boot model", extRoot, buildSystem.model.arguments, signal, { outputFile: extOut });
  const extModel = JSON.parse(readFileSync(extOut, "utf8"));
  const extByName = (name) => extModel.projects.find((project) => project.name === name).path;
  await check("composed and cross-module @interface Boot annotations become spring-boot targets", () => {
    const actual = extModel.targets.map((target) => [target.project, target.properties.mainClass, target.tags]);
    assert.deepEqual(actual, [
      [extByName("apps"), "demo.apps.AutoBootApp", ["spring-boot"]],
      [extByName("apps"), "demo.apps.CloudApp", ["spring-boot"]],
      [extByName("apps"), "demo.apps.CustomMetaApp", ["spring-boot"]],
      [extByName("custom-boot"), "demo.custom.HelperMain", ["spring-boot"]],
    ]);
  });

  const client = new BackendClient({ jar: join(pluginDir, "lib", "lithe-maven-provider.jar"), args: [], timeoutMs: timeoutMs });
  try {
    await report.step("start provider for extended boot and Java 21 entrypoints", () => client.initialize(extRoot));
    let operation = 0;
    const entrypoints = (file) => client.request("lithe/build/runEntrypoints", {
      operationID: `ext-entry-${++operation}`, buildSystemID: "maven", root: ".", file,
    });
    const instanceEntry = await entrypoints("plain/src/main/java/demo/plain/InstanceVoidMain.java");
    const compactEntry = await entrypoints(compactFile);
    const privateEntry = await entrypoints("plain/src/main/java/demo/plain/PrivateMain.java");
    await check("runEntrypoints accepts Java 21+ void main() and compact sources while rejecting private main()", () => {
      assert.deepEqual(instanceEntry.entrypoints, [
        { run: "main", name: "InstanceVoidMain", entrypoint: "demo.plain.InstanceVoidMain", project: { name: "plain", path: "plain" } },
      ]);
      assert.deepEqual(compactEntry.entrypoints, [
        { run: "main", name: "CompactMain", entrypoint: "CompactMain", project: { name: "plain", path: "plain" } },
      ]);
      assert.deepEqual(privateEntry.entrypoints, []);
    });
    // Remove compact source before invoking javac/mvn so Java 8 CI compiles cleanly.
    rmSync(join(extRoot, compactFile));

    const bootProvided = await client.request("lithe/build/prepareRun", {
      operationID: `ext-run-${++operation}`, buildSystemID: "maven", root: ".", options: {}, optionArguments: [], environment: {},
      tool: { executable: maven }, run: "spring-boot", project: { name: "apps", path: "apps" },
      target: { name: "CustomMetaApp", path: "apps", tags: ["spring-boot"], properties: { mainClass: "demo.apps.CustomMetaApp" } },
      userArguments: [],
    });
    const bootNoProvided = await client.request("lithe/build/prepareRun", {
      operationID: `ext-run-${++operation}`, buildSystemID: "maven", root: ".", options: {},
      optionArguments: ["-Dlithe.runIncludeProvided=false"], environment: {},
      tool: { executable: maven }, run: "spring-boot", project: { name: "apps", path: "apps" },
      target: { name: "CustomMetaApp", path: "apps", tags: ["spring-boot"], properties: { mainClass: "demo.apps.CustomMetaApp" } },
      userArguments: [],
    });
    const customBootDiscovered = await client.request("lithe/build/prepareRun", {
      operationID: `ext-run-${++operation}`, buildSystemID: "maven", root: ".", options: {}, optionArguments: [], environment: {},
      tool: { executable: maven }, run: "spring-boot", project: { name: "custom-boot", path: "custom-boot" },
      target: { name: "HelperMain", path: "custom-boot", tags: ["spring-boot"], properties: {} },
      userArguments: [],
    });
    const plainPrepared = await client.request("lithe/build/prepareRun", {
      operationID: `ext-run-${++operation}`, buildSystemID: "maven", root: ".", options: {}, optionArguments: [], environment: {},
      tool: { executable: maven }, run: "application", project: { name: "plain", path: "plain" },
      target: { name: "InstanceVoidMain", path: "plain", tags: ["java-application"], properties: {} },
      userArguments: [],
    });
    await check("Spring Boot includes provided dependencies by default, respects override, and bytecode detects custom Boot & InstanceVoidMain", () => {
      const bootCp = bootProvided.arguments[bootProvided.arguments.indexOf("-cp") + 1].split(delimiter);
      const providedClasses = join(extRoot, "provided-lib", "target", "classes");
      assert.ok(bootCp.includes(providedClasses), "spring-boot includes provided-scope reactor classes");
      const output = execFileSync("java", bootProvided.arguments, { cwd: join(extRoot, bootProvided.workingDirectory), encoding: "utf8", timeout: timeoutMs });
      assert.equal(output.trim(), "provided-ok");

      const suppressedCp = bootNoProvided.arguments[bootNoProvided.arguments.indexOf("-cp") + 1].split(delimiter);
      assert.ok(!suppressedCp.includes(providedClasses), "-Dlithe.runIncludeProvided=false excludes provided scope");

      const discoveredMain = customBootDiscovered.arguments[customBootDiscovered.arguments.indexOf("-cp") + 2];
      assert.equal(discoveredMain, "demo.custom.HelperMain", "bytecode custom boot descriptor disambiguates HelperMain over PlainExtraMain");

      const plainCp = plainPrepared.arguments[plainPrepared.arguments.indexOf("-cp") + 1].split(delimiter);
      assert.ok(!plainCp.includes(providedClasses), "plain jar application excludes provided scope");
      assert.equal(plainPrepared.arguments[plainPrepared.arguments.indexOf("-cp") + 2], "demo.plain.InstanceVoidMain");
    });
    await report.step("stop extended boot provider", () => client.close());
  } catch (error) {
    client.kill();
    throw error;
  }
}
await withInterrupts(test);
