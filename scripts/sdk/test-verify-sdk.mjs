#!/usr/bin/env node
// Regressions for scripts/sdk/verify-sdk.mjs. No network and no Maven: the
// release check runs against injected fetchers and a local git repository.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { appendFileSync, cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { runCommand } from "../process.mjs";
import { createReport } from "../report.mjs";
import { readLock, resolveTagCommit, verifySdk, verifySdkRelease } from "./verify-sdk.mjs";

const report = createReport("sdk-lock");
const scratch = mkdtempSync(join(tmpdir(), "lithe-sdk-lock-test-"));
let failed = false;
let sequence = 0;
const check = async (name, body) => {
  try { await report.step(name, body); }
  catch (error) { failed = true; console.error(error.stack); }
};
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const lockPath = (root) => join(root, "sdk.lock.json");

/** A disposable copy of the files verifySdk reads. */
function copyRepository() {
  const root = join(scratch, `repository-${++sequence}`);
  mkdirSync(root);
  cpSync(resolve("vendor"), join(root, "vendor"), { recursive: true });
  cpSync(resolve("sdk.lock.json"), lockPath(root));
  return root;
}
const editLock = (root, change) => {
  const lock = JSON.parse(readFileSync(lockPath(root), "utf8"));
  change(lock);
  writeFileSync(lockPath(root), `${JSON.stringify(lock, null, 2)}\n`);
};
const backend = (lock) => lock.files.find((entry) => entry.path.endsWith("/Backend.java"));
const git = (cwd, ...args) => runCommand("git", args, {
  cwd,
  stdio: "ignore",
  timeoutMs: 20_000,
  env: {
    ...process.env,
    GIT_AUTHOR_NAME: "test", GIT_AUTHOR_EMAIL: "test@example.invalid",
    GIT_COMMITTER_NAME: "test", GIT_COMMITTER_EMAIL: "test@example.invalid",
    GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_SYSTEM: "/dev/null",
  },
});

try {
  await check("the repository's vendored SDK matches its lock", async () => {
    const lock = await verifySdk();
    assert.equal(lock.tag, `sdk-java-v${lock.version}`);
    assert.match(lock.commit, /^[0-9a-f]{40}$/);
    assert.match(lock.sha256, /^[0-9a-f]{64}$/);
    assert.ok(lock.files.every((entry) => Object.keys(entry).length === 3), "files carry no local-change markers");
  });

  await check("a modified, extra or missing vendored file is rejected", async () => {
    const modified = copyRepository();
    appendFileSync(join(modified, backend(await readLock(modified)).path), "// local change\n");
    await assert.rejects(verifySdk(modified), /SDK SHA-256 mismatch: .*Backend\.java/);

    const extra = copyRepository();
    writeFileSync(join(extra, "vendor/lithe-plugin-sdk-java/Extra.java"), "class Extra {}\n");
    await assert.rejects(verifySdk(extra), /Unpinned SDK input: vendor\/lithe-plugin-sdk-java\/Extra\.java/);

    const missing = copyRepository();
    rmSync(join(missing, backend(await readLock(missing)).path));
    await assert.rejects(verifySdk(missing), /ENOENT/);
  });

  await check("a lock that is not a fully pinned release is rejected", async () => {
    const cases = {
      "unsupported schema": (lock) => { lock.schemaVersion = 1; },
      "another repository": (lock) => { lock.repository = "https://github.com/example/lithe-plugin-docs"; },
      "tag not derived from the version": (lock) => { lock.tag = "sdk-java-v9.9.9"; },
      "abbreviated commit": (lock) => { lock.commit = lock.commit.slice(0, 7); },
      "asset url not derived from the tag": (lock) => { lock.url = "https://example.invalid/lithe-plugin-sdk-java.jar"; },
      "missing asset checksum": (lock) => { delete lock.sha256; },
      "local-change marker": (lock) => { backend(lock).localChanges = true; },
      "path outside vendor": (lock) => { backend(lock).path = "elsewhere/Backend.java"; },
      "upstream not the vendored file's origin": (lock) => { backend(lock).upstream = "sdk/node/Backend.java"; },
      "duplicate entry": (lock) => { lock.files.push({ ...lock.files[0] }); },
      "path traversal": (lock) => { backend(lock).path = "vendor/../sdk.lock.json"; },
    };
    for (const [name, change] of Object.entries(cases)) {
      const root = copyRepository();
      editLock(root, change);
      await assert.rejects(verifySdk(root), /SDK lock|lock entry/, name);
    }
  });

  await check("the release check passes only when tag, asset and upstream files all match", async () => {
    const root = copyRepository();
    const lock = await readLock(root);
    const sources = new Map(lock.files.map((entry) => [entry.upstream, readFileSync(join(root, entry.path))]));
    const requested = [];
    const fetchSource = async (url) => {
      requested.push(url);
      const upstream = url.slice(`https://raw.githubusercontent.com/Lithe-IDEA/lithe-plugin-docs/${lock.commit}/`.length);
      if (!sources.has(upstream)) throw new Error(`unexpected ${url}`);
      return sources.get(upstream);
    };
    const asset = Buffer.from("released jar bytes");
    editLock(root, (value) => { value.sha256 = sha256(asset); });
    const resolveTag = async (remote, tag) => {
      assert.equal(remote, "https://github.com/Lithe-IDEA/lithe-plugin-docs.git");
      assert.equal(tag, lock.tag);
      return lock.commit;
    };
    const fetchAsset = async (url) => {
      assert.equal(url, lock.url);
      return asset;
    };
    await verifySdkRelease(root, { fetchAsset, fetchSource, resolveTag });
    assert.equal(requested.length, lock.files.length, "every locked file is compared with the tag's copy");

    await assert.rejects(
      verifySdkRelease(root, { fetchAsset, fetchSource, resolveTag: async () => "1".repeat(40) }),
      /points at 1{40}, but the lock pins/,
    );
    await assert.rejects(
      verifySdkRelease(root, { fetchAsset: async () => Buffer.from("replaced"), fetchSource, resolveTag }),
      /release asset checksum mismatch/,
    );
    await assert.rejects(
      verifySdkRelease(root, {
        fetchAsset, resolveTag,
        fetchSource: async (url) => (url.endsWith("/Backend.java") ? Buffer.from("tampered") : fetchSource(url)),
      }),
      /Backend\.java differs from .* at sdk-java-v/,
    );
    await assert.rejects(
      verifySdkRelease(root, { fetchAsset, resolveTag, fetchSource: async () => { throw new Error("HTTP 404"); } }),
      /HTTP 404/,
    );
  });

  await check("the tag resolver follows annotated and lightweight tags in a real git repository", async () => {
    const remote = join(scratch, "remote");
    mkdirSync(remote);
    await git(remote, "init", "-q");
    writeFileSync(join(remote, "file.txt"), "one\n");
    await git(remote, "add", "file.txt");
    await git(remote, "commit", "-q", "-m", "one");
    await git(remote, "tag", "-a", "-m", "annotated release", "annotated-v1");
    await git(remote, "tag", "lightweight-v1");
    const head = readFileSync(join(remote, ".git/refs/heads", readFileSync(join(remote, ".git/HEAD"), "utf8").trim().split("/").pop()), "utf8").trim();
    assert.equal(await resolveTagCommit(remote, "annotated-v1"), head, "an annotated tag resolves to its commit, not the tag object");
    assert.equal(await resolveTagCommit(remote, "lightweight-v1"), head);
    await assert.rejects(resolveTagCommit(remote, "missing-v1"), /Tag missing-v1 was not found/);
  });
} finally {
  rmSync(scratch, { recursive: true, force: true });
  report.write();
}
process.exitCode = failed ? 1 : 0;
