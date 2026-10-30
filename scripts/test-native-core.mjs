#!/usr/bin/env node
// The production plugin, current Core C ABI, and a real Maven reactor in one workflow.
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
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
      assert.equal((await client.initialize(workspace, [9])).capabilities.experimental.litheBuild.version, 9);
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
    await report.step('Maven configured compiler executions own Java requirements across profiles and modules', async () => {
      const pom = join(workspace, root, 'pom.xml'), modulePom = join(workspace, root, 'core/pom.xml');
      const original = readFileSync(pom, 'utf8'), originalModule = readFileSync(modulePom, 'utf8');
      const version = project => project.properties['java.minimumVersion'];
      try {
        const read = async profiles => build('build.decodeModel', { workspaceRoot: workspace, root,
          result: await send(build('build.modelPlan', planned({ options: { profiles, offline: true } })).plan, 'compiler-model') }).model;
        assert.equal(version(model.projects.find(project => project.name === 'core')), '8');
        writeFileSync(pom, original.replace('</profiles>', `<profile><id>compiler-floor</id><properties>
          <maven.compiler.release>17</maven.compiler.release><maven.compiler.testRelease>21</maven.compiler.testRelease>
          </properties></profile></profiles>`));
        let current = await read(['compiler-floor']);
        assert.equal(version(current.projects.find(project => project.name === 'core')), '21', 'test release is part of the effective compiler requirement');
        assert.equal(version(current.projects.find(project => project.name === 'app')), '21');
        writeFileSync(modulePom, originalModule.replace('</project>', `<build><plugins><plugin>
          <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
          <configuration><release>11</release></configuration><executions><execution><id>extra-compile</id>
          <phase>compile</phase><goals><goal>compile</goal></goals><configuration><release>23</release></configuration>
          </execution></executions></plugin></plugins></build></project>`));
        current = await read(['compiler-floor']);
        assert.equal(version(current.projects.find(project => project.name === 'core')), '23', 'custom execution configuration is interpreted by Maven');
        assert.equal(version(current.projects.find(project => project.name === 'app')), '21', 'module override does not leak');
        writeFileSync(modulePom, originalModule.replace('</project>', `<build><plugins><plugin>
          <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
          <configuration><release>\${unresolved.compiler.release}</release></configuration>
          </plugin></plugins></build></project>`));
        const absent = await read(['compiler-floor']);
        assert.equal(version(absent.projects.find(project => project.name === 'core')), '21', 'Maven treats an absent expression as unset and retains the configured test release');
        writeFileSync(modulePom, originalModule.replace('</project>', `<build><plugins><plugin>
          <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
          <configuration><release>unsupported-language</release></configuration>
          </plugin></plugins></build></project>`));
        const unknown = await read(['compiler-floor']);
        assert.equal(version(unknown.projects.find(project => project.name === 'core')), undefined, 'an unsupported configured language stays unknown rather than guessing from the parent POM');

        writeFileSync(pom, '<broken><maven.compiler.release>99</maven.compiler.release>');
        const generated = coreCommand('runConfig.generate', { root: workspace, paths: [], javaEntrypoints: { schemaVersion: 1, entries: [] },
          projectOwnership: current.projects.map(project => ({ path: project.path, name: project.name, mavenReactorPath: root,
            minimumJavaVersion: version(project), contentRoots: [] })) }, null);
        assert.equal(generated.toolchainRequirements.toolchains['project-jdk'].minimumVersion, '23');
      } finally { writeFileSync(pom, original); writeFileSync(modulePom, originalModule); }
    });
    await report.step('effective Profile and inherited plugins own framework Run generation without host POM parsing', async () => {
      const pom = join(workspace, root, 'pom.xml');
      const original = readFileSync(pom, 'utf8');
      try {
        writeFileSync(pom, original.replace('</plugins>', `<plugin>
          <groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId><version>0.0.0-fixture</version>
          </plugin></plugins>`).replace('</profiles>', `<profile><id>framework</id><build><plugins><plugin>
          <groupId>io.quarkus.platform</groupId><artifactId>quarkus-maven-plugin</artifactId><version>0.0.0-fixture</version>
          </plugin></plugins></build></profile></profiles>`));
        const read = async profiles => build('build.decodeModel', { workspaceRoot: workspace, root,
          result: await send(build('build.modelPlan', planned({ options: { profiles, offline: true } })).plan, 'framework-model') }).model;
        const inactive = await read([]);
        assert.ok(inactive.projects.every(project => project.properties['run.modelVersion'] === '1'));
        assert.ok(inactive.projects.every(project => project.properties['run.provider'] === undefined), 'plugin management alone is not an applied framework');
        const active = await read(['framework']);
        assert.equal(active.projects.find(project => project.name === 'reactor').properties['run.provider'], undefined, 'aggregators are not services');
        assert.ok(active.projects.filter(project => project.name !== 'reactor').every(project => project.properties['run.provider'] === 'quarkus.maven'), 'Maven applies the inherited active Profile');
        const ownership = active.projects.map(project => ({ path: project.path, name: project.name, mavenReactorPath: root,
          mavenRunProvider: project.properties['run.provider'], contentRoots: [] }));
        writeFileSync(pom, '<deliberately-broken-after-Maven-returned-the-model>');
        const generated = coreCommand('runConfig.generate', { root: workspace, paths: [], javaEntrypoints: { schemaVersion: 1, entries: [], diagnostics: [] }, projectOwnership: ownership }, null);
        assert.deepEqual(generated.generated.configurations.map(item => item.id).sort(), ['quarkus.maven:nested/app', 'quarkus.maven:nested/core']);
        for (const item of generated.generated.configurations) {
          assert.equal(item.cwd, root); assert.equal(item.execution, 'service');
        }
      } finally { writeFileSync(pom, original); }
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
    await report.step('Java preparation preserves Maven filtering, custom roots and main versus test resources', async () => {
      const directory = join(workspace, root, 'core');
      const pom = join(directory, 'pom.xml');
      const original = readFileSync(pom, 'utf8');
      const resources = join(directory, 'custom inputs'), testResources = join(directory, 'test inputs');
      mkdirSync(resources); mkdirSync(testResources);
      writeFileSync(join(resources, 'runtime.properties'), 'selected=${prepared.value}');
      writeFileSync(join(testResources, 'test.properties'), 'test=${prepared.value}');
      try {
        writeFileSync(pom, original.replace('</project>', `<build><resources><resource><directory>custom inputs</directory><filtering>true</filtering></resource></resources>
          <testResources><testResource><directory>test inputs</directory><filtering>true</filtering></testResource></testResources></build>
          <profiles><profile><id>prepared</id><properties><prepared.value>upstream</prepared.value></properties></profile></profiles></project>`));
        const plan = task => build('build.taskPlan', planned({ task, project, options: { profiles: ['prepared'] } })).plan;
        const main = await send(plan('java-prepare-run-main'), 'prepare-main-resources');
        assert.equal(main.exitCode, 0, main.output);
        assert.equal(readFileSync(join(directory, 'target/classes/runtime.properties'), 'utf8'), 'selected=upstream');
        assert.equal(existsSync(join(directory, 'target/test-classes/test.properties')), false);
        const tests = await send(plan('java-prepare-run-test'), 'prepare-test-resources');
        assert.equal(tests.exitCode, 0, tests.output);
        assert.equal(readFileSync(join(directory, 'target/test-classes/test.properties'), 'utf8'), 'test=upstream');
        await assert.rejects(send(build('build.taskPlan', planned({ task: 'java-prepare-run-main', project, userArguments: ['verify'] })).plan,
          'reject-extra-resource-goals'), /no raw goals/);
      } finally { writeFileSync(pom, original); rmSync(resources, { recursive: true, force: true }); rmSync(testResources, { recursive: true, force: true }); }
    });
    await report.step('Core and Surefire run one exact method and decode one report result', async () => {
      testPlan = build('build.testPlan', planned({ project, suite: 'demo.core.GreeterTest', case: 'greetsByName', userArguments: [] })).plan;
      assert.equal(testPlan.provider.method, 'lithe/build/test');
      const result = await send(testPlan, 'exact-test');
      assert.equal(result.exitCode, 0, result.output);
      const tests = build('build.decodeTestResults', { workspaceRoot: workspace, root, project: project.path, projects: model.projects.map(project => project.path),
        suite: 'demo.core.GreeterTest', output: result.output, result: result.testResults }).results;
      assert.equal(tests.summary.total, 1, JSON.stringify(tests));
      assert.equal(tests.summary.failed, 0, JSON.stringify(tests));
      assert.equal(tests.tests[0].case, 'greetsByName');
      const progress = client.notifications.filter(message => message.method === 'lithe/build/progress' && message.params.operationID === 'exact-test');
      assert.equal(progress.map(message => message.params.output ?? '').join(''), result.output);
    });
    await report.step('Core forwards semantic test identities and Maven reports the exact selected method', async () => {
      const selectors = ['demo.core.GreeterTest#greetsByName()'];
      const plan = build('build.testPlan', planned({ project, selectorKind: 'java.test.fullName', selectors, userArguments: [] })).plan;
      assert.deepEqual(plan.provider.params.selectors, selectors);
      assert.equal(plan.provider.params.suite, undefined);
      const result = await send(plan, 'semantic-test');
      assert.equal(result.exitCode, 0, result.output);
      const decoded = build('build.decodeTestResults', { workspaceRoot: workspace, root, project: project.path,
        projects: model.projects.map(value => value.path), result: result.testResults }).results;
      assert.equal(decoded.summary.total, 1);
      assert.equal(decoded.tests[0].case, 'greetsByName');
      assert.equal(decoded.tests[0].project, 'nested/core');
    });
    await report.step('Unicode Java class and method identities survive the full Core Maven report roundtrip', async () => {
      const source = join(workspace, root, 'core/src/test/java/demo/core/测试.java');
      try {
        writeFileSync(source, 'package demo.core; public class 测试 { @org.junit.Test public void 校验() { org.junit.Assert.assertTrue(true); } }');
        const plan = build('build.testPlan', planned({ project, selectorKind: 'java.test.fullName', selectors: ['demo.core.测试#校验()'] })).plan;
        const result = await send(plan, 'unicode-semantic-test');
        assert.equal(result.exitCode, 0, result.output);
        const decoded = build('build.decodeTestResults', { workspaceRoot: workspace, root, project: project.path, result: result.testResults }).results;
        assert.equal(decoded.summary.total, 1);
        assert.equal(decoded.tests[0].suite, 'demo.core.测试');
        assert.equal(decoded.tests[0].case, '校验');
      } finally { rmSync(source, { force: true }); }
    });
    await report.step('an exact class cannot execute a different package with the same class-path suffix', async () => {
      const directory = join(workspace, root, 'core/src/test/java/other/demo/core');
      const source = join(directory, 'GreeterTest.java');
      mkdirSync(directory, { recursive: true });
      try {
        writeFileSync(source, 'package other.demo.core; public class GreeterTest { @org.junit.Test public void greetsByName() { org.junit.Assert.fail("wrong package executed"); } }');
        const plan = build('build.testPlan', planned({ project, selectorKind: 'java.test.fullName', selectors: ['demo.core.GreeterTest'] })).plan;
        const result = await send(plan, 'exact-package-test');
        assert.equal(result.exitCode, 0, result.output);
        assert.deepEqual([...new Set(result.testResults.tests.map(value => value.suite))], ['demo.core.GreeterTest']);
        assert.deepEqual(result.testResults.coverage, [{ project: 'nested/core', suite: 'demo.core.GreeterTest' }]);
      } finally { rmSync(source, { force: true }); }
    });
    await report.step('parameterized invocations preserve the method selector, severity, count and total duration', async () => {
      const source = join(workspace, root, 'core/src/test/java/demo/core/ParameterizedTest.java');
      try {
        writeFileSync(source, `package demo.core; @org.junit.runner.RunWith(org.junit.runners.Parameterized.class)
          public class ParameterizedTest {
            @org.junit.runners.Parameterized.Parameters public static java.util.Collection<Object[]> inputs() {
              return java.util.Arrays.asList(new Object[][] {{1}, {2}}); }
            public ParameterizedTest(int value) {} @org.junit.Test public void checks() { org.junit.Assert.assertTrue(true); }
          }`);
        const plan = build('build.testPlan', planned({ project, selectorKind: 'java.test.fullName', selectors: ['demo.core.ParameterizedTest#checks()'] })).plan;
        const result = await send(plan, 'parameterized-method-test');
        assert.equal(result.exitCode, 0, result.output);
        const decoded = build('build.decodeTestResults', { workspaceRoot: workspace, root, project: project.path, result: result.testResults }).results;
        assert.equal(decoded.summary.total, 1);
        assert.equal(decoded.tests[0].case, 'checks');
        assert.equal(decoded.tests[0].invocations, 2);
        assert.equal(decoded.tests[0].durationMillis, result.testResults.tests.reduce((sum, test) => sum + test.durationMillis, 0));
      } finally { rmSync(source, { force: true }); }
    });
    await report.step('project selection builds prerequisites without running their identical test class', async () => {
      const coreSource = join(workspace, root, 'core/src/test/java/demo/core/GreeterTest.java');
      const original = readFileSync(coreSource, 'utf8');
      const directory = join(workspace, root, 'app/src/test/java/demo/core');
      const appSource = join(directory, 'GreeterTest.java');
      const appPom = join(workspace, root, 'app/pom.xml');
      const originalPom = readFileSync(appPom, 'utf8');
      mkdirSync(directory, { recursive: true });
      try {
        writeFileSync(coreSource, original.replace(/}\s*$/, '@org.junit.Test public void scopedMethod() { org.junit.Assert.fail("dependency test executed"); } }'));
        writeFileSync(appPom, originalPom.replace('</dependencies>', '<dependency><groupId>junit</groupId><artifactId>junit</artifactId><version>4.13.2</version><scope>test</scope></dependency></dependencies>'));
        writeFileSync(appSource, 'package demo.core; public class GreeterTest { @org.junit.Test public void scopedMethod() { org.junit.Assert.assertTrue(true); } }');
        const app = model.projects.find(value => value.name === 'app');
        const plan = build('build.testPlan', planned({ project: app, selectorKind: 'java.test.fullName',
          selectors: ['demo.core.GreeterTest#scopedMethod()'], userArguments: ['-Dtest=*', '-DskipTests=false'] })).plan;
        const result = await send(plan, 'exact-project-test');
        assert.equal(result.exitCode, 0, result.output);
        assert.deepEqual(result.testResults.tests.map(value => [value.project, value.suite, value.case]),
          [['nested/app', 'demo.core.GreeterTest', 'scopedMethod']]);
      } finally { writeFileSync(coreSource, original); writeFileSync(appPom, originalPom); rmSync(appSource, { force: true }); }
    });
    await report.step('an unknown method cannot pass with a fresh empty Surefire report', async () => {
      const plan = build('build.testPlan', planned({ project, suite: 'demo.core.GreeterTest', case: 'doesNotExist', userArguments: [] })).plan;
      await assert.rejects(send(plan, 'unknown-test'), /no fresh result/);
    });
    await report.step('custom Surefire directories and a failing method return only fresh inline reports', async () => {
      const directory = join(workspace, root, 'core');
      const pom = join(directory, 'pom.xml');
      const original = readFileSync(pom, 'utf8');
      writeFileSync(pom, original.replace('</project>', `<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId><configuration><reportsDirectory>\${project.build.directory}/custom reports</reportsDirectory></configuration>
        </plugin></plugins></build></project>`));
      const failure = join(directory, 'src/test/java/demo/core/FailureTest.java');
      writeFileSync(failure, 'package demo.core; public class FailureTest { @org.junit.Test public void failsForReport() { org.junit.Assert.fail("expected report failure"); } }');
      const plan = build('build.testPlan', planned({ project, suite: 'demo.core.FailureTest', case: 'failsForReport', userArguments: [] })).plan;
      const result = await send(plan, 'failing-custom-report');
      assert.notEqual(result.exitCode, 0, result.output);
      assert.ok(existsSync(join(directory, 'target/custom reports/TEST-demo.core.FailureTest.xml')));
      const decoded = build('build.decodeTestResults', { workspaceRoot: workspace, root, project: project.path,
        projects: model.projects.map(value => value.path), result: result.testResults }).results;
      assert.equal(decoded.summary.total, 1, JSON.stringify(decoded));
      assert.equal(decoded.summary.failed, 1, JSON.stringify(decoded));
      assert.equal(decoded.tests[0].project, 'nested/core');
      assert.equal(decoded.tests[0].case, 'failsForReport');
      assert.match(decoded.tests[0].message, /expected report failure/);
      assert.deepEqual((await (await import('node:fs/promises')).readdir(join(scratch, 'state'))).filter(name => name.startsWith('execution-')), []);
      rmSync(failure);
      writeFileSync(pom, original);
    });
    await report.step('verify retains Failsafe failures alongside fresh Surefire results', async () => {
      const directory = join(workspace, root, 'core');
      const pom = join(directory, 'pom.xml');
      const original = readFileSync(pom, 'utf8');
      const integration = join(directory, 'src/test/java/demo/core/FailureIT.java');
      try {
        writeFileSync(pom, original.replace('</project>', `<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-failsafe-plugin</artifactId><version>3.5.2</version>
          <configuration><reportsDirectory>\${project.build.directory}/integration reports</reportsDirectory></configuration>
          <executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions>
          </plugin></plugins></build></project>`));
        writeFileSync(integration, 'package demo.core; public class FailureIT { @org.junit.Test public void failsInIntegration() { org.junit.Assert.fail("expected Failsafe failure"); } }');
        // Clean removes the preceding failing class after its source was removed.
        // Maven owns both phases; the host must not merge stale report directories.
        const plan = build('build.taskPlan', planned({ task: 'goal', project, userArguments: ['clean', 'verify'] })).plan;
        const result = await send(plan, 'failsafe-verify');
        assert.notEqual(result.exitCode, 0, result.output);
        assert.ok(existsSync(join(directory, 'target/integration reports/TEST-demo.core.FailureIT.xml')));
        const decoded = build('build.decodeTestResults', { workspaceRoot: workspace, root, project: project.path,
          projects: model.projects.map(value => value.path), result: result.testResults }).results;
        assert.equal(decoded.summary.total, 3, JSON.stringify(decoded));
        assert.equal(decoded.summary.passed, 2, JSON.stringify(decoded));
        assert.equal(decoded.summary.failed, 1, JSON.stringify(decoded));
        assert.ok(decoded.tests.every(test => test.project === 'nested/core'));
        const failed = decoded.tests.find(test => test.case === 'failsInIntegration');
        assert.equal(failed?.status, 'failed');
        assert.match(failed.message, /expected Failsafe failure/);
        assert.deepEqual((await (await import('node:fs/promises')).readdir(join(scratch, 'state'))).filter(name => name.startsWith('execution-')), []);
      } finally {
        rmSync(integration, { force: true });
        writeFileSync(pom, original);
      }
    });
    await report.step('publish the same normalized Maven facts and effective options to a declared consumer', () => {
      const consumer = JSON.parse(JSON.stringify(manifest));
      consumer.id = 'org.example.maven-input-consumer';
      consumer.requires.buildProviders = 6;
      delete consumer.buildSystems[0].modelSharing;
      delete consumer.buildSystems[0].configuration;
      delete consumer.buildSystems[0].test.results;
      delete consumer.buildSystems[0].test.selectorKinds;
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
    await report.step('a module opened as the workspace retains the dot project identity', async () => {
      const module = join(workspace, root, 'core');
      const standalone = new BackendClient({ java: process.env.LITHE_TEST_JAVA ?? 'java', jar: join(pkg, 'lib/lithe-maven-backend.jar'),
        state: join(scratch, 'module-state'), timeoutMs: mavenTimeout() });
      try {
        await standalone.initialize(module, [9]);
        const plan = build('build.testPlan', { root: '.', options: {}, project: { name: 'core', path: '.' },
          selectorKind: 'java.test.fullName', selectors: ['demo.core.GreeterTest#greetsByName()'] }).plan;
        const result = await standalone.request(plan.provider.method, { ...plan.provider.params,
          tool: { executable: maven }, operationID: 'module-workspace-test' });
        assert.equal(result.exitCode, 0, result.output);
        const decoded = build('build.decodeTestResults', { workspaceRoot: module, root: '.', project: '.', result: result.testResults }).results;
        assert.equal(decoded.summary.total, 1);
        assert.equal(decoded.tests[0].project, '.');
        assert.equal(decoded.tests[0].case, 'greetsByName');
      } finally {
        standalone.kill();
        await bounded(standalone.exited, 'module backend cleanup');
      }
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
