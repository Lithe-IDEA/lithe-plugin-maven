// Optional explicit local Core integration. No fallback when a supplied Core rejects the manifest.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
export function coreCommand(command, payload, expectedStatus = 'ok') {
  if (!process.env.LITHE_CORE_LIBRARY) throw new Error('Set LITHE_CORE_LIBRARY to the current host Core dynamic library.');
  const result = spawnSync(process.env.PYTHON ?? 'python3', [fileURLToPath(new URL('./core-command.py', import.meta.url)), process.env.LITHE_CORE_LIBRARY], {
    input: JSON.stringify({ id: 'maven-integration', command, payload }), encoding: 'utf8', timeout: 15000, maxBuffer: 32 * 1024 * 1024,
  });
  assert.equal(result.status, 0, result.error?.message ?? result.stderr);
  const envelope = JSON.parse(result.stdout);
  assert.equal(envelope.ok, true, JSON.stringify(envelope));
  if (expectedStatus !== null) assert.equal(envelope.data.status, expectedStatus, JSON.stringify(envelope.data));
  return envelope.data;
}
