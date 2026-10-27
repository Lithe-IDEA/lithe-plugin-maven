#!/usr/bin/env node
// Runs test/fixtures/reactor with a real Maven: the model and dependency
// trees and native tests through the packaged build provider
// (lib/lithe-maven-backend.jar, which runs Maven with the extension). It checks the documents Lithe receives; Lithe's own tests decode
// them, this script makes sure Maven produces them. The provider's caching is
// covered by scripts/test-backend.mjs with a fake Maven.
//
// Run scripts/package.mjs first. MVN selects the Maven executable (default
// `mvn`), so CI can also check the oldest supported Maven; LITHE_TEST_JAVA
// the Java that runs the provider (default `java`).
//
// LITHE_CAPTURE_DIR=<dir> also copies the documents there, with the
// scratch workspace written as `${workspace}`, for Lithe's Core tests.
//
// Usage: node scripts/test.mjs

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { BackendClient, bounded } from "./backend-client.mjs";
import { mavenTimeout, runCommand, withInterrupts } from "./process.mjs";
import { createReport } from "./report.mjs";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const PACKAGE_DIR = "io.github.lithe-idea.maven-build";
const pluginDir = resolve(process.env.LITHE_TEST_PACKAGE ?? resolve("dist", "package", PACKAGE_DIR));
const maven = process.env.MVN ?? "mvn";
if (!existsSync(join(pluginDir, "lib", "lithe-maven-extension.jar")) || !existsSync(join(pluginDir, "lib", "lithe-maven-backend.jar"))) {
  console.error("✗ dist/package is missing; run node scripts/package.mjs first");
  process.exit(1);
}
const manifest = JSON.parse(readFileSync(join(pluginDir, "plugin.json"), "utf8"));
const buildSystem = manifest.buildSystems[0];

const report = createReport("maven-integration");
const timeoutMs = mavenTimeout();

const scratch = realpathSync(mkdtempSync(join(tmpdir(), "lithe-maven-test-")));
const root = join(scratch, "reactor");
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

/** The executable Lithe would pass as tool.executable: MVN, or `mvn` on PATH. */
function mavenExecutable() {
  if (maven.includes("/")) return resolve(maven);
  const found = spawnSync("/bin/sh", ["-c", 'command -v "$0"', maven], { encoding: "utf8" }).stdout.trim();
  if (!found) throw new Error(`${maven} is not on PATH`);
  return found;
}

async function test(signal) {
  let backend;
  const stopBackend = () => backend?.kill();
  signal.addEventListener("abort", stopBackend, { once: true });
  try {
    cpSync(resolve("test", "fixtures", "reactor"), root, { recursive: true });

    backend = new BackendClient({
      java: process.env.LITHE_TEST_JAVA ?? "java",
      jar: join(pluginDir, "lib", "lithe-maven-backend.jar"),
      state: join(scratch, "state"),
      timeoutMs,
    });
    await report.step("start the build provider", () => backend.initialize(root, [3]));
    const params = (extra = {}) => ({
      buildSystemID: "maven", root: ".", options: {}, optionArguments: [], environment: {},
      tool: { executable: mavenExecutable() }, ...extra,
    });
    const model = await report.step("read the reactor model", () => backend.request("lithe/build/model", params()));
    capture("model.json", model);
    const byName = (name) => model.projects.find((project) => project.name === name);

    await check("the model lists the reactor and its aggregator tree", () => {
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

    const projectDependencies = await report.step("read project dependencies", () => backend.request(
      "lithe/build/dependencies", params({ project: { name: "app", path: "app" } }),
    ));
    capture("app-dependencies.json", projectDependencies);
    await check("a project's tree resolves an uninstalled sibling from the reactor", () => {
      const tree = projectDependencies;
      assert.equal(tree.format, "litheDependencies");
      assert.deepEqual(tree.dependencies.map((node) => [node.group, node.name, node.scope]), [["demo", "core", "compile"]]);
    });

    const workspaceDependencies = await report.step("read workspace dependencies", () => backend.request(
      "lithe/build/dependencies", params({ project: null }),
    ));
    capture("dependencies.json", workspaceDependencies);
    await check("the workspace tree has one node per project with transitive children", () => {
      const tree = workspaceDependencies;
      assert.deepEqual(tree.dependencies.map((node) => node.name), ["reactor", "core", "app"]);
      const junit = tree.dependencies[1].children[0];
      assert.deepEqual([junit.name, junit.version, junit.scope], ["junit", "4.13.2", "test"]);
      assert.equal(junit.children[0].name, "hamcrest-core");
    });

    await check("one Maven model run serves every tree and leaves a reusable snapshot", async () => {
      const saved = JSON.parse(readFileSync(join(scratch, "state", "maven-snapshot.json"), "utf8")).value;
      assert.equal(saved.snapshotVersion, 1);
      assert.deepEqual(saved.roots[root].model, model);
      // The provider remains alive for the native task/test requests below.
    });

    await check("reading the model and dependencies builds nothing", () => {
      for (const directory of ["target", "core/target", "app/target"]) {
        assert.ok(!existsSync(join(root, directory)), `${directory} was not created`);
      }
    });

    await report.step("run the filtered Maven test suite through its provider", async () => {
      const result = await backend.request("lithe/build/test", params({ project: { name: "core", path: "core" },
        suite: "demo.core.GreeterTest", userArguments: [], operationID: "test-suite" }));
      assert.equal(result.exitCode, 0, result.output);
    });
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
    if (backend) console.error(`build provider stderr:\n${backend.stderr}`);
  } finally {
    signal.removeEventListener("abort", stopBackend);
    if (backend) {
      backend.kill();
      await bounded(backend.exited, "the build provider to end").catch(() => {});
    }
    try { await report.step("remove the scratch reactor", () => rmSync(scratch, { recursive: true, force: true })); }
    finally { report.write(); }
  }
  process.exitCode = failed ? 1 : 0;
}
await withInterrupts(test);
