#!/usr/bin/env node
// Run real packaging/testing in a disposable source copy. Existing accepted ZIPs,
// development packages and explicitly supplied host bundles stay read-only.
// Usage: node scripts/verify-scripts.mjs --host-bundle /path/to/Lithe.app [--host-bundle ...]
import assert from "node:assert/strict";
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join, resolve } from "node:path";
import { bundleManifest } from "./bundle-manifest.mjs";
import { mavenTimeout, runCommand, withInterrupts } from "./process.mjs";
import { createReport } from "./report.mjs";

const hosts = [];
for (let i = 2; i < process.argv.length; i += 2) {
  if (process.argv[i] !== "--host-bundle" || !process.argv[i + 1]) throw new Error("expected --host-bundle /path/to/Lithe.app");
  hosts.push(resolve(process.argv[i + 1]));
}
if (!hosts.length) throw new Error("at least one --host-bundle is required for the host immutability check");
const packageId = "io.github.lithe-idea.maven-build";
const originalPackage = resolve("dist/package", packageId);
const protectedPaths = [resolve(packageId), ...hosts];
if (existsSync(originalPackage)) protectedPaths.push(originalPackage);
const manifest = JSON.parse(readFileSync(join(packageId, "plugin.json"), "utf8"));
const originalZip = resolve("dist", `maven-build-${manifest.version}-darwin.zip`);
if (existsSync(originalZip)) protectedPaths.push(originalZip);
const artifacts = resolve(".artifacts/script-verification");
mkdirSync(artifacts, { recursive: true });
const evidence = mkdtempSync(join(artifacts, "run-"));
const scratch = mkdtempSync(join(tmpdir(), "lithe-script-verification-"));
const report = createReport("script-verification");
const before = protectedPaths.map(bundleManifest);
const snapshot = (name, manifests) => writeFileSync(join(evidence, `${name}.json`), `${JSON.stringify(manifests, null, 2)}\n`);
snapshot("before", before);
const unchanged = (name) => {
  const after = protectedPaths.map(bundleManifest);
  snapshot(name, after);
  for (let i = 0; i < before.length; i++) assert.deepEqual(after[i], before[i], `${protectedPaths[i]} changed`);
};

await withInterrupts(async (signal) => {
  try {
    for (const path of [packageId, "scripts", "extension", "test", "LICENSE", "NOTICE"]) {
      cpSync(resolve(path), join(scratch, path), {
        recursive: true,
        filter: (source) => source !== resolve("extension/target"),
      });
    }
    const timeoutMs = mavenTimeout();
    const env = { ...process.env, LITHE_REPORT_DIR: join(scratch, ".artifacts/test-stability") };
    await report.step("package in an isolated source copy", () => runCommand(process.execPath, ["scripts/package.mjs"], {
      cwd: scratch, env, timeoutMs: timeoutMs + 40_000, graceMs: 4_000, signal,
    }));
    await report.step("packaging preserves original plugins, accepted ZIP and host bundles", () => unchanged("after-package"));
    const built = join(scratch, "dist/package", packageId);
    const packaged = bundleManifest(built);
    snapshot("test-before", [packaged]);
    await report.step("test the newly built package with real Maven", () => runCommand(process.execPath, ["scripts/test.mjs"], {
      cwd: scratch, env, timeoutMs: timeoutMs * 5 + 20_000, graceMs: 4_000, signal,
    }));
    await report.step("integration testing preserves the new package and host bundles", () => {
      const tested = bundleManifest(built);
      snapshot("test-after", [tested]);
      assert.deepEqual(tested, packaged, "Maven integration tests modified the packaged plugin");
      unchanged("after-test");
    });
    cpSync(join(scratch, "dist"), join(evidence, "new-build"), { recursive: true });
    console.log(`Verification evidence: ${evidence}`);
    console.log(`New build: ${join(evidence, "new-build", basename(originalZip))}`);
  } catch (error) {
    report.failure("script verification failed", error);
    console.error(error.stack);
    process.exitCode = 1;
  } finally {
    try {
      // Preserve partial timings on failure as well as successful runs.
      await report.step("preserve individual command timings", () => {
        if (existsSync(join(scratch, ".artifacts/test-stability"))) {
          cpSync(join(scratch, ".artifacts/test-stability"), join(evidence, "timing"), { recursive: true });
        }
      });
      await report.step("final inventory preserves all protected files", () => unchanged("final"));
    } finally {
      rmSync(scratch, { recursive: true, force: true });
      report.write();
    }
  }
});
