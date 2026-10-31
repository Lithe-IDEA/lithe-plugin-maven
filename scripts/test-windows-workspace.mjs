// The Windows TypeScript workflow, production Maven Provider and current Core.
// Runs on macOS/Linux; native Windows launch/process acceptance is separate.
import assert from 'node:assert/strict';
import { closeSync, cpSync, existsSync, fstatSync, mkdirSync, mkdtempSync, openSync, readFileSync, readSync, readdirSync, realpathSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { BackendClient, bounded } from './backend-client.mjs';
import { coreCommand } from './core-client.mjs';
import { bundleManifest } from './bundle-manifest.mjs';
import { createReport } from './report.mjs';
import { mavenTimeout, withInterrupts } from './process.mjs';

if (!process.versions.bun) throw new Error('Run with Bun to load the production Windows TypeScript source.');
if (!process.env.LITHE_HOST_SOURCE) throw new Error('Set LITHE_HOST_SOURCE to the exact host checkout under test.');
if (!process.env.LITHE_TEST_MAVEN || !process.env.LITHE_TEST_JAVA) throw new Error('Set explicit LITHE_TEST_MAVEN and LITHE_TEST_JAVA paths.');
const { WindowsBuildProviderWorkspace } = await import(pathToFileURL(join(resolve(process.env.LITHE_HOST_SOURCE),
  'windows/tauri/src/features/build/services/build-provider-workspace.ts')).href);
const { createTranslator } = await import(pathToFileURL(join(resolve(process.env.LITHE_HOST_SOURCE), 'windows/tauri/src/i18n/locale.ts')).href);
const pkg = resolve(process.env.LITHE_TEST_PACKAGE ?? 'dist/package/io.github.lithe-idea.maven-build');
const manifest = JSON.parse(readFileSync(join(pkg, 'plugin.json'), 'utf8')), before = bundleManifest(pkg);
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'lithe-windows-maven-'))), root = 'nested';
const workspacePath = join(scratch, 'workspace'), state = join(scratch, 'state');
mkdirSync(workspacePath); cpSync(new URL('../test/fixtures/reactor', import.meta.url), join(workspacePath, root), { recursive: true });
const instance = { pluginID: manifest.id, buildSystemID: 'maven', root, options: { profiles: ['ci'] } };
const report = createReport('windows-provider-workspace');
let client, workspace, model, scopedTool;
await withInterrupts(async signal => {
  const stop = () => client?.kill(); signal.addEventListener('abort', stop, { once: true });
  try {
    client = new BackendClient({ java: process.env.LITHE_TEST_JAVA, jar: join(pkg, 'lib/lithe-maven-backend.jar'), state, timeoutMs: mavenTimeout() });
    assert.equal((await client.initialize(workspacePath, [11])).capabilities.experimental.litheBuild.version, 11);
    const javaHome = resolve(process.env.LITHE_TEST_JAVA, '../..'), requests = [];
    workspace = new WindowsBuildProviderWorkspace(workspacePath, {
      translate: createTranslator('en-US'),
      core: async (command, manifest, buildSystemID, request, workspaceRoot) => coreCommand(command, { manifest, buildSystemID, workspaceRoot, request }),
      tools: async (_target, instanceRoot, _check, configurationId) => {
        const selected = configurationId ? coreCommand('runConfig.projectToolchain', { root: workspacePath, path: instanceRoot, configurationId }, null).toolchain : {};
        const home = selected.mavenJavaHomePath || selected.javaHomePath || javaHome;
        return { manifest, pluginDirectory: pkg, workspaceStateDirectory: state,
          tool: { executable: selected.mavenExecutablePath || process.env.LITHE_TEST_MAVEN }, executesProjectCode: false,
          environment: { JAVA_HOME: home, PATH: join(home, 'bin') + delimiter + process.env.PATH } };
      },
      guard: () => () => {},
      // Test-only native I/O port: private XML is hashed here, never returned to
      // the production TypeScript workflow or sent back over Provider RPC.
      configurationCheck: async (_target, sources) => {
        const paths = [], revisions = [];
        for (const source of sources) {
          const file = fileURLToPath(source.uri); paths.push(file);
          let descriptor;
          try {
            const metadata = statSync(file);
            assert.ok(metadata.isFile() && metadata.size <= 2 * 1024 * 1024, 'bounded regular configuration source');
            descriptor = openSync(file, 'r');
            const opened = fstatSync(descriptor); assert.ok(opened.isFile() && opened.size <= 2 * 1024 * 1024);
            const buffer = Buffer.alloc(2 * 1024 * 1024 + 1);
            let length = 0;
            while (length < buffer.length) {
              const count = readSync(descriptor, buffer, length, buffer.length - length, null);
              if (count === 0) break;
              length += count;
            }
            assert.ok(length <= 2 * 1024 * 1024);
            revisions.push('sha256:' + createHash('sha256').update(buffer.subarray(0, length)).digest('hex'));
          } catch (error) { if (error.code !== 'ENOENT') throw error; revisions.push('missing'); }
          finally { if (descriptor !== undefined) closeSync(descriptor); }
        }
        return { current: sources.every((source, index) => source.revision === revisions[index]), paths };
      },
      providers: {
        send: async (_target, request) => {
          requests.push(request.method);
          assert.equal(request.params.environment.JAVA_HOME, javaHome);
          const operationID = crypto.randomUUID(), sent = client.send(request.method, { ...request.params, operationID });
          const abort = () => client.cancel(sent.id);
          const progress = notification => {
            if (notification.method === 'lithe/build/progress' && notification.params.operationID === operationID) request.onProgress?.(notification.params);
          };
          client.onNotification = progress; request.signal.addEventListener('abort', abort, { once: true });
          try {
            const response = await sent.response;
            if (response.error) throw new Error(JSON.stringify(response.error));
            return response.result;
          } finally { request.signal.removeEventListener('abort', abort); if (client.onNotification === progress) client.onNotification = undefined; }
        },
        stop: async () => { await client.close(); },
      },
    });
    await report.step('Windows reads real native settings before a broken POM without writing sources or creating the repository', async () => {
      const pom = join(workspacePath, root, 'pom.xml'), original = readFileSync(pom);
      const mavenConfiguration = join(workspacePath, root, '.mvn/maven.config');
      const user = join(scratch, 'user settings.xml'), global = join(scratch, 'global.xml'), repository = join(scratch, 'unused-repository');
      writeFileSync(user, '<settings><servers><server><id>fixture</id><password>fixture-private-value</password></server></servers></settings>');
      writeFileSync(global, `<settings><localRepository>${repository}</localRepository></settings>`);
      const before = [readFileSync(user), readFileSync(global)];
      mkdirSync(join(workspacePath, root, '.mvn'), { recursive: true });
      writeFileSync(mavenConfiguration, `-gs\n${global}\n`);
      writeFileSync(pom, '<intentionally broken POM');
      try {
        const configuration = await workspace.readConfiguration({ ...instance, options: { settingsFile: user } }).completion;
        const selected = configuration.sources.find(source => source.id === 'user');
        const field = selected.fields.find(field => field.id === 'localRepository');
        assert.equal(field.value, null); assert.equal(field.effectiveValue, repository);
        assert.equal(configuration.sources.find(source => source.id === 'installation').writable, false);
        assert.ok(!JSON.stringify(configuration).includes('fixture-private-value'));
        assert.deepEqual([readFileSync(user), readFileSync(global)], before);
        assert.equal(existsSync(repository), false);
        assert.equal(existsSync(join(workspacePath, root, 'target')), false);
        assert.deepEqual(readdirSync(state).filter(name => name.startsWith('configuration-')), []);
        assert.equal(workspace.model(instance), undefined);
      } finally { writeFileSync(pom, original); rmSync(mavenConfiguration); }
    });
    await report.step('Windows loads the real reactor through Core and the production Provider', async () => {
      model = await workspace.loadModel(instance).completion;
      assert.deepEqual(model.projects.map(project => project.name).sort(), ['app', 'core', 'reactor']);
      assert.ok(model.contentRoots.some(content => content.path === 'nested/core/src/main/java'));
      assert.deepEqual(requests, ['lithe/build/configuration', 'lithe/build/configuration', 'lithe/build/model']);
    });
    await report.step('Windows executes real reactor tests and decodes inline reports', async () => {
      const operation = workspace.executeTask(instance, 'test');
      const result = await operation.completion;
      assert.equal(result.exitCode, 0, result.output);
      assert.ok(result.results.tests.some(test => test.suite === 'demo.core.GreeterTest'));
      assert.equal(workspace.operations().find(record => record.id === operation.operationID).phase, 'succeeded');
    });
    await report.step('Windows prepares a real module Run through public Core plans without replacing the default model', async () => {
      const project = model.projects.find(project => project.name === 'core');
      const run = manifest.buildSystems[0].run.find(run => run.execution === 'task');
      const target = model.targets.find(target => target.projectID === project.id && target.tags.some(tag => run.fromTargets.tags.includes(tag)));
      assert.ok(target, 'a current module target is published by the Provider');
      const defaults = structuredClone(workspace.model(instance));
      const prepared = await workspace.prepareRun(instance, { run: run.id, projectID: project.id, targetID: target.id,
        launchOptions: { execution: 'task', runtimeArguments: [], arguments: ['compile'], environment: {}, workingDirectory: root } }).completion;
      assert.deepEqual(prepared.launch.executable, { kind: 'toolchain', value: 'project-maven' });
      assert.ok(prepared.launch.arguments.includes('compile'));
      assert.equal(prepared.languageImport.kind, 'maven');
      assert.equal(prepared.languageImport.mavenContext.mavenExecutablePath, process.env.LITHE_TEST_MAVEN);
      prepared.assertCurrent(); await prepared.validate();
      assert.deepEqual(workspace.model(instance), defaults);
      assert.deepEqual(instance.options, { profiles: ['ci'] });
    });
    await report.step('Windows selects dependencies from the same current Provider project', async () => {
      const project = model.projects.find(project => project.name === 'core');
      const result = await workspace.dependencies(instance, project.id).completion;
      assert.equal(result.dependencies.project, project.path);
      assert.ok(result.dependencies.dependencies.some(dependency => dependency.name === 'junit'));
    });
    await report.step('Windows per-Run Profiles use a private real model and preserve the default model and options', async () => {
      const project = model.projects.find(project => project.name === 'core'), defaults = structuredClone(workspace.model(instance));
      const prepared = await workspace.prepareConfigurationRun(instance, { provider: 'maven.module', projectPath: project.path,
        optionOverrides: { profiles: [], skipTests: false },
        launchOptions: { execution: 'task', runtimeArguments: [], arguments: ['compile'], environment: {} } }).completion;
      assert.ok(prepared.launch.arguments.includes('compile'));
      assert.equal(prepared.languageImport.kind, 'maven');
      assert.deepEqual(prepared.languageImport.mavenContext.profiles, []);
      // False contributes no option; the optional public import field may be absent.
      assert.equal(prepared.languageImport.mavenContext.skipTests ?? false, false);
      assert.ok(!prepared.launch.arguments.includes('-DskipTests'));
      await prepared.validate();
      assert.deepEqual(workspace.model(instance), defaults); assert.deepEqual(instance.options, { profiles: ['ci'] });
    });
    await report.step('a configuration-owned private Run model preserves explicit Maven selection and leaves the shared backend alive', async () => {
      const project = model.projects.find(project => project.path === root), defaults = structuredClone(workspace.model(instance));
      const target = model.targets.find(target => target.projectID === project.id && target.name === 'maven.module');
      assert.ok(target);
      const scopedTool = join(scratch, 'selected Run Maven'); symlinkSync(process.env.LITHE_TEST_MAVEN, scopedTool);
      const local = join(workspacePath, '.lithe/run/local.json'), original = existsSync(local) ? readFileSync(local) : undefined;
      mkdirSync(dirname(local), { recursive: true });
      // An explicit saved selector makes this test workspace readable without
      // an in-memory detector layer; the ordinary module entry uses real Core merge.
      writeFileSync(local, JSON.stringify({ version: 2, configurations: [
        { id: 'user:anchor', name: 'Saved task', provider: 'build.provider', execution: 'task', extensions: { build: {
          pluginID: manifest.id, buildSystemID: 'maven', root, run: 'module', projectID: project.id, targetID: target.id, execution: 'task', userArguments: ['compile'] } } },
        { id: 'user:scoped', name: 'Scoped module', provider: 'maven.module', execution: 'task', cwd: root, args: ['compile'],
          toolchains: { java: 'project-jdk', maven: 'project-maven' }, extensions: { maven: { module: '.', skipTests: false },
            java: { mavenExecutablePath: scopedTool, mavenJavaHomePath: javaHome } } },
      ] }));
      let scope;
      try {
        const input = coreCommand('runConfig.providerLaunchInputs', { root: workspacePath, configurationId: 'user:scoped', providerRoot: root }, null);
        scope = workspace.createRunScope(instance, input, 'user:scoped');
        await scope.domain.loadModel(scope.instance, scope.requiredOptions).completion;
        const prepared = await scope.domain.prepareConfigurationRun(scope.instance, input).completion;
        assert.equal(prepared.tool.tool.executable, scopedTool);
        assert.equal(prepared.languageImport.mavenContext.mavenExecutablePath, scopedTool);
        assert.deepEqual(prepared.languageImport.mavenContext.profiles, ['ci']);
        await prepared.validate();
        assert.deepEqual(workspace.model(instance), defaults); assert.deepEqual(instance.options, { profiles: ['ci'] });
      } finally {
        try { await scope?.dispose(); }
        finally { if (original) writeFileSync(local, original); else rmSync(local, { force: true }); }
      }
      assert.equal(client.child.exitCode, null, 'the backend is still owned by the main workspace');
      assert.deepEqual(workspace.model(instance), defaults);
    });
    await report.step('Windows executes a current model plugin goal without reconstructing its Maven command', async () => {
      const project = model.projects.find(project => project.name === 'core');
      const task = model.tasks.find(task => task.projectID === project.id &&
        task.properties.goal?.includes(':maven-compiler-plugin:') && task.properties.goal.endsWith(':compile'));
      assert.ok(task, 'the effective Maven model publishes the compiler goal');
      const result = await workspace.executeModelTask(instance, task.id).completion;
      assert.equal(result.exitCode, 0, result.output);
    });
    await report.step('Windows forwards the exact JDT selector and retains only the selected method report', async () => {
      const project = model.projects.find(project => project.name === 'core');
      const result = await workspace.executeTests(instance, { projectID: project.id, selectorKind: 'java.test.fullName',
        selectors: ['demo.core.GreeterTest#greetsByName()'] }).completion;
      assert.equal(result.exitCode, 0, result.output);
      assert.equal(result.results.summary.total, 1);
      assert.deepEqual(result.results.tests.map(test => [test.project, test.suite, test.case]),
        [[project.path, 'demo.core.GreeterTest', 'greetsByName']]);
    });
    await report.step('a failed real Maven test retains its exit status, output and decoded failure', async () => {
      const source = join(workspacePath, root, 'core/src/test/java/demo/core/GreeterTest.java');
      writeFileSync(source, readFileSync(source, 'utf8').replace('assertEquals("Hello, Lithe", new Greeter().greet("Lithe"));',
        'org.junit.Assert.fail("Windows failure marker");'));
      const operation = workspace.executeTask(instance, 'test');
      const result = await operation.completion;
      assert.notEqual(result.exitCode, 0);
      assert.ok(result.results.tests.some(test => test.status === 'failed'));
      const retained = workspace.operations().find(record => record.id === operation.operationID);
      assert.equal(retained.phase, 'failed');
      assert.ok(result.output.length > 0, 'Maven failure output is retained');
      assert.equal(retained.output, result.output);
      assert.ok(result.results.tests.some(test => test.message?.includes('Windows failure marker')),
        'Surefire owns the detailed assertion message even when Maven uses quiet output');
    });
    await report.step('workspace cleanup reaps its backend and preserves package bytes', async () => {
      await workspace.dispose();
      assert.notEqual(client.child.exitCode, null);
      assert.deepEqual(bundleManifest(pkg), before);
      assert.throws(() => workspace.loadModel(instance), /inputs changed/);
    });
  } finally {
    signal.removeEventListener('abort', stop);
    try { if (workspace) await workspace.dispose(); else if (client) await client.close(); }
    finally { client?.kill(); if (client) await bounded(client.exited, 'Windows integration backend cleanup'); rmSync(scratch, { recursive: true, force: true }); report.write(); }
  }
});
