// Runs this plugin's host end-to-end tests against a pinned Lithe desktop host.
//
//   node scripts/e2e-host.mjs [--source <Lithe git URL or path>] [--ref <commit or branch>] [--run <regex>] [--suite <name>]
//
// The tests in e2e/host/ drive the real plugin package through the desktop host's own test
// harness (an internal Go package), so they cannot be built in this repository. Each run checks
// out the host revision pinned in e2e/host/lithe-host.json into the disposable .artifacts/lithe-host
// (desktop/ and scripts/ only) and overlays the plugin's test files while running
// the host's scripts/desktop/desktop-go.sh test, which builds the Core
// revision that host pins. The host repository never reads this repository: the tests live here
// and the pin says which host they last passed on. Bumping the pin can require adapting the tests
// to changes in the host's internal harness.
//
// Every variable listed in lithe-host.json "requiredEnvironment" must be set, because the tests
// skip without their plugin input and a skipped run proves nothing. "repositoryEnvironment" maps a
// variable to a path in this repository, used when the variable is not set. --source and --ref override
// the pin, for example to try an unmerged host branch; --run overrides the test selection.
// --suite selects a named test group and its required plugin inputs.
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { runCommand, withInterrupts } from './process.mjs';

const root = fileURLToPath(new URL('..', import.meta.url));
const tests = join(root, 'e2e', 'host');
const work = join(root, '.artifacts', 'lithe-host');

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
async function checkoutHost(source, ref, signal) {
  const run = (args) => runCommand('git', args, { timeoutMs: 120_000, signal });
  await mkdir(work, { recursive: true });
  await run(['init', '-q', work]);
  await run(['-C', work, 'sparse-checkout', 'set', '--cone', 'desktop', 'scripts']);
  // A named remote, because the partial clone fetches missing blobs from it later.
  await run(['-C', work, 'config', 'remote.origin.url', source]);
  await run(['-C', work, 'fetch', '-q', '--depth', '1', '--filter=blob:none', 'origin', ref]);
  await run(['-C', work, 'checkout', '-q', '--force', '--detach', 'FETCH_HEAD']);
  // Discard test files left by earlier versions of this runner in its disposable checkout.
  await run(['-C', work, 'clean', '-q', '-f', '-d', '--', 'desktop']);
}

const { values } = parseArgs({
  options: { source: { type: 'string' }, ref: { type: 'string' }, run: { type: 'string' }, suite: { type: 'string' } },
});
const pin = JSON.parse(await readFile(join(tests, 'lithe-host.json'), 'utf8'));
for (const [name, path] of Object.entries(pin.repositoryEnvironment ?? {})) {
  process.env[name] ||= join(root, path);
}
const suite = values.suite && pin.suites?.[values.suite];
if (values.suite && !suite) throw new Error(`Unknown host E2E suite: ${values.suite}`);
const required = suite?.requiredEnvironment ?? Object.keys(pin.requiredEnvironment ?? {});
const missing = required.filter((name) => !process.env[name]);
if (missing.length > 0) {
  throw new Error(`Set the plugin inputs first:\n${missing.map((name) => `  ${name}: ${pin.requiredEnvironment[name]}`).join('\n')}`);
}

// git runs inside the checkout, so a local source must be absolute.
const given = values.source ?? pin.repository;
const source = !/^[a-z]+:\/\//.test(given) ? resolve(given) : given;
await withInterrupts(async (signal) => {
  await checkoutHost(source, values.ref ?? pin.revision, signal);
  const overlay = join(root, '.artifacts', 'lithe-host-overlay.json');
  const files = await hostTestFiles();
  await writeFile(overlay, JSON.stringify({
    Replace: Object.fromEntries(files.map((file) => [join(work, file), join(tests, file)])),
  }));
  for (const [pkg, selection] of Object.entries(suite?.packages ?? pin.packages)) {
    await runCommand('bash', ['scripts/desktop/desktop-go.sh', 'test', '-overlay', overlay, pkg,
      '-count=1', '-v', '-timeout', pin.timeout ?? '20m', '-run', values.run ?? selection],
    { cwd: work, timeoutMs: 1_200_000, signal });
  }
});
