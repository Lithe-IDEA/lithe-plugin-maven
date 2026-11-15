// The Windows asset must contain the same portable implementation, not an
// empty marker ZIP that merely makes the marketplace offer an install button.
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const id = 'io.github.lithe-idea.maven-build';
const version = JSON.parse(readFileSync(`${id}/plugin.json`, 'utf8')).version;

test('Windows x64 release contains the verified portable Maven plugin', { timeout: 15000 }, () => {
  const windows = `dist/maven-build-${version}-windows.zip`;
  const digest = (file) => createHash('sha256').update(readFileSync(file)).digest('hex');
  assert.equal(digest(windows), digest(`dist/maven-build-${version}-darwin.zip`));
  const manifest = JSON.parse(execFileSync('unzip', ['-p', windows, `${id}/plugin.json`], {
    timeout: 10000, maxBuffer: 1024 * 1024,
  }));
  const invocation = manifest.buildSystems[0].invocation;
  assert.ok(invocation.executables.some((candidate) => candidate.platforms?.includes('windows')));
});
