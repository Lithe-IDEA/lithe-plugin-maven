#!/usr/bin/env node
// Node-only lifecycle regressions; no Maven, network, or installed Lithe needed.
import assert from "node:assert/strict";
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { performance } from "node:perf_hooks";
import { mavenTimeout, runCommand as execute, withInterrupts } from "./process.mjs";
import { createReport } from "./report.mjs";

await withInterrupts(async (signal) => {
  const runCommand = (command, args, options = {}) => execute(command, args, { signal, ...options });
  const report = createReport("process-regressions");
  const scratch = mkdtempSync(join(tmpdir(), "lithe-process-test-"));
  const fixture = resolve("test/fixtures/process-tree.mjs");
  let failed = false;
  let sequence = 0;
  const ownedRecords = new Set();
  // Independent from the implementation under test: a broken watchdog must not
  // turn its own regression into an unbounded CI wait.
  const suiteDeadline = setTimeout(() => {
    const error = new Error("process regression suite exceeded 30000 ms");
    console.error(error.message);
    report.failure("regression suite deadline", error);
    for (const file of ownedRecords) cleanup(file);
    report.write();
    process.exit(1);
  }, 30_000);
  const readPids = (file) => readFileSync(file, "utf8").trim().split("\n").map(Number);
  const assertGone = (file, count) => {
    const pids = readPids(file);
    assert.equal(pids.length, count, "the ready handshake must include every descendant");
    for (const pid of pids) assert.throws(() => process.kill(pid, 0), { code: "ESRCH" }, `process ${pid} was reaped`);
  };
  const check = async (name, body) => {
    try { await report.step(name, body); }
    catch (error) { failed = true; console.error(error.stack); }
  };
  function cleanup(records) {
    try {
      for (const pid of readPids(records)) {
        try { process.kill(pid, "SIGKILL"); }
        catch (error) { if (error.code !== "ESRCH") throw error; }
      }
    } catch (error) { if (error.code !== "ENOENT") throw error; }
  }
  const tree = async (mode, action, body) => {
    const records = join(scratch, `pids-${++sequence}`);
    ownedRecords.add(records);
    try { await body(records, [fixture, mode, records, action]); }
    finally {
      // Test teardown owns even a broken implementation's surviving fixtures.
      cleanup(records);
      ownedRecords.delete(records);
    }
  };

  try {
    await check("success and nonzero exit preserve status, cwd, and command diagnostics", async () => {
      const listeners = [process.listenerCount("SIGINT"), process.listenerCount("SIGTERM")];
      await runCommand(process.execPath, ["-e", "process.exit(0)"], { cwd: scratch, stdio: "ignore", timeoutMs: 2_000 });
      await assert.rejects(runCommand(process.execPath, ["-e", "process.exit(7)"], {
        cwd: scratch, stdio: "ignore", timeoutMs: 2_000,
      }), (error) => error.message.includes("status 7") && error.message.includes(scratch) && error.message.includes(process.execPath));
      assert.deepEqual([process.listenerCount("SIGINT"), process.listenerCount("SIGTERM")], listeners);
    });
    await check("missing executable fails promptly with a useful diagnostic", async () => {
      await assert.rejects(runCommand(join(scratch, "missing"), [], { stdio: "ignore", timeoutMs: 2_000 }), /ENOENT/);
    });
    await check("timeout escalates past ignored SIGTERM and reaps the whole tree", () => tree("tree", "timeout", async (records, args) => {
      const started = performance.now();
      await assert.rejects(runCommand(process.execPath, args, {
        stdio: ["ignore", "pipe", "pipe"], timeoutMs: 1_500, graceMs: 100, killWaitMs: 1_000,
      }), /timed out \(limit 1500 ms\)/);
      assert.ok(performance.now() - started < 3_500, "timeout and cleanup have a local bound");
      assertGone(records, 2);
    }));
    await check("an exited parent cannot leave a descendant holding output pipes", () => tree("tree", "exit", async (records, args) => {
      await runCommand(process.execPath, args, {
        stdio: ["ignore", "pipe", "pipe"], timeoutMs: 3_000, graceMs: 100, killWaitMs: 1_000,
      });
      assertGone(records, 2);
    }));
    for (const signal of ["SIGINT", "SIGTERM"]) {
      await check(`${signal} cleans up descendants, runs finally, and returns the conventional status`, () => tree("owner", signal, async (records, args) => {
        await assert.rejects(runCommand(process.execPath, args, {
          stdio: "ignore", timeoutMs: 8_000,
        }), new RegExp(`status ${signal === "SIGINT" ? 130 : 143}`));
        assert.equal(readFileSync(`${records}.cleaned`, "utf8"), "owner cleanup ran\n");
        assertGone(records, 3);
      }));
    }
    await check("missing and malformed command output fail both the lane and its reports", async () => {
      const cwd = join(scratch, "invalid-output");
      mkdirSync(cwd);
      mkdirSync(join(cwd, "test/fixtures/reactor"), { recursive: true });
      const packageId = "io.github.lithe-idea.maven-build";
      const plugin = join(cwd, "dist/package", packageId);
      mkdirSync(join(plugin, "lib"), { recursive: true });
      cpSync(join(packageId, "plugin.json"), join(plugin, "plugin.json"));
      writeFileSync(join(plugin, "lib/lithe-maven-extension.jar"), "test placeholder");
      const fake = join(cwd, "fake-maven.mjs");
      cpSync(resolve("test/fixtures/fake-maven.mjs"), fake);
      chmodSync(fake, 0o755);
      const temporary = join(cwd, "tmp");
      mkdirSync(temporary);
      for (const mode of ["missing", "malformed"]) {
        const reports = join(cwd, mode);
        await assert.rejects(runCommand(process.execPath, [resolve("scripts/test.mjs")], {
          cwd, env: { ...process.env, MVN: fake, FAKE_MAVEN_MODE: mode, TMPDIR: temporary, LITHE_REPORT_DIR: reports },
          stdio: "ignore", timeoutMs: 5_000, graceMs: 4_000,
        }), /status 1/);
        const result = JSON.parse(readFileSync(join(reports, "maven-integration.json"), "utf8"));
        assert.ok(result.tests.some((test) => test.status === "error"));
        assert.match(readFileSync(join(reports, "maven-integration.junit.xml"), "utf8"), /<error/);
        assert.deepEqual(readdirSync(temporary), [], "failed integrations remove their scratch reactors");
        assert.ok(existsSync(join(reports, "maven-integration.html")));
      }
    });
    await check("pre-cancelled calls spawn nothing and invalid deadlines are rejected", async () => {
      const controller = new AbortController();
      controller.abort(new Error("already cancelled"));
      assert.throws(() => runCommand(process.execPath, [], { signal: controller.signal }), /already cancelled/);
      for (const timeoutMs of [0, -1, Infinity, NaN, 3_600_001]) {
        assert.throws(() => runCommand(process.execPath, [], { timeoutMs }), /timeoutMs must be/);
      }
      const previous = process.env.LITHE_MAVEN_TIMEOUT_MS;
      try {
        process.env.LITHE_MAVEN_TIMEOUT_MS = "invalid";
        assert.throws(mavenTimeout, /LITHE_MAVEN_TIMEOUT_MS/);
        process.env.LITHE_MAVEN_TIMEOUT_MS = "1000";
        assert.equal(mavenTimeout(), 1_000);
      } finally {
        if (previous === undefined) delete process.env.LITHE_MAVEN_TIMEOUT_MS;
        else process.env.LITHE_MAVEN_TIMEOUT_MS = previous;
      }
    });
  } finally {
    clearTimeout(suiteDeadline);
    rmSync(scratch, { recursive: true, force: true });
    report.write();
  }
  process.exitCode = failed ? 1 : 0;
});
