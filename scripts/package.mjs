#!/usr/bin/env node
// Packages the plugin into dist/<name>-<version>-darwin.zip, the asset name
// Lithe Store's release tracker recognizes (docs/publishing.md there).
//
// The package is plain data plus two jars, built here with Maven and copied
// to lib/: the Maven core extension in extension/, which runs inside the
// user's own Maven, and the build provider in backend/, which Lithe starts
// with Java 8 or newer. Neither carries native code, so one archive serves
// every Mac. The unpacked package stays in dist/package/ for installing a
// local development copy into Lithe.
//
// The backend shades Lithe's Java plugin SDK pinned in sdk.lock.json: the
// JAR is downloaded once into .cache/sdk/ and refused unless its SHA-256
// matches. LITHE_SDK_JAR=<path> uses a local JAR instead; while the lock has
// no SHA-256 (the SDK is not released yet) that is the only way, and a
// release (--expect-tag) refuses it.
//
// Usage: node scripts/package.mjs [--expect-tag <tag>]

import { mavenTimeout, runCommand, withInterrupts, ZIP_TIMEOUT_MS } from "./process.mjs";
import { createReport } from "./report.mjs";
import { createHash } from "node:crypto";
import { cpSync, existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const PACKAGE_DIR = "io.github.lithe-idea.maven-build";
const ASSET_PREFIX = "maven-build";
const PLATFORM = "darwin";
const EXTENSION_JAR = "extension/target/lithe-maven-extension.jar";
const BACKEND_JAR = "backend/target/lithe-maven-backend.jar";
const SDK_DOWNLOAD_TIMEOUT_MS = 60_000;
const PACKAGE_ROOT = join("dist", "package");

const manifest = JSON.parse(readFileSync(`${PACKAGE_DIR}/plugin.json`, "utf8"));
const extensionPom = readFileSync("extension/pom.xml", "utf8");
const pomVersion = (pom, artifact) => new RegExp(`<artifactId>${artifact}</artifactId>\\s*(?:<!--.*?-->\\s*)?<version>([^<]+)</version>`, "s")
  .exec(pom)?.[1];
const extensionVersion = pomVersion(extensionPom, "lithe-maven-extension");
const backendPom = readFileSync("backend/pom.xml", "utf8");
const backendVersion = pomVersion(backendPom, "lithe-maven-backend");
const sdkLock = JSON.parse(readFileSync("sdk.lock.json", "utf8"));
const backendSdkVersion = /<artifactId>lithe-plugin-sdk-java<\/artifactId>\s*<version>([^<]+)<\/version>/.exec(backendPom)?.[1];
const problems = [];
if (manifest.id !== PACKAGE_DIR) problems.push(`plugin.json id ${manifest.id} must equal the directory ${PACKAGE_DIR}`);
if (!/^[0-9]+\.[0-9]+\.[0-9]+$/.test(manifest.version)) problems.push(`version ${manifest.version} is not MAJOR.MINOR.PATCH`);
if (manifest.vendor?.signatureRequirement !== "none") problems.push('vendor.signatureRequirement must be "none"');
if (manifest.id === "dev.lithe" || manifest.id.startsWith("dev.lithe.")) problems.push("dev.lithe.* is reserved for official plugins");
if (extensionVersion !== manifest.version) {
  problems.push(`extension/pom.xml version ${extensionVersion} must equal plugin.json version ${manifest.version}`);
}
if (backendVersion !== manifest.version) {
  problems.push(`backend/pom.xml version ${backendVersion} must equal plugin.json version ${manifest.version}`);
}
if (backendSdkVersion !== sdkLock.version) {
  problems.push(`backend/pom.xml SDK version ${backendSdkVersion} must equal sdk.lock.json version ${sdkLock.version}`);
}
if (sdkLock.sha256 !== null && !/^[0-9a-f]{64}$/.test(sdkLock.sha256)) problems.push("sdk.lock.json sha256 must be 64 lowercase hex digits or null");

const tagFlag = process.argv.indexOf("--expect-tag");
if (tagFlag >= 0) {
  const tag = process.argv[tagFlag + 1];
  if (tag !== `v${manifest.version}`) problems.push(`tag ${tag} does not match plugin.json version ${manifest.version} (expected v${manifest.version})`);
  if (sdkLock.sha256 === null) problems.push("a release needs a released SDK: pin its url and sha256 in sdk.lock.json");
  if (process.env.LITHE_SDK_JAR) problems.push("a release uses the SDK pinned in sdk.lock.json; unset LITHE_SDK_JAR");
}
if (problems.length > 0) {
  for (const problem of problems) console.error(`✗ ${problem}`);
  process.exit(1);
}

const sha256 = (path) => createHash("sha256").update(readFileSync(path)).digest("hex");

/** The SDK JAR to shade, checked against sdk.lock.json. */
async function sdkJar(signal) {
  const local = process.env.LITHE_SDK_JAR;
  if (local) {
    const path = resolve(local);
    if (!existsSync(path)) throw new Error(`LITHE_SDK_JAR ${path} does not exist`);
    const actual = sha256(path);
    if (sdkLock.sha256 !== null && actual !== sdkLock.sha256) {
      throw new Error(`LITHE_SDK_JAR ${path} has SHA-256 ${actual}, but sdk.lock.json pins ${sdkLock.sha256}`);
    }
    if (sdkLock.sha256 === null) console.warn(`! using the unreleased SDK ${path} (SHA-256 ${actual}); sdk.lock.json pins no release yet`);
    return path;
  }
  if (sdkLock.sha256 === null) {
    throw new Error(`SDK ${sdkLock.version} is not released yet (sdk.lock.json has no sha256). `
      + `Build it from ${sdkLock.source.repository} ${sdkLock.source.ref} and set LITHE_SDK_JAR to ${sdkLock.source.jar}.`);
  }
  const cached = resolve(".cache", "sdk", `lithe-plugin-sdk-java-${sdkLock.version}.jar`);
  if (existsSync(cached) && sha256(cached) === sdkLock.sha256) return cached;
  const response = await fetch(sdkLock.url, { signal: AbortSignal.any([signal, AbortSignal.timeout(SDK_DOWNLOAD_TIMEOUT_MS)]) });
  if (!response.ok) throw new Error(`downloading ${sdkLock.url} failed with HTTP ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  const actual = createHash("sha256").update(bytes).digest("hex");
  if (actual !== sdkLock.sha256) throw new Error(`${sdkLock.url} has SHA-256 ${actual}, but sdk.lock.json pins ${sdkLock.sha256}`);
  mkdirSync(resolve(".cache", "sdk"), { recursive: true });
  writeFileSync(`${cached}.partial`, bytes);
  renameSync(`${cached}.partial`, cached);
  return cached;
}

/** Lithe refuses packages with symbolic links, so refuse them before zipping. */
function assertNoLinks(directory) {
  for (const name of readdirSync(directory)) {
    const path = join(directory, name);
    const stat = lstatSync(path);
    if (stat.isSymbolicLink()) throw new Error(`${path} is a symbolic link`);
    if (stat.isDirectory()) assertNoLinks(path);
  }
}

const report = createReport("package");
const timeoutMs = mavenTimeout();
await withInterrupts(async (signal) => {
  try {
    await report.step("build the Maven extension", async () => {
      await runCommand(process.env.MVN ?? "mvn", ["-B", "-ntp", "-q", "-f", "extension/pom.xml", "clean", "package"], { timeoutMs, signal });
      if (!existsSync(EXTENSION_JAR)) throw new Error(`${EXTENSION_JAR} was not built`);
    });

    const sdk = await report.step("check the plugin SDK", () => sdkJar(signal));
    await report.step("build the Maven backend", async () => {
      await runCommand(process.env.MVN ?? "mvn", [
        "-B", "-ntp", "-q", "-f", "backend/pom.xml", `-Dlithe.sdk.jar=${sdk}`, "clean", "package",
      ], { timeoutMs, signal });
      if (!existsSync(BACKEND_JAR)) throw new Error(`${BACKEND_JAR} was not built`);
    });

    await report.step("assemble the plugin package", () => {
      signal.throwIfAborted();
      rmSync("dist", { recursive: true, force: true });
      const packageRoot = join(PACKAGE_ROOT, PACKAGE_DIR);
      cpSync(PACKAGE_DIR, packageRoot, { recursive: true });
      mkdirSync(join(packageRoot, "lib"), { recursive: true });
      cpSync(EXTENSION_JAR, join(packageRoot, "lib", "lithe-maven-extension.jar"));
      cpSync(BACKEND_JAR, join(packageRoot, "lib", "lithe-maven-backend.jar"));
      cpSync("LICENSE", join(packageRoot, "LICENSE"));
      cpSync("NOTICE", join(packageRoot, "NOTICE"));
      assertNoLinks(packageRoot);
    });

    const asset = resolve("dist", `${ASSET_PREFIX}-${manifest.version}-${PLATFORM}.zip`);
    // -X drops extra file attributes; -y stores a symbolic link as a link so
    // Lithe refuses it instead of the archive silently carrying its target.
    await report.step("archive the plugin package", () => runCommand("zip", ["-qrXy", asset, PACKAGE_DIR, "-x", "*.DS_Store"], {
      cwd: PACKAGE_ROOT, timeoutMs: ZIP_TIMEOUT_MS, signal,
    }));
    console.log(`dist/${ASSET_PREFIX}-${manifest.version}-${PLATFORM}.zip ${sha256(asset)}`);
  } catch (error) {
    report.failure("packaging lane failed", error);
    console.error(error.stack);
    process.exitCode = 1;
  } finally {
    report.write();
  }
});
