#!/usr/bin/env node
// Drives lib/lithe-maven-backend.jar from dist/package over its stdio, as
// Lithe does, with test/fixtures/fake-maven-backend.mjs as Maven. The fake
// logs every run, so the checks count Maven runs exactly: the snapshot
// survives a restart, POM edits and refresh invalidate it, concurrent
// requests share one run, an edit during a run is not saved, and cancelling
// ends Maven and its process tree. No Maven or network is needed.
//
// Run scripts/package.mjs first. LITHE_TEST_JAVA selects the Java that runs
// the backend (default `java`), so CI also checks Java 8.
//
// Usage: node scripts/test-backend.mjs

import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { appendFileSync, chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { BackendClient, bounded } from "./backend-client.mjs";
import { withInterrupts } from "./process.mjs";
import { createReport } from "./report.mjs";

const PACKAGE_DIR = "io.github.lithe-idea.maven-build";
const jar = resolve(process.env.LITHE_TEST_PACKAGE ?? resolve("dist", "package", PACKAGE_DIR), "lib", "lithe-maven-backend.jar");
if (!existsSync(jar)) {
  console.error("✗ dist/package is missing the backend; run node scripts/package.mjs first");
  process.exit(1);
}
const java = process.env.LITHE_TEST_JAVA ?? "java";
// `java -version` prints to stderr; Java 8 and older report 1.<major>.
const [, first, second] = /version "(\d+)(?:\.(\d+))?/.exec(spawnSync(java, ["-version"], { encoding: "utf8" }).stderr) ?? [];
const javaMajor = Number(first) === 1 ? Number(second) : Number(first);
console.log(`backend runtime: ${java} (Java ${javaMajor})`);

// Real paths: on macOS the temporary directory is reached through a link.
const scratch = realpathSync(mkdtempSync(join(tmpdir(), "lithe-maven-backend-test-")));
const root = join(scratch, "reactor");
const state = join(scratch, "state");
const snapshotFile = join(state, "maven-snapshot.json");
const controlFile = join(scratch, "control.json");
const runLog = join(scratch, "runs.log");
const fakeMaven = join(scratch, "bin", "mvn");
const report = createReport("backend");
const clients = new Set();
let failed = false;

const runs = () => existsSync(runLog) ? readFileSync(runLog, "utf8").trim().split("\n").map((line) => JSON.parse(line)) : [];
const pids = (file) => existsSync(file) ? readFileSync(file, "utf8").trim().split("\n").filter(Boolean).map(Number) : [];
const alive = (pid) => {
  try { process.kill(pid, 0); return true; }
  catch (error) { if (error.code === "ESRCH") return false; throw error; }
};
const control = (value) => writeFileSync(controlFile, JSON.stringify(value));
const sleep = (ms) => new Promise((done) => setTimeout(done, ms));
async function until(condition, description, timeoutMs = 10_000) {
  const deadline = Date.now() + timeoutMs;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${description}`);
    await sleep(20);
  }
}
const touch = (relative, text) => appendFileSync(join(root, relative), `\n<!-- ${text} -->\n`);
const params = (extra = {}) => ({
  buildSystemID: "maven", root: ".", options: {}, optionArguments: [], environment: {},
  tool: { executable: fakeMaven }, ...extra,
});

async function start() {
  const client = new BackendClient({
    java, jar, state,
    // A private home keeps the developer's ~/.m2/settings.xml out of the fingerprint.
    javaOptions: [`-Duser.home=${join(scratch, "home")}`],
    env: { ...process.env, FAKE_MAVEN_CONTROL: controlFile, FAKE_MAVEN_LOG: runLog },
  });
  clients.add(client);
  const initialized = await client.initialize(root);
  assert.deepEqual(initialized.capabilities.experimental.litheBuild, { version: 1 });
  return client;
}
async function stop(client) {
  assert.equal(await client.close(), 0, `the backend exits cleanly\n${client.stderr}`);
  clients.delete(client);
}
const check = async (name, body) => {
  try { await report.step(name, body); }
  catch (error) { failed = true; console.error(error.stack); }
};

async function test() {
  try {
    cpSync(resolve("test", "fixtures", "reactor"), root, { recursive: true });
    mkdirSync(join(scratch, "bin"));
    mkdirSync(join(scratch, "home"));
    const fixture = resolve("test", "fixtures", "fake-maven-backend.mjs");
    const wrapper = `#!/bin/sh\nFAKE_MAVEN_SELF="$0" exec ${JSON.stringify(process.execPath)} ${JSON.stringify(fixture)} "$@"\n`;
    writeFileSync(fakeMaven, wrapper);
    chmodSync(fakeMaven, 0o755);
    control({});

    let client = await start();

    await check("the first request runs Maven once for the model and every module's dependencies", async () => {
      const model = await client.request("lithe/build/model", params());
      assert.equal(model.format, "litheBuildModel");
      assert.deepEqual(model.projects.map((project) => project.name), ["reactor", "core", "app"]);
      assert.equal(runs().length, 1);
      const [run] = runs();
      assert.equal(run.cwd, root);
      assert.ok(run.argv.includes("pre-clean") && run.argv.includes("-B") && run.argv.includes("-ntp"));
      assert.ok(run.argv.some((arg) => arg.startsWith("-Dmaven.ext.class.path=") && arg.endsWith("lithe-maven-extension.jar")));

      const app = await client.request("lithe/build/dependencies", params({ project: { name: "app", path: "app" } }));
      assert.equal(app.format, "litheDependencies");
      assert.deepEqual(app.dependencies.map((node) => [node.group, node.name, node.scope]), [["demo", "core", "compile"]]);
      const workspace = await client.request("lithe/build/dependencies", params({ project: null }));
      assert.deepEqual(workspace.dependencies.map((node) => node.name), ["reactor", "core", "app"]);
      const reactor = await client.request("lithe/build/dependencies", params({ project: { name: "reactor", path: "." } }));
      assert.deepEqual(reactor.dependencies, []);
      assert.equal(runs().length, 1, "dependencies come from the same run");

      const snapshot = JSON.parse(readFileSync(snapshotFile, "utf8")).value;
      assert.equal(snapshot.snapshotVersion, 2);
      assert.deepEqual(Object.keys(snapshot.roots), [root]);
    });

    await check("an unknown project is an error, not an empty tree", async () => {
      const { response } = client.send("lithe/build/dependencies", params({ project: { name: "nope", path: "nope" } }));
      const message = await response;
      assert.equal(message.error?.data?.code, "maven.unknownProject");
    });

    await check("a restarted backend answers from the snapshot without running Maven", async () => {
      await stop(client);
      client = await start();
      const model = await client.request("lithe/build/model", params());
      assert.equal(model.fakeRun, 1);
      const app = await client.request("lithe/build/dependencies", params({ project: { name: "app", path: "app" } }));
      assert.equal(app.dependencies[0].name, "core");
      assert.equal(runs().length, 1);
    });

    await check("editing a module's POM invalidates the snapshot", async () => {
      touch("app/pom.xml", "edited");
      assert.equal((await client.request("lithe/build/model", params())).fakeRun, 2);
      assert.equal((await client.request("lithe/build/model", params())).fakeRun, 2);
      assert.equal(runs().length, 2);
    });

    await check("refresh runs Maven even when the snapshot is current", async () => {
      assert.equal((await client.request("lithe/build/model", params({ refresh: true }))).fakeRun, 3);
      assert.equal((await client.request("lithe/build/model", params())).fakeRun, 3);
      assert.equal(runs().length, 3);
    });

    await check("changed options and .mvn/maven.config invalidate the snapshot", async () => {
      assert.equal((await client.request("lithe/build/model", params({ options: { offline: true } }))).fakeRun, 4);
      assert.ok(runs().at(-1).argv.includes("-o"), "option arguments reach Maven");
      mkdirSync(join(root, ".mvn"));
      writeFileSync(join(root, ".mvn", "maven.config"), "-Pci\n");
      assert.equal((await client.request("lithe/build/model", params({ options: { offline: true } }))).fakeRun, 5);
      assert.equal((await client.request("lithe/build/model", params({ options: { offline: true } }))).fakeRun, 5);
    });

    await check("a native settings source selected by Maven invalidates cache even outside CLI guessing", async () => {
      const selected = join(root, ".mvn", "selected settings.xml");
      const before = runs().length;
      writeFileSync(selected, "<settings><!-- external edit --></settings>");
      await client.request("lithe/build/model", params({ options: { offline: true } }));
      assert.equal(runs().length, before + 1);
      await client.request("lithe/build/model", params({ options: { offline: true } }));
      assert.equal(runs().length, before + 1, "unchanged selected source still permits cache reuse");
      rmSync(selected);
      await client.request("lithe/build/model", params({ options: { offline: true } }));
      assert.equal(runs().length, before + 2, "removing an explicitly selected native source invalidates its snapshot");
      const entry = JSON.parse(readFileSync(snapshotFile, "utf8")).value.roots[root];
      assert.equal(entry.nativeSources[0].revision, "missing");
    });

    await check("concurrent requests share one Maven run", async () => {
      touch("core/pom.xml", "concurrent");
      const gate = join(scratch, "gate-concurrent");
      control({ gate });
      const before = runs().length;
      const model = client.send("lithe/build/model", params());
      const app = client.send("lithe/build/dependencies", params({ project: { name: "app", path: "app" } }));
      await until(() => existsSync(`${gate}.started`), "Maven to start");
      await sleep(300);
      writeFileSync(gate, "");
      const [modelResponse, appResponse] = await Promise.all([model.response, app.response]);
      assert.ok(modelResponse.result && appResponse.result, JSON.stringify([modelResponse, appResponse]));
      assert.equal(runs().length, before + 1);
      control({});
    });

    await check("a POM edited while Maven runs is answered but not saved", async () => {
      const gate = join(scratch, "gate-edit");
      control({ gate });
      const saved = readFileSync(snapshotFile, "utf8");
      const pending = client.send("lithe/build/model", params({ refresh: true }));
      await until(() => existsSync(`${gate}.started`), "Maven to start");
      touch("core/pom.xml", "edited during the run");
      writeFileSync(gate, "");
      const response = await pending.response;
      assert.equal(response.result?.fakeRun, runs().length);
      assert.equal(readFileSync(snapshotFile, "utf8"), saved, "the snapshot file is unchanged");
      control({});
      const before = runs().length;
      await client.request("lithe/build/model", params());
      assert.equal(runs().length, before + 1, "the edit is read by a new run");
      await client.request("lithe/build/model", params());
      assert.equal(runs().length, before + 1, "and that run is saved");
    });

    await check("Maven goes on while another request still waits for it", async () => {
      const gate = join(scratch, "gate-partial-cancel");
      control({ gate });
      touch("app/pom.xml", "partial cancel");
      const before = runs().length;
      const first = client.send("lithe/build/model", params());
      const second = client.send("lithe/build/dependencies", params({ project: { name: "core", path: "core" } }));
      await until(() => existsSync(`${gate}.started`), "Maven to start");
      const maven = runs().at(-1).pid;
      client.cancel(first.id);
      assert.equal((await first.response).error?.code, -32800);
      await sleep(300);
      assert.ok(alive(maven), "Maven still runs for the second request");
      writeFileSync(gate, "");
      const result = (await second.response).result;
      assert.deepEqual(result.dependencies.map((node) => node.name), ["junit"]);
      assert.equal(runs().length, before + 1);
      control({});
    });

    await check("cancelling every waiting request ends Maven and its process tree", async () => {
      const gate = join(scratch, "gate-cancel");
      control({ gate, child: true });
      touch("app/pom.xml", "cancel");
      const saved = readFileSync(snapshotFile, "utf8");
      const first = client.send("lithe/build/model", params());
      const second = client.send("lithe/build/model", params());
      await until(() => existsSync(`${gate}.started`) && pids(`${runLog}.children`).length > 0, "Maven and its child to start");
      const maven = runs().at(-1).pid;
      const [child] = pids(`${runLog}.children`);
      client.cancel(first.id);
      client.cancel(second.id);
      assert.equal((await first.response).error?.code, -32800);
      assert.equal((await second.response).error?.code, -32800);
      await until(() => !alive(maven), "Maven to end", 8_000);
      if (javaMajor >= 9) {
        await until(() => !alive(child), "Maven's child to end", 8_000);
      } else {
        // Java 8 cannot list descendants; Lithe's process scope ends them with the backend.
        console.log(`  Java ${javaMajor}: only Maven itself is stopped; ending its child here`);
        try { process.kill(child, "SIGKILL"); } catch (error) { if (error.code !== "ESRCH") throw error; }
      }
      assert.equal(readFileSync(snapshotFile, "utf8"), saved, "a cancelled run saves nothing");
      control({});
      rmSync(`${runLog}.children`);
    });

    await check("a failed Maven run reports Maven's errors and keeps the old snapshot", async () => {
      control({ exit: 1 });
      const saved = readFileSync(snapshotFile, "utf8");
      const { response } = client.send("lithe/build/model", params({ refresh: true }));
      const { error } = await response;
      assert.equal(error?.data?.code, "maven.loadFailed");
      assert.match(error.message, /\[ERROR\] fake failure for the backend test/);
      assert.doesNotMatch(error.message, /Scanning for projects/);
      assert.equal(readFileSync(snapshotFile, "utf8"), saved);
      control({});
    });

    await check("Maven that succeeds without readable documents is an error, not an empty model", async () => {
      const saved = readFileSync(snapshotFile, "utf8");
      for (const output of ["missing", "malformed"]) {
        control({ output });
        const { error } = await client.send("lithe/build/model", params({ refresh: true })).response;
        assert.equal(error?.data?.code, "maven.invalidOutput", output);
      }
      assert.equal(readFileSync(snapshotFile, "utf8"), saved);
      control({});
    });

    await check("a corrupt snapshot or one of an unknown version is a miss", async () => {
      await client.request("lithe/build/model", params());
      let before = runs().length;
      await stop(client);
      writeFileSync(snapshotFile, "{not JSON");
      client = await start();
      await client.request("lithe/build/model", params());
      assert.equal(runs().length, before + 1);

      const stored = JSON.parse(readFileSync(snapshotFile, "utf8"));
      stored.value.snapshotVersion = 99;
      writeFileSync(snapshotFile, JSON.stringify(stored));
      before = runs().length;
      await client.request("lithe/build/model", params());
      assert.equal(runs().length, before + 1);
      assert.equal(JSON.parse(readFileSync(snapshotFile, "utf8")).value.snapshotVersion, 2, "the next run rewrites it");
    });

    await check("without tool.executable the backend uses the project's Maven Wrapper", async () => {
      mkdirSync(join(root, ".mvn", "wrapper"), { recursive: true });
      writeFileSync(join(root, ".mvn", "wrapper", "maven-wrapper.properties"), "distributionUrl=https://example.invalid/maven.zip\n");
      writeFileSync(join(root, "mvnw"), readFileSync(fakeMaven));
      chmodSync(join(root, "mvnw"), 0o755);
      const { tool, ...rest } = params();
      await client.request("lithe/build/model", rest);
      assert.equal(runs().at(-1).self, join(root, "mvnw"));
    });

    await check("the backend exits on shutdown and leaves no Maven behind", async () => {
      await stop(client);
      for (const { pid } of runs()) assert.ok(!alive(pid), `Maven ${pid} ended`);
    });
  } catch (error) {
    failed = true;
    report.failure("backend lane failed", error);
    console.error(error.stack);
  } finally {
    for (const client of clients) {
      if (failed) console.error(`backend stderr:\n${client.stderr}`);
      client.kill();
      await bounded(client.exited, "the backend to be killed").catch(() => {});
    }
    for (const pid of [...runs().map((run) => run.pid), ...pids(`${runLog}.children`)]) {
      try { process.kill(pid, "SIGKILL"); } catch (error) { if (error.code !== "ESRCH") throw error; }
    }
    try { await report.step("remove the scratch workspace", () => rmSync(scratch, { recursive: true, force: true })); }
    finally { report.write(); }
  }
  process.exitCode = failed ? 1 : 0;
}
await withInterrupts(test);
