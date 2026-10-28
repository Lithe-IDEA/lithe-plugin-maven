// Runtime-discovered components must survive a clean package, independent of
// compiler output left by an earlier build. Native settings and failed report
// callbacks are otherwise silently absent despite their classes being present.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
export function verifyExtension(jar) {
  const result = spawnSync('unzip', ['-p', jar, 'META-INF/sisu/javax.inject.Named'],
    { encoding: 'utf8', timeout: 5000, maxBuffer: 65536 });
  assert.equal(result.status, 0, result.error?.message ?? result.stderr);
  const components = new Set(result.stdout.trim().split(/\r?\n/));
  for (const name of ['LitheLifecycleParticipant', 'NativeSettingsEvents', 'ReportCaptureEvents']) {
    assert.ok(components.has('io.github.litheidea.maven.' + name), `The clean extension package omits ${name}`);
  }
}
