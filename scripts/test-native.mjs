#!/usr/bin/env node
// A real Java backend with an instrumented Maven process. No shell plans or network.
import assert from 'node:assert/strict';
import { appendFileSync, chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { BackendClient, bounded } from './backend-client.mjs';
import { bundleManifest } from './bundle-manifest.mjs';
import { withInterrupts } from './process.mjs';
import { createReport } from './report.mjs';

const id = 'io.github.lithe-idea.maven-build';
const pkg = resolve(process.env.LITHE_TEST_PACKAGE ?? resolve('dist/package', id));
const before = bundleManifest(pkg);
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'lithe-maven-native-')));
const workspace = join(scratch, 'workspace');
const root = join(workspace, 'nested');
const state = join(scratch, 'state');
const controlFile = join(scratch, 'control.json');
const log = join(scratch, 'runs.log');
const executable = join(scratch, 'mvn');
const clients = new Set();
const report = createReport('native-maven');
const runs = () => existsSync(log) ? readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : [];
const executionRuns = () => runs().filter(run => !run.argv.includes('pre-clean')).map(run => ({ ...run,
  instrumentation: run.argv.filter(arg => ['-Dmaven.ext.class.path=', '-Dlithe.reportOutput=', '-Dlithe.reportWorkspace=', '-Dlithe.testProject='].some(prefix => arg.startsWith(prefix))),
  argv: run.argv.filter(arg => !['-Dmaven.ext.class.path=', '-Dlithe.reportOutput=', '-Dlithe.reportWorkspace=', '-Dlithe.testProject='].some(prefix => arg.startsWith(prefix))),
}));
const control = value => writeFileSync(controlFile, JSON.stringify(value));
const alive = pid => { try { process.kill(pid, 0); return true; } catch (error) { if (error.code === 'ESRCH') return false; throw error; } };
async function until(condition, description) {
  const deadline = performance.now() + 10000;
  while (!condition()) {
    if (performance.now() > deadline) throw new Error(`Timed out: ${description}`);
    // Bounded observation of an OS process/file boundary; successful RPCs use promises.
    await new Promise(done => setTimeout(done, 20));
  }
}
const params = (extra = {}) => ({ buildSystemID: 'maven', root: 'nested', options: {}, optionArguments: [], environment: {},
  tool: { executable }, userArguments: [], ...extra });
const selected = { name: 'core', path: 'nested/core' };
const exact = (suite, method) => '-Dtest=%regex[\\Q' + suite.replaceAll('.', '/') + '.class\\E#\\Q' + method + '\\E(?:\\(.*\\))?(?:\\[.*\\])?]';
async function start() {
  const client = new BackendClient({ java: process.env.LITHE_TEST_JAVA ?? 'java', jar: join(pkg, 'lib/lithe-maven-backend.jar'),
    state, javaOptions: [`-Duser.home=${join(scratch, 'home')}`],
    env: { ...process.env, FAKE_MAVEN_CONTROL: controlFile, FAKE_MAVEN_LOG: log } });
  clients.add(client);
  assert.equal((await client.initialize(workspace, [9])).capabilities.experimental.litheBuild.version, 9);
  return client;
}
await withInterrupts(async (signal) => {
  const stop = () => { for (const client of clients) client.kill(); };
  signal.addEventListener('abort', stop, { once: true });
  try {
    mkdirSync(workspace);
    cpSync(resolve('test/fixtures/reactor'), root, { recursive: true });
    writeFileSync(executable, `#!/bin/sh\nexec '${process.execPath.replaceAll("'", "'\\''")}' '${resolve('test/fixtures/fake-maven-backend.mjs').replaceAll("'", "'\\''")}' "$@"\n`);
    chmodSync(executable, 0o755);
    control({});
    let client = await start();
    await report.step('load the real backend model without a native command fallback', async () => {
      const result = await client.request('lithe/build/model', params());
      assert.equal(result.projects.length, 3);
      assert.equal(runs().length, 1);
      const dependencies = await client.request('lithe/build/dependencies', params({ project: { name: 'app', path: 'nested/app' } }));
      assert.equal(dependencies.dependencies[0].name, 'core', 'nested workspace-relative dependency selection');
    });
    await report.step('native lifecycle uses current nested project, options and exact arguments', async () => {
      const result = await client.request('lithe/build/execute', params({ task: 'package', project: selected, operationID: 'package',
        options: { profiles: ['ci'], offline: true, skipTests: true, settingsFile: '/fixture settings.xml', localRepository: '/fixture repository' },
        userArguments: ['-Dmessage=two words'] }));
      assert.equal(result.exitCode, 0);
      const run = executionRuns().at(-1);
      assert.equal(run.cwd, root);
      assert.equal(run.instrumentation.length, 4);
      assert.ok(run.instrumentation.some(arg => arg.startsWith('-Dlithe.reportOutput=' + state + '/execution-')));
      assert.deepEqual(result.testResults.tests, []);
      assert.deepEqual(run.argv, ['-B', '-ntp', '-Pci', '-s', '/fixture settings.xml', '-Dmaven.repo.local=/fixture repository', '-o', '-DskipTests', '-pl', 'core', 'package', '-Dmessage=two words']);
      const progress = client.notifications.filter(message => message.method === 'lithe/build/progress' && message.params.operationID === 'package');
      assert.equal(progress.map(message => message.params.output ?? '').join(''), result.output);
      assert.match(result.output, /\[WARNING\]/);
    });
    await report.step('model goals are checked against the current Maven extension output', async () => {
      const modelTask = { name: 'compiler:compile', path: 'nested/core', properties: { goal: 'org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile' } };
      const result = await client.request('lithe/build/execute', params({ task: 'plugin-goal', project: selected, modelTask, operationID: 'goal' }));
      assert.equal(result.exitCode, 0);
      assert.ok(executionRuns().at(-1).argv.includes(modelTask.properties.goal));
      const count = executionRuns().length;
      await assert.rejects(client.request('lithe/build/execute', params({ task: 'plugin-goal', project: selected,
        modelTask: { ...modelTask, properties: { goal: 'unpublished:goal' } }, operationID: 'forged-goal' })), /absent from the current Maven model/);
      assert.equal(executionRuns().length, count);
    });
    await report.step('Surefire class/method selection stays exact and builds reactor prerequisites', async () => {
      const result = await client.request('lithe/build/test', params({ project: selected, suite: 'demo.core.GreeterTest', case: 'greets',
        options: { skipTests: true }, operationID: 'exact-test' }));
      assert.equal(result.exitCode, 0);
      assert.equal(result.testResults.tests[0].suite, 'demo.core.GreeterTest');
      assert.equal(result.testResults.tests[0].project, 'nested/core');
      assert.deepEqual(executionRuns().at(-1).argv, ['-B', '-ntp', '-pl', 'core', '-am', 'test', exact('demo.core.GreeterTest', 'greets'), '-Dsurefire.failIfNoSpecifiedTests=false']);
      const count = executionRuns().length;
      await assert.rejects(client.request('lithe/build/test', params({ project: selected, suite: '*', operationID: 'wildcard' })), /exact Surefire/);
      await assert.rejects(client.request('lithe/build/test', params({ project: selected, case: 'greets', operationID: 'no-suite' })), /needs its suite/);
      assert.equal(executionRuns().length, count);
    });
    await report.step('opaque JDT identities reach Surefire exactly and reject unsupported or missing selections', async () => {
      const result = await client.request('lithe/build/test', params({ project: selected,
        selectorKind: 'java.test.fullName', selectors: ['demo.core.GreeterTest#greets()'], operationID: 'semantic-test' }));
      assert.equal(result.exitCode, 0);
      assert.ok(executionRuns().at(-1).argv.includes(exact('demo.core.GreeterTest', 'greets')));
      await client.request('lithe/build/test', params({ project: selected,
        selectorKind: 'java.test.fullName', selectors: ['示例.测试#校验()'], operationID: 'unicode-semantic-test' }));
      assert.ok(executionRuns().at(-1).argv.includes(exact('示例.测试', '校验')));

      for (const selectors of [['*'], ['demo.core.GreeterTest#greets(java.lang.String)']]) {
        await assert.rejects(client.request('lithe/build/test', params({ project: selected,
          selectorKind: 'java.test.fullName', selectors, operationID: 'invalid-semantic-test' })));
      }
    });
    await report.step('an old or empty Surefire report cannot make an unknown selector pass', async () => {
      control({ execution: { noReport: true } });
      await assert.rejects(client.request('lithe/build/test', params({ project: selected, selectorKind: 'java.test.fullName',
        selectors: ['demo.core.GreeterTest#greets()'], operationID: 'stale-semantic-test' })), /no fresh result/);
      await assert.rejects(client.request('lithe/build/test', params({ project: selected, suite: 'demo.core.GreeterTest', case: 'greets',
        operationID: 'stale-test' })), /no fresh result/);
      control({});
    });
    await report.step('a fully qualified test selector cannot match a different package suffix', async () => {
      control({ execution: { reportSuite: 'other.demo.core.GreeterTest' } });
      await assert.rejects(client.request('lithe/build/test', params({ project: selected, suite: 'demo.core.GreeterTest', case: 'greets',
        operationID: 'wrong-package-test' })), /no fresh result/);
      control({});
    });
    await report.step('another reactor project cannot satisfy an exact report-derived test selection', async () => {
      control({ execution: { reportProject: 'nested/app' } });
      await assert.rejects(client.request('lithe/build/test', params({ project: selected, suite: 'demo.core.GreeterTest',
        case: 'greets', operationID: 'wrong-project-report' })), /no fresh result/);
      control({});
    });
    await report.step('malformed or external-entity reports fail closed and release operation state', async () => {
      for (const badReport of ['broken', 'external-entity']) {
        control({ execution: { badReport } });
        await assert.rejects(client.request('lithe/build/test', params({ project: selected,
          suite: 'demo.core.GreeterTest', operationID: 'bad-' + badReport })));
        assert.deepEqual((await (await import('node:fs/promises')).readdir(state)).filter(name => name.startsWith('execution-')), []);
      }
      control({});
    });
    await report.step('a custom goal preserves user arguments and an unknown project cannot execute', async () => {
      await client.request('lithe/build/execute', params({ task: 'goal', operationID: 'custom', userArguments: ['validate', '-Dvalue=a b'] }));
      assert.deepEqual(executionRuns().at(-1).argv, ['-B', '-ntp', 'validate', '-Dvalue=a b']);
      const count = executionRuns().length;
      for (const project of [{ name: 'missing', path: 'nested/core' }, { name: 'core', path: '../escape' }]) {
        await assert.rejects(client.request('lithe/build/execute', params({ task: 'compile', project, operationID: 'bad-project' })));
      }
      assert.equal(executionRuns().length, count);
    });
    await report.step('a tool failure returns an exit status and bounded output', async () => {
      control({ execution: { exit: 7 } });
      const result = await client.request('lithe/build/execute', params({ task: 'compile', operationID: 'failure' }));
      assert.equal(result.exitCode, 7);
      assert.match(result.output, /fake failure/);
      control({});
    });
    await report.step('cancelling a native build stops Maven and its owned child', async () => {
      const gate = join(scratch, 'blocked');
      control({ execution: { gate, child: true } });
      const pending = client.send('lithe/build/execute', params({ task: 'compile', operationID: 'cancel-build' }));
      try {
        await until(() => existsSync(gate + '.started'), 'Maven to reach its controlled gate');
        const pid = Number(readFileSync(gate + '.started', 'utf8').trim());
        const child = Number(readFileSync(log + '.children', 'utf8').trim().split('\n').at(-1));
        client.cancel(pending.id);
        assert.equal((await pending.response).error?.code, -32800);
        await until(() => !alive(pid) && !alive(child), 'cancelled Maven process tree to end');
      } finally { writeFileSync(gate, 'release'); control({}); }
      assert.equal((await client.request('lithe/build/execute', params({ task: 'validate', operationID: 'after-cancel' }))).exitCode, 0);
    });
    await report.step('an oversized transcript fails and releases its Maven process', async () => {
      control({ execution: { flood: true } });
      await assert.rejects(client.request('lithe/build/execute', params({ task: 'compile', operationID: 'overflow' })), /transcript/);
      const pid = executionRuns().at(-1).pid;
      await until(() => !alive(pid), 'oversized output process to end');
      control({});
    });
    await report.step('test teardown also reclaims SDK tools in a separate process group', async () => {
      const other = await start();
      const gate = join(scratch, 'teardown-blocked');
      control({ execution: { gate, child: true } });
      const pending = other.send('lithe/build/execute', params({ task: 'compile', operationID: 'teardown' }));
      // Register rejection handling before the owner is deliberately killed.
      const rejected = assert.rejects(pending.response, /backend exited/);
      try {
        await until(() => existsSync(gate + '.started'), 'the separately grouped tool to start');
        const pid = Number(readFileSync(gate + '.started', 'utf8').trim());
        const child = Number(readFileSync(log + '.children', 'utf8').trim().split('\n').at(-1));
        other.kill();
        await rejected;
        await bounded(other.exited, 'the stopped backend');
        await until(() => !alive(pid) && !alive(child), 'teardown to reclaim the detached SDK process group');
        clients.delete(other);
      } finally { writeFileSync(gate, 'release'); control({}); other.kill(); }
    });
    await report.step('ending the backend keeps the installed package byte-for-byte read-only', async () => {
      assert.equal(await client.close(), 0);
      clients.delete(client);
      assert.deepEqual(bundleManifest(pkg), before);
    });
  } catch (error) {
    process.exitCode = 1;
    report.failure('native Maven lane failed', error);
    console.error(error.stack);
  } finally {
    signal.removeEventListener('abort', stop);
    stop();
    for (const client of clients) await bounded(client.exited, 'backend cleanup').catch(error => { process.exitCode = 1; console.error(error); });
    rmSync(scratch, { recursive: true, force: true });
    report.write();
  }
});
