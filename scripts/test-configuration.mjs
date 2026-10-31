#!/usr/bin/env node
// Explicit real-tool integration. Every native settings file and HOME is isolated.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { BackendClient, bounded } from './backend-client.mjs';
import { bundleManifest } from './bundle-manifest.mjs';
import { coreCommand } from './core-client.mjs';
import { withInterrupts } from './process.mjs';
import { createReport } from './report.mjs';

const pkg = resolve(process.env.LITHE_TEST_PACKAGE ?? resolve('dist/package', 'io.github.lithe-idea.maven-build'));
const java = process.env.LITHE_TEST_JAVA;
const maven = process.env.LITHE_TEST_MAVEN;
assert.ok(java && maven, 'Set explicit LITHE_TEST_JAVA and LITHE_TEST_MAVEN paths.');
const manifest = JSON.parse(readFileSync(join(pkg, 'plugin.json'), 'utf8'));
const before = bundleManifest(pkg);
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'lithe-maven-configuration-')));
const workspace = join(scratch, 'workspace');
const root = join(workspace, 'nested');
const home = join(scratch, 'home');
const user = join(scratch, 'user settings.xml');
const installation = join(scratch, 'global.xml');
const state = join(scratch, 'state');
const repository = join(scratch, 'global-repository');
const report = createReport('native-maven-configuration');
const clients = new Set();
const hash = bytes => 'sha256:' + createHash('sha256').update(bytes).digest('hex');
const request = options => ({ buildSystemID: 'maven', root: 'nested', options,
  optionArguments: [], environment: {}, tool: { executable: maven } });
const payload = value => ({ manifest, buildSystemID: 'maven', request: value });
async function start(version = 7) {
  const client = new BackendClient({ java, jar: join(pkg, 'lib/lithe-maven-backend.jar'), state,
    javaOptions: [`-Duser.home=${home}`],
    env: { ...process.env, MAVEN_OPTS: `-Duser.home=${home}`, JAVA_HOME: resolve(java, '../..') } });
  clients.add(client);
  assert.equal((await client.initialize(workspace, [version])).capabilities.experimental.litheBuild.version, version);
  return client;
}

await withInterrupts(async signal => {
  const stop = () => { for (const client of clients) client.kill(); };
  signal.addEventListener('abort', stop, { once: true });
  try {
    mkdirSync(join(root, '.mvn'), { recursive: true });
    mkdirSync(home);
    // An invalid POM proves that inspection exits before reactor import.
    writeFileSync(join(root, 'pom.xml'), '<this is intentionally not a POM');
    writeFileSync(join(root, '.mvn/maven.config'), `-gs\n${installation}\n`);
    writeFileSync(installation, `<settings><localRepository>${repository}</localRepository></settings>`);
    writeFileSync(user, '<settings><!--keep--><activeProfiles><activeProfile>ci</activeProfile></activeProfiles><servers><server><id>fixture</id><password>fixture-secret-sentinel</password></server></servers></settings>');
    const sourcesBefore = [readFileSync(user), readFileSync(installation)];
    const client = await start();
    let snapshot;
    await report.step('selected Maven exports native sources before reading a broken POM', async () => {
      const plan = coreCommand('build.configurationPlan', payload({ root: 'nested', options: { settingsFile: user }, tool: { executable: maven } })).plan;
      assert.equal(plan.provider.method, 'lithe/build/configuration');
      const raw = await client.request(plan.provider.method, plan.provider.params);
      snapshot = coreCommand('build.decodeConfiguration', payload({ result: raw })).configuration;
      const nativeUser = snapshot.sources.find(source => source.id === 'user');
      const global = snapshot.sources.find(source => source.id === 'installation');
      assert.equal(nativeUser.fields.find(field => field.id === 'localRepository').value, null);
      assert.equal(nativeUser.fields.find(field => field.id === 'localRepository').effectiveValue, repository);
      assert.equal(global.writable, false);
      assert.equal(nativeUser.revision, hash(sourcesBefore[0]));
      assert.ok(!JSON.stringify(snapshot).includes('fixture-secret-sentinel'));
      assert.deepEqual([readFileSync(user), readFileSync(installation)], sourcesBefore);
      assert.equal(existsSync(join(root, 'target')), false);
      assert.equal(existsSync(repository), false, 'Reading inherited configuration must not create its repository');
    });
    await report.step('Core edits the approved native field and Maven re-reads the saved source', async () => {
      const source = snapshot.sources.find(source => source.id === 'user');
      const replacement = coreCommand('build.editConfigurationDocument', payload({ snapshot,
        edits: { sourceID: 'user', revision: source.revision, values: { localRepository: join(scratch, 'saved-repository') } },
        document: sourcesBefore[0].toString('utf8') })).edit;
      assert.equal(hash(readFileSync(user)), source.revision);
      writeFileSync(user, replacement.document);
      assert.ok(replacement.document.includes('<!--keep-->'));
      assert.ok(replacement.document.includes('<password>fixture-secret-sentinel</password>'));
      const updated = await client.request('lithe/build/configuration', request({ settingsFile: user }));
      const field = updated.sources.find(source => source.id === 'user').fields.find(field => field.id === 'localRepository');
      assert.equal(field.value, join(scratch, 'saved-repository'));
      assert.equal(field.effectiveValue, field.value);
      assert.ok(!JSON.stringify(updated).includes('fixture-secret-sentinel'));
      assert.deepEqual(readFileSync(installation), sourcesBefore[1]);
      assert.equal(existsSync(join(scratch, 'saved-repository')), false, 'Readback must not initialize the newly selected repository');
    });
    await report.step('effective CLI overrides remain distinct from the editable native value', async () => {
      const updated = await client.request('lithe/build/configuration', request({ settingsFile: user,
        localRepository: join(scratch, 'cli-repository'), profiles: ['cli-profile'] }));
      const fields = updated.sources.find(source => source.id === 'user').fields;
      const repositoryField = fields.find(field => field.id === 'localRepository');
      assert.equal(repositoryField.value, join(scratch, 'saved-repository'));
      assert.equal(repositoryField.effectiveValue, join(scratch, 'cli-repository'));
      assert.equal(repositoryField.effectiveSource, 'commandLine');
      assert.deepEqual(fields.find(field => field.id === 'activeProfiles').value, ['ci']);
      assert.ok(fields.find(field => field.id === 'activeProfiles').effectiveValue.includes('cli-profile'));
      assert.equal(existsSync(join(scratch, 'cli-repository')), false, 'CLI overrides must remain facts without repository writes');
    });
    await report.step('older wire versions refuse configuration instead of invoking another method', async () => {
      const old = await start(6);
      const response = await old.send('lithe/build/configuration', request({ settingsFile: user })).response;
      assert.equal(response.error.code, -32601);
      await old.close(); clients.delete(old);
    });
    await report.step('shutdown cleans probe state and keeps the installed package unchanged', async () => {
      await client.close(); clients.delete(client);
      assert.deepEqual(bundleManifest(pkg), before);
      const entries = await import('node:fs/promises').then(fs => fs.readdir(state));
      assert.equal(entries.some(name => name.startsWith('configuration-')), false);
    });
  } finally {
    signal.removeEventListener('abort', stop);
    for (const client of clients) {
      try { await client.close(); }
      catch { client.kill(); await bounded(client.exited, 'configuration backend cleanup'); }
    }
    rmSync(scratch, { recursive: true, force: true });
  }
}).catch(error => { report.failure('native configuration integration', error); process.exitCode = 1; }).finally(() => report.write());
