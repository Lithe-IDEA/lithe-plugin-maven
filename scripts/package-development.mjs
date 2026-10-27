#!/usr/bin/env node
// Explicit source-workspace validation only. Never writes dist/ or changes sdk.lock.json.
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { runCommand, withInterrupts, mavenTimeout } from './process.mjs';
import { createReport } from './report.mjs';
import { bundleManifest } from './bundle-manifest.mjs';

if (process.argv.length !== 4 || process.argv[2] !== '--sdk-jar') throw new Error('Usage: node scripts/package-development.mjs --sdk-jar /absolute/path/to/development-sdk.jar');
const sdk = resolve(process.argv[3]);
const digest = createHash('sha256').update(readFileSync(sdk)).digest('hex');
const artifactRoot = resolve('.artifacts/native-development');
mkdirSync(artifactRoot, { recursive: true });
const run = mkdtempSync(join(artifactRoot, 'run-'));
const id = 'io.github.lithe-idea.maven-build';
const report = createReport('native-development-package');
await withInterrupts(async (signal) => {
  const scratch = join(run, 'source');
  try {
    // Isolated build state; the source, accepted dist package and released SDK lock stay read-only.
    for (const dir of ['extension', 'backend']) cpSync(resolve(dir), join(scratch, dir), {
      recursive: true, filter: (file) => file !== resolve(dir, 'target'),
    });
    for (const [name, args] of [
      ['extension', []], ['backend', [`-Dlithe.sdk.jar=${sdk}`]],
    ]) await report.step(`build ${name} with the explicit development SDK`, () => runCommand(process.env.MVN ?? 'mvn',
      ['-B', '-ntp', '-q', '-f', join(scratch, name, 'pom.xml'), ...args, 'package'], { signal, timeoutMs: mavenTimeout() }));
    const pkg = join(run, id);
    await report.step('assemble a development-only package', () => {
      cpSync(resolve(id), pkg, { recursive: true });
      mkdirSync(join(pkg, 'lib'));
      cpSync(join(scratch, 'extension/target/lithe-maven-extension.jar'), join(pkg, 'lib/lithe-maven-extension.jar'));
      cpSync(join(scratch, 'backend/target/lithe-maven-backend.jar'), join(pkg, 'lib/lithe-maven-backend.jar'));
      for (const file of ['LICENSE', 'NOTICE']) cpSync(resolve(file), join(pkg, file));
      writeFileSync(join(run, 'identity.json'), JSON.stringify({ developmentOnly: true, sdk: { path: sdk, sha256: digest }, package: bundleManifest(pkg) }, null, 2) + '\n');
      writeFileSync(join(artifactRoot, 'latest.json'), JSON.stringify({ developmentOnly: true, package: pkg, sdkSHA256: digest }, null, 2) + '\n');
    });
    console.log(`LITHE_TEST_PACKAGE=${pkg}`);
  } catch (error) {
    process.exitCode = 1;
    report.failure('development package failed', error);
    console.error(error.stack);
  } finally {
    rmSync(scratch, { recursive: true, force: true });
    report.write();
  }
});
