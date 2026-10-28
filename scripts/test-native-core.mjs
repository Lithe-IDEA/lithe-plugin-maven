#!/usr/bin/env node
// The production plugin, current Core C ABI, and a real Maven reactor in one workflow.
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { BackendClient, bounded } from './backend-client.mjs';
import { coreCommand } from './core-client.mjs';
import { bundleManifest } from './bundle-manifest.mjs';
import { createReport } from './report.mjs';
import { mavenTimeout, withInterrupts } from './process.mjs';

const id = 'io.github.lithe-idea.maven-build';
const pkg = resolve(process.env.LITHE_TEST_PACKAGE ?? resolve('dist/package', id));
const before = bundleManifest(pkg);
const manifest = JSON.parse(readFileSync(join(pkg, 'plugin.json')));
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'lithe-maven-core-')));
const workspace = join(scratch, 'workspace');
const root = 'nested';
const report = createReport('native-maven-core');
let client;
let model;
let compilePlan;
let testPlan;
const options = { profiles: ['ci'] };
const configured = process.env.MVN ?? 'mvn';
let maven = configured;
if (!configured.includes('/')) {
  const probe = spawnSync('/bin/sh', ['-c', 'command -v "$0"', configured], { encoding: 'utf8', timeout: 5000 });
  assert.equal(probe.status, 0, 'Maven must be installed for this integration lane');
  maven = probe.stdout.trim();
}
const build = (command, request) => coreCommand(command, { manifest, buildSystemID: 'maven', request });
const planned = extra => ({ root, options, pluginDirectory: pkg, workspaceStateDirectory: join(scratch, 'state'), tool: { executable: maven }, ...extra });
const send = async (plan, operationID) => client.request(plan.provider.method, { ...plan.provider.params, operationID });

await withInterrupts(async (signal) => {
  const stop = () => client?.kill();
  signal.addEventListener('abort', stop, { once: true });
  try {
    mkdirSync(workspace);
    cpSync(resolve('test/fixtures/reactor'), join(workspace, root), { recursive: true });
    client = new BackendClient({ java: process.env.LITHE_TEST_JAVA ?? 'java', jar: join(pkg, 'lib/lithe-maven-backend.jar'),
      state: join(scratch, 'state'), timeoutMs: mavenTimeout() });
    await report.step('Core admits the new Maven capability and produces only Provider plans', async () => {
      coreCommand('plugins.declarative.validate', { manifest });
      assert.equal((await client.initialize(workspace, [3])).capabilities.experimental.litheBuild.version, 3);
      for (const task of manifest.buildSystems[0].tasks) { assert.equal(task.source, 'provider'); assert.deepEqual(task.arguments, []); }
      assert.equal(manifest.buildSystems[0].test.source, 'provider');
    });
    await report.step('read and normalize the real Maven reactor through Core', async () => {
      const plan = build('build.modelPlan', planned({})) .plan;
      const result = await send(plan, 'model');
      model = build('build.decodeModel', { workspaceRoot: workspace, root, options, result }).model;
      assert.deepEqual(model.projects.map(project => project.name).sort(), ['app', 'core', 'reactor']);
      assert.ok(model.contentRoots.some(content => content.path === 'nested/core/src/main/java'));
    });
    const project = model.projects.find(project => project.name === 'core');
    await report.step('Core plans a module build, Maven executes it, and the class file exists', async () => {
      compilePlan = build('build.taskPlan', planned({ task: 'compile', project })).plan;
      assert.equal(compilePlan.provider.method, 'lithe/build/execute');
      assert.equal(compilePlan.arguments, undefined);
      const result = await send(compilePlan, 'compile');
      assert.equal(result.exitCode, 0, result.output);
      assert.ok(existsSync(join(workspace, 'nested/core/target/classes/demo/core/Greeter.class')));
      const decoded = build('build.decodeDiagnostics', { workspaceRoot: workspace, root, output: result.output });
      assert.deepEqual(decoded.diagnostics, []);
    });
    await report.step('Core and Surefire run one exact method and decode one report result', async () => {
      testPlan = build('build.testPlan', planned({ project, suite: 'demo.core.GreeterTest', case: 'greetsByName', userArguments: [] })).plan;
      assert.equal(testPlan.provider.method, 'lithe/build/test');
      const result = await send(testPlan, 'exact-test');
      assert.equal(result.exitCode, 0, result.output);
      const tests = build('build.decodeTestResults', { workspaceRoot: workspace, root, project: project.path, projects: model.projects.map(project => project.path),
        suite: 'demo.core.GreeterTest', output: result.output }).results;
      assert.equal(tests.summary.total, 1, JSON.stringify(tests));
      assert.equal(tests.summary.failed, 0, JSON.stringify(tests));
      assert.equal(tests.tests[0].case, 'greetsByName');
      const progress = client.notifications.filter(message => message.method === 'lithe/build/progress' && message.params.operationID === 'exact-test');
      assert.equal(progress.map(message => message.params.output ?? '').join(''), result.output);
    });
    await report.step('an unknown method cannot pass with a fresh empty Surefire report', async () => {
      const plan = build('build.testPlan', planned({ project, suite: 'demo.core.GreeterTest', case: 'doesNotExist', userArguments: [] })).plan;
      await assert.rejects(send(plan, 'unknown-test'), /no fresh result/);
    });
    await report.step('publish the same normalized Maven facts and effective options to a declared consumer', () => {
      const consumer = JSON.parse(JSON.stringify(manifest));
      consumer.id = 'org.example.maven-input-consumer';
      consumer.requires.buildProviders = 6;
      delete consumer.buildSystems[0].modelSharing;
      delete consumer.buildSystems[0].configuration;
      consumer.buildSystems[0].modelInputs = [{ pluginID: id, buildSystemID: 'maven' }];
      const snapshot = coreCommand('build.resolveModelInputs', { manifest: consumer, buildSystemID: 'maven', request: { revision: 11,
        sources: [{ manifest, instances: [{ buildSystemID: 'maven', root, state: 'ready', model, options }] }] } }).modelInputs;
      const facts = snapshot.inputs[0].instances[0];
      assert.equal(facts.model.projects.length, 3);
      assert.deepEqual(facts.options.profiles, ['ci']);
      assert.equal(facts.model.tasks, undefined);
      assert.equal(facts.model.run, undefined);
      assert.equal(facts.model.optionValues, undefined);
      assert.equal(facts.options.settingsFile, undefined);
      const plan = coreCommand('build.modelPlan', { manifest: consumer, buildSystemID: 'maven', request: planned({ modelInputs: snapshot }) }).plan;
      assert.deepEqual(plan.provider.params.modelInputs, snapshot);
    });
    await report.step('close the backend and preserve the entire plugin package', async () => {
      assert.equal(await client.close(), 0);
      assert.deepEqual(bundleManifest(pkg), before);
    });
  } catch (error) {
    process.exitCode = 1;
    report.failure('real Core/Maven lane failed', error);
    console.error(error.stack);
    if (client) console.error(client.stderr);
  } finally {
    signal.removeEventListener('abort', stop);
    stop();
    if (client) await bounded(client.exited, 'backend cleanup').catch(error => { process.exitCode = 1; console.error(error); });
    rmSync(scratch, { recursive: true, force: true });
    report.write();
  }
});
