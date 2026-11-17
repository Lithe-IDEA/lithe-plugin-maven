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
import { tmpdir } from "node:os";
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
    await check("a service run compiles its reactor dependencies and launches its main class", () => {
      assert.equal(service.workingDirectory, "service");
      assert.deepEqual(service.environment, { LITHE_FIXTURE: "set" });
      const classpath = service.arguments[service.arguments.indexOf("-cp") + 1].split(delimiter);
      assert.equal(classpath[0], join(root, "service", "target", "classes"));
      assert.ok(classpath.includes(join(root, "core", "target", "classes")), "the uninstalled sibling comes from the reactor");
      assert.ok(!classpath.some((entry) => entry.includes("junit")), "test dependencies stay off the runtime classpath");
      assert.equal(launch(service).trim(), "Hello, service greeting=hi there arguments=--port=0,typed env=set");
    });

    // Without a main class in the model, the compiled classes decide, as spring-boot-maven-plugin does.
    const discovered = await prepare({ name: "Main", path: "app", tags: ["java-application"], properties: {} });
    await check("a target without a main class runs the one the build compiled", () => {
      assert.equal(discovered.arguments[discovered.arguments.indexOf("-cp") + 2], "demo.app.Main");
      assert.equal(launch(discovered).trim(), "Hello, Maven");
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
await withInterrupts(test);
