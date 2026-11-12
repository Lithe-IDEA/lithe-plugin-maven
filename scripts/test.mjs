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
// LITHE_CAPTURE_DIR=<dir> also copies the documents there, with the
// scratch workspace written as `${workspace}`, for Lithe's Core tests.
//
// Usage: node scripts/test.mjs

import assert from "node:assert/strict";
import { mavenTimeout, runCommand, withInterrupts } from "./process.mjs";
import { createReport } from "./report.mjs";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const PACKAGE_DIR = "io.github.lithe-idea.maven-build";
const pluginDir = resolve("dist", "package", PACKAGE_DIR);
const maven = process.env.MVN ?? "mvn";
if (!existsSync(join(pluginDir, "lib", "lithe-maven-extension.jar"))) {
  console.error("✗ dist/package is missing; run node scripts/package.mjs first");
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

const scratch = realpathSync(mkdtempSync(join(tmpdir(), "lithe-maven-test-")));
const root = join(scratch, "reactor");
const outputs = join(scratch, "outputs");
const captureDir = process.env.LITHE_CAPTURE_DIR;
function capture(name, document) {
  if (!captureDir) return;
  let text = JSON.stringify(document);
  // The real path first: on macOS it extends the temporary path.
  for (const prefix of new Set([realpathSync(root), root])) text = text.replaceAll(prefix, "${workspace}");
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
      assert.deepEqual(model.projects.map((project) => project.name), ["reactor", "core", "app"]);
      assert.equal(byName("reactor").displayName, "Demo Reactor");
      assert.equal(byName("core").parent, byName("reactor").path);
      assert.deepEqual(byName("app").properties, {
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
      assert.deepEqual(tree.dependencies.map((node) => node.name), ["reactor", "core", "app"]);
      const junit = tree.dependencies[1].children[0];
      assert.deepEqual([junit.name, junit.version, junit.scope], ["junit", "4.13.2", "test"]);
      assert.equal(junit.children[0].name, "hamcrest-core");
    });

    await check("reading the model and dependencies builds nothing", () => {
      for (const directory of ["target", "core/target", "app/target"]) {
        assert.ok(!existsSync(join(root, directory)), `${directory} was not created`);
      }
    });

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
