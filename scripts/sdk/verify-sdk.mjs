// Verifies the vendored Java SDK against sdk.lock.json.
//
// The lock pins the officially released SDK: repository, version, release tag,
// the commit that tag points to, the release asset URL with its SHA-256, and
// every vendored file with its upstream path and SHA-256.
//
// Usage: node scripts/sdk/verify-sdk.mjs [--download]
//
// Without --download the check is offline: it validates the lock and requires
// vendor/ to contain exactly the locked files with the locked hashes. It runs
// before every package build. With --download it also proves, over the network,
// that the lock still describes the release: the tag resolves to the locked
// commit, the release asset has the locked SHA-256, and every locked file at
// that commit has the locked SHA-256 (so the vendored sources are the released
// sources, unmodified).
import { createHash } from 'node:crypto';
import { lstat, mkdtemp, open, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { runCommand } from '../process.mjs';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

export const SDK_REPOSITORY = 'https://github.com/Lithe-IDEA/lithe-plugin-docs';
const RAW_BASE = 'https://raw.githubusercontent.com/Lithe-IDEA/lithe-plugin-docs';
const SDK_SOURCE_UPSTREAM = 'sdk/java/plugin-sdk/src/main/java/';
const SDK_SOURCE_VENDORED = 'vendor/lithe-plugin-sdk-java/src/main/java/';
const FETCH_TIMEOUT_MS = 60_000;
const ASSET_LIMIT_BYTES = 16 * 1024 * 1024;
const FILE_LIMIT_BYTES = 4 * 1024 * 1024;
const LS_REMOTE_TIMEOUT_MS = 60_000;

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const isHash = (value) => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);

function safeRelative(value) {
  return typeof value === 'string' && value !== '' && !value.includes('\\')
    && value.split('/').every((part) => part && part !== '.' && part !== '..');
}

/** The only upstream locations that may be vendored, and where each one lives. */
function vendoredPathFor(upstream) {
  if (upstream === 'LICENSE') return 'vendor/LICENSE';
  if (upstream.startsWith(SDK_SOURCE_UPSTREAM) && upstream.endsWith('.java')) {
    return SDK_SOURCE_VENDORED + upstream.slice(SDK_SOURCE_UPSTREAM.length);
  }
  return null;
}

/** Reads sdk.lock.json and rejects anything that is not a fully pinned release. */
export async function readLock(root = repository) {
  const lock = JSON.parse(await readFile(join(root, 'sdk.lock.json'), 'utf8'));
  const version = lock.version;
  const valid = lock.schemaVersion === 2
    && lock.repository === SDK_REPOSITORY
    && /^\d+\.\d+\.\d+$/.test(version)
    && lock.tag === `sdk-java-v${version}`
    && /^[0-9a-f]{40}$/.test(lock.commit)
    && lock.url === `${SDK_REPOSITORY}/releases/download/${lock.tag}/lithe-plugin-sdk-java-${version}.jar`
    && isHash(lock.sha256)
    && Array.isArray(lock.files) && lock.files.length > 0;
  if (!valid) {
    throw new Error('The SDK lock must pin the released SDK: repository, version, tag, commit, asset url and sha256.');
  }
  const paths = new Set();
  const upstreams = new Set();
  for (const entry of lock.files) {
    const keys = Object.keys(entry ?? {}).sort().join(',');
    if (keys !== 'path,sha256,upstream' || !safeRelative(entry.upstream) || !safeRelative(entry.path)
      || !isHash(entry.sha256) || vendoredPathFor(entry.upstream) !== entry.path
      || paths.has(entry.path) || upstreams.has(entry.upstream)) {
      throw new Error(`Invalid or duplicate SDK source lock entry: ${entry?.path ?? '(no path)'}`);
    }
    paths.add(entry.path);
    upstreams.add(entry.upstream);
  }
  return lock;
}

/** Offline check: vendor/ holds exactly the locked files with the locked hashes. */
export async function verifySdk(root = repository) {
  const lock = await readLock(root);
  const expected = new Set();
  for (const entry of lock.files) {
    const file = join(root, entry.path);
    if (!(await lstat(file)).isFile()) throw new Error(`SDK input is not a regular file: ${entry.path}`);
    if (sha256(await readFile(file)) !== entry.sha256) throw new Error(`SDK SHA-256 mismatch: ${entry.path}`);
    expected.add(entry.path);
  }
  async function walk(directory, prefix) {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      const name = `${prefix}/${entry.name}`;
      if (entry.isDirectory()) await walk(join(directory, entry.name), name);
      else if (!expected.delete(name)) throw new Error(`Unpinned SDK input: ${name}`);
    }
  }
  await walk(join(root, 'vendor'), 'vendor');
  if (expected.size) throw new Error('A pinned SDK source is missing.');
  return lock;
}

/** GET a URL with a deadline and a size cap; redirects are followed. */
export async function fetchBytes(url, limit) {
  const response = await fetch(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS) });
  if (!response.ok) throw new Error(`GET ${url} failed: HTTP ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (bytes.length > limit) throw new Error(`GET ${url} returned more than ${limit} bytes`);
  return bytes;
}

/** Resolves a release tag to the commit it points at, annotated or not. */
export async function resolveTagCommit(remote, tag) {
  const scratch = await mkdtemp(join(tmpdir(), 'lithe-sdk-ls-remote-'));
  try {
    const output = join(scratch, 'refs.txt');
    const handle = await open(output, 'w');
    try {
      await runCommand('git', ['ls-remote', '--tags', remote, `refs/tags/${tag}`, `refs/tags/${tag}^{}`], {
        stdio: ['ignore', handle.fd, 'inherit'],
        timeoutMs: LS_REMOTE_TIMEOUT_MS,
      });
    } finally {
      await handle.close();
    }
    const refs = new Map();
    for (const line of (await readFile(output, 'utf8')).split('\n')) {
      const [commit, name] = line.split('\t');
      if (commit && name) refs.set(name, commit);
    }
    // An annotated tag lists the tag object and, after ^{}, the commit it points at.
    const commit = refs.get(`refs/tags/${tag}^{}`) ?? refs.get(`refs/tags/${tag}`);
    if (!commit) throw new Error(`Tag ${tag} was not found in ${remote}`);
    return commit;
  } finally {
    await rm(scratch, { recursive: true, force: true });
  }
}

/**
 * Network check that the lock still describes the release. The fetchers are
 * parameters so tests can run without the network.
 */
export async function verifySdkRelease(root = repository, {
  fetchAsset = (url) => fetchBytes(url, ASSET_LIMIT_BYTES),
  fetchSource = (url) => fetchBytes(url, FILE_LIMIT_BYTES),
  resolveTag = resolveTagCommit,
} = {}) {
  const lock = await verifySdk(root);
  const commit = await resolveTag(`${lock.repository}.git`, lock.tag);
  if (commit !== lock.commit) {
    throw new Error(`Tag ${lock.tag} points at ${commit}, but the lock pins ${lock.commit}.`);
  }
  const asset = sha256(await fetchAsset(lock.url));
  if (asset !== lock.sha256) {
    throw new Error(`SDK release asset checksum mismatch: expected ${lock.sha256}, got ${asset} (${lock.url})`);
  }
  for (const entry of lock.files) {
    const url = `${RAW_BASE}/${lock.commit}/${entry.upstream}`;
    const digest = sha256(await fetchSource(url));
    if (digest !== entry.sha256) {
      throw new Error(`Vendored ${entry.path} differs from ${entry.upstream} at ${lock.tag}: expected ${entry.sha256}, upstream has ${digest}.`);
    }
  }
  return lock;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  if (args.some((arg) => arg !== '--download') || args.length > 1) {
    console.error('Usage: node scripts/sdk/verify-sdk.mjs [--download]');
    process.exit(2);
  }
  if (args.length) {
    const lock = await verifySdkRelease();
    console.log(`Vendored Java SDK ${lock.version} matches release ${lock.tag} (${lock.commit}) and its published asset.`);
  } else {
    await verifySdk();
    console.log('Vendored Java SDK source hashes verified.');
  }
}
