// Runs this plugin's host end-to-end tests against a pinned Lithe desktop host.
//
//   node scripts/e2e-host.mjs [--source <Lithe git URL or path>] [--ref <commit or branch>] [--run <regex>]
//
// The tests in e2e/host/ drive the real plugin package through the desktop host's own test
// harness (an internal Go package), so they cannot be built in this repository. Each run checks
// out the host revision pinned in e2e/host/lithe-host.json into the disposable .artifacts/lithe-host
// (desktop/ and scripts/ only), copies every e2e/host/desktop/<path> file to the same path in that
// checkout and runs the host's scripts/desktop/desktop-go.sh test there, which builds the Core
// revision that host pins. The host repository never reads this repository: the tests live here
// and the pin says which host they last passed on. Bumping the pin can require adapting the tests
// to changes in the host's internal harness.
//
// Every variable listed in lithe-host.json "requiredEnvironment" must be set, because the tests
// skip without their plugin input and a skipped run proves nothing. "repositoryEnvironment" maps a
// variable to a path in this repository, used when the variable is not set. --source and --ref override
// the pin, for example to try an unmerged host branch; --run overrides the test selection.
import { spawn } from 'node:child_process';
import { copyFile, mkdir, readFile, readdir } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';

const root = fileURLToPath(new URL('..', import.meta.url));
const tests = join(root, 'e2e', 'host');
const work = join(root, '.artifacts', 'lithe-host');

function run(command, args, options = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', ...options });
    child.on('error', reject);
    child.on('close', (code, signal) => {
      if (code === 0) resolvePromise();
      else reject(new Error(`${command} ${args.join(' ')} failed (${signal ?? `exit ${code}`})`));
    });
  });
}

/** Lists the files under e2e/host/desktop, relative to e2e/host. */
async function hostTestFiles(directory = join(tests, 'desktop')) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) files.push(...await hostTestFiles(path));
    else if (entry.isFile()) files.push(relative(tests, path));
  }
  return files;
}

/** Checks out `ref` from `source` into the disposable work directory, desktop/ and scripts/ only. */
async function checkoutHost(source, ref) {
  await mkdir(work, { recursive: true });
  await run('git', ['init', '-q', work]);
  await run('git', ['-C', work, 'sparse-checkout', 'set', '--cone', 'desktop', 'scripts']);
  // A named remote, because the partial clone fetches missing blobs from it later.
  await run('git', ['-C', work, 'config', 'remote.origin.url', source]);
  await run('git', ['-C', work, 'fetch', '-q', '--depth', '1', '--filter=blob:none', 'origin', ref]);
  await run('git', ['-C', work, 'checkout', '-q', '--force', '--detach', 'FETCH_HEAD']);
  // Drops test files copied by an earlier run; ignored build state such as .artifacts/ stays.
  await run('git', ['-C', work, 'clean', '-q', '-f', '-d', '--', 'desktop']);
}

const { values } = parseArgs({
  options: { source: { type: 'string' }, ref: { type: 'string' }, run: { type: 'string' } },
});
const pin = JSON.parse(await readFile(join(tests, 'lithe-host.json'), 'utf8'));
for (const [name, path] of Object.entries(pin.repositoryEnvironment ?? {})) {
  process.env[name] ||= join(root, path);
}
const missing = Object.entries(pin.requiredEnvironment ?? {}).filter(([name]) => !process.env[name]);
if (missing.length > 0) {
  throw new Error(`Set the plugin inputs first:\n${missing.map(([name, what]) => `  ${name}: ${what}`).join('\n')}`);
}

// git runs inside the checkout, so a local source must be absolute.
const given = values.source ?? pin.repository;
const source = !/^[a-z]+:\/\//.test(given) ? resolve(given) : given;
await checkoutHost(source, values.ref ?? pin.revision);
for (const file of await hostTestFiles()) {
  const target = join(work, file);
  await mkdir(dirname(target), { recursive: true });
  await copyFile(join(tests, file), target);
}
for (const [pkg, selection] of Object.entries(pin.packages)) {
  await run('bash', ['scripts/desktop/desktop-go.sh', 'test', pkg, '-count=1', '-v',
    '-timeout', pin.timeout ?? '30m', '-run', values.run ?? selection], { cwd: work });
}
