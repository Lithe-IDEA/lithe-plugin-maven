#!/usr/bin/env node
// Runs the commands plugin.json declares against test/fixtures/reactor with
// a real Maven, and checks the documents the extension writes. Lithe's own
// tests decode these documents; this script makes sure Maven produces them.
//
// Run scripts/package.mjs first. MVN selects the Maven executable (default
// `mvn`), so CI can also check the oldest supported Maven.
//
// Usage: node scripts/test.mjs

import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
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
function expand(list, { outputFile, project, suite }) {
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

function run(root, list, values = {}) {
  const argv = [...expand(buildSystem.invocation.globalArguments, values), ...expand(list, values)];
  execFileSync(maven, argv, { cwd: root, stdio: ["ignore", "ignore", "inherit"] });
}

const scratch = mkdtempSync(join(tmpdir(), "lithe-maven-test-"));
const root = join(scratch, "reactor");
let failed = false;
const check = (name, body) => {
  try {
    body();
    console.log(`✓ ${name}`);
  } catch (error) {
    failed = true;
    console.error(`✗ ${name}\n${error.stack}`);
  }
};

try {
  cpSync(resolve("test", "fixtures", "reactor"), root, { recursive: true });

  const modelFile = join(scratch, "model.json");
  run(root, buildSystem.model.arguments, { outputFile: modelFile });
  const model = JSON.parse(readFileSync(modelFile, "utf8"));
  const byName = (name) => model.projects.find((project) => project.name === name);

  check("the model lists the reactor and its aggregator tree", () => {
    assert.equal(model.format, "litheBuildModel");
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

  check("profiles become choices of the Profiles option", () => {
    assert.deepEqual(model.optionValues.profiles, ["ci", "release"]);
  });

  check("plugin goals are model tasks filed under Plugins", () => {
    const compile = model.tasks.find((task) => task.project === byName("core").path && task.name === "compiler:compile");
    assert.ok(compile, "core has compiler:compile");
    assert.equal(compile.template, "plugin-goal");
    assert.deepEqual(compile.category, ["Plugins", "compiler"]);
    assert.match(compile.properties.goal, /^org\.apache\.maven\.plugins:maven-compiler-plugin:[^:]+:compile$/);
    const templates = new Set(buildSystem.tasks.filter((task) => task.fromModel).map((task) => task.id));
    assert.ok(model.tasks.every((task) => templates.has(task.template)), "every task names a fromModel template");
  });

  check("jar modules have content roots and the pom aggregator has none", () => {
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

  const projectDependencies = join(scratch, "app-dependencies.json");
  run(root, buildSystem.dependencies.arguments, { outputFile: projectDependencies, project: "app" });
  check("a project's tree resolves an uninstalled sibling from the reactor", () => {
    const tree = JSON.parse(readFileSync(projectDependencies, "utf8"));
    assert.equal(tree.format, "litheDependencies");
    assert.deepEqual(tree.dependencies.map((node) => [node.group, node.name, node.scope]), [["demo", "core", "compile"]]);
  });

  const workspaceDependencies = join(scratch, "dependencies.json");
  run(root, buildSystem.dependencies.arguments, { outputFile: workspaceDependencies });
  check("the workspace tree has one node per project with transitive children", () => {
    const tree = JSON.parse(readFileSync(workspaceDependencies, "utf8"));
    assert.deepEqual(tree.dependencies.map((node) => node.name), ["reactor", "core", "app"]);
    const junit = tree.dependencies[1].children[0];
    assert.deepEqual([junit.name, junit.version, junit.scope], ["junit", "4.13.2", "test"]);
    assert.equal(junit.children[0].name, "hamcrest-core");
  });

  check("reading the model and dependencies builds nothing", () => {
    for (const directory of ["target", "core/target", "app/target"]) {
      assert.ok(!existsSync(join(root, directory)), `${directory} was not created`);
    }
  });

  run(root, buildSystem.test.arguments, { project: "core", suite: "demo.core.GreeterTest" });
  check("a filtered test run writes the report the results glob reads", () => {
    const report = readFileSync(join(root, "core", "target", "surefire-reports", "TEST-demo.core.GreeterTest.xml"), "utf8");
    assert.match(report, /<testsuite[^>]*name="demo\.core\.GreeterTest"[^>]*tests="2"/);
  });

  check("without Lithe's properties the extension does nothing", () => {
    rmSync(join(root, "core", "target"), { recursive: true, force: true });
    execFileSync(maven, ["-B", "-ntp", "-q", `-Dmaven.ext.class.path=${pluginDir}/lib/lithe-maven-extension.jar`, "validate"], {
      cwd: root,
      stdio: ["ignore", "ignore", "inherit"],
    });
  });
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
process.exit(failed ? 1 : 0);
