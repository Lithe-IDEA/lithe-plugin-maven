import { createHash } from 'node:crypto';
import { lstat, readFile, readdir } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..');

/** Verify every vendored production source before building the backend. */
export async function verifySdk(root = repository) {
  const lock = JSON.parse(await readFile(join(root, 'sdk.lock.json'), 'utf8'));
  if (lock.schemaVersion !== 1 || !/^[0-9a-f]{40}$/.test(lock.source?.revision) || !Array.isArray(lock.files) || lock.source.repository !== 'https://github.com/Lithe-IDEA/lithe-plugin-docs') {
    throw new Error('The SDK lock must identify its upstream base and every source hash.');
  }
  const expected = new Set();
  for (const entry of lock.files) {
    if (!entry.path?.startsWith('vendor/') || entry.path.includes('\\')
      || entry.path.split('/').some((part) => !part || part === '.' || part === '..')
      || !/^[0-9a-f]{64}$/.test(entry.sha256) || expected.has(entry.path)
      || (entry.localChanges === true && (!Object.hasOwn(entry, 'upstreamSha256') || (entry.upstreamSha256 !== null && !/^[0-9a-f]{64}$/.test(entry.upstreamSha256))))) {
      throw new Error('Invalid or duplicate SDK source lock entry.');
    }
    const file = join(root, entry.path);
    if (!(await lstat(file)).isFile()) throw new Error(`SDK input is not a regular file: ${entry.path}`);
    const digest = createHash('sha256').update(await readFile(file)).digest('hex');
    if (digest !== entry.sha256) throw new Error(`SDK SHA-256 mismatch: ${entry.path}`);
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
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await verifySdk();
  console.log('Vendored Java SDK source hashes verified.');
}
