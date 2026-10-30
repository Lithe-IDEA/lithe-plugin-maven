#!/usr/bin/env node
// Real framework Mojos, Core plans, and standard JDI; every owned JVM is reaped.
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { BackendClient, bounded } from './backend-client.mjs';
import { coreCommand } from './core-client.mjs';
import { bundleManifest } from './bundle-manifest.mjs';
import { createReport } from './report.mjs';
import { mavenTimeout, runCommand, withInterrupts } from './process.mjs';

const pkg = resolve(process.env.LITHE_TEST_PACKAGE ?? 'dist/package/io.github.lithe-idea.maven-build');
const manifest = JSON.parse(readFileSync(join(pkg, 'plugin.json')));
const before = bundleManifest(pkg);
const java = process.env.LITHE_TEST_JAVA ?? (process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin/java'));
const maven = process.env.LITHE_TEST_MAVEN ?? process.env.MVN;
assert.ok(java?.startsWith('/'), 'Set LITHE_TEST_JAVA to an absolute JDK executable (Java 17+ for these framework fixtures)');
assert.ok(maven?.startsWith('/'), 'Set LITHE_TEST_MAVEN to an absolute Maven executable (Maven 3.9+ for this acceptance lane)');
const scratch = realpathSync(mkdtempSync(join(tmpdir(), 'lithe-framework-debug-')));
const workspace = join(scratch, 'workspace');
const report = createReport('framework-jvm-debug');
let client;
let sequence = 0;

const frameworkCases = [
  { name: 'quarkus', provider: 'quarkus.maven', version: '3.15.5', group: 'io.quarkus.platform', artifact: 'quarkus-maven-plugin',
    dependencies: '<dependency><groupId>io.quarkus</groupId><artifactId>quarkus-arc</artifactId><version>3.15.5</version></dependency>',
    management: '<dependencyManagement><dependencies><dependency><groupId>io.quarkus.platform</groupId><artifactId>quarkus-bom</artifactId><version>3.15.5</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>',
    lifecycle: '<executions><execution><goals><goal>build</goal></goals></execution></executions>',
    configuration: '<jvmArgs>-Dlithe.pom=preserved</jvmArgs>',
    imports: 'import io.quarkus.runtime.QuarkusApplication; import io.quarkus.runtime.annotations.QuarkusMain;',
    declaration: '@QuarkusMain public class Main implements QuarkusApplication { public int run(String... args) throws Exception {',
    end: 'return 0; } }' },
  { name: 'micronaut', provider: 'micronaut.maven', version: '4.7.2', group: 'io.micronaut.maven', artifact: 'micronaut-maven-plugin',
    // The plugin declares this parameter required even when test resources are
    // disabled. Use its own 4.7.2 parent POM's version; no Docker server is started.
    // Platform 4.7.6 pins Core/processor 4.7.14. The fixture runs real DI and
    // Netty HTTP, rather than merely launching a plain main through the Mojo.
    management: '<dependencyManagement><dependencies><dependency><groupId>io.micronaut.platform</groupId><artifactId>micronaut-platform</artifactId><version>4.7.6</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>',
    dependencies: '<dependency><groupId>io.micronaut</groupId><artifactId>micronaut-runtime</artifactId></dependency><dependency><groupId>io.micronaut</groupId><artifactId>micronaut-http-server-netty</artifactId></dependency><dependency><groupId>io.micronaut</groupId><artifactId>micronaut-jackson-databind</artifactId></dependency>',
    compiler: '<plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><configuration><annotationProcessorPaths><path><groupId>io.micronaut</groupId><artifactId>micronaut-inject-java</artifactId><version>4.7.14</version></path></annotationProcessorPaths></configuration></plugin>',
    configuration: '<mainClass>demo.app.Main</mainClass><jvmArguments>-Dlithe.pom=preserved</jvmArguments><testResourcesVersion>2.6.2</testResourcesVersion><testResourcesEnabled>false</testResourcesEnabled>',
    imports: 'import io.micronaut.context.ApplicationContext; import io.micronaut.runtime.server.EmbeddedServer;',
    declaration: 'public class Main { public static void main(String[] args) throws Exception { try (ApplicationContext context = ApplicationContext.run(java.util.Map.of("micronaut.server.host", "127.0.0.1", "micronaut.server.port", -1))) { EmbeddedServer server = context.getBean(EmbeddedServer.class).start();',
    greeting: 'context.getBean(GreetingService.class).greet()',
    after: 'System.out.println("MICRONAUT_PORT="+server.getPort()); new java.util.concurrent.CountDownLatch(1).await(45, java.util.concurrent.TimeUnit.SECONDS);',
    http: true, end: '} } }' },
];

async function unusedPort() {
  const server = createServer(), allocation = new AbortController();
  try {
    return await bounded(new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen({ host: '127.0.0.1', port: 0, signal: allocation.signal }, () => {
        const port = server.address().port;
        server.close(error => error ? reject(error) : resolve(port));
      });
    }), 'allocate framework debug port', 5000);
  } finally { allocation.abort(); }
}

await withInterrupts(async signal => {
  const stop = () => client?.kill();
  signal.addEventListener('abort', stop, { once: true });
  try {
    mkdirSync(workspace);
    const helper = join(scratch, 'ResumeFramework.java');
    writeFileSync(helper, `import com.sun.jdi.*; import com.sun.jdi.connect.*; import java.util.*;
      public class ResumeFramework { public static void main(String[] args) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
          .filter(value -> value.name().equals("com.sun.jdi.SocketAttach")).findFirst().get();
        Map<String, Connector.Argument> options = connector.defaultArguments();
        options.get("hostname").setValue("127.0.0.1"); options.get("port").setValue(args[0]); options.get("timeout").setValue("5000");
        VirtualMachine vm = connector.attach(options); try { vm.resume(); } finally { try { vm.dispose(); } catch (VMDisconnectedException exited) {} }
      } }`);
    const tools = join(resolve(java, '../..'), 'lib/tools.jar');
    await report.step('compile the standard JDI attach/resume helper', () => runCommand(join(dirname(java), 'javac'),
      [...(existsSync(tools) ? ['-cp', tools] : []), helper], { signal, timeoutMs: 10000 }));
    client = new BackendClient({ java, jar: join(pkg, 'lib/lithe-maven-backend.jar'), state: join(scratch, 'state'), timeoutMs: mavenTimeout() });
    await client.initialize(workspace, [11]);
    for (const fixture of frameworkCases) {
      await report.step(`${fixture.name} ${fixture.version}: current model, suspended JVM, JDI resume, argv and reactor dependency`, async () => {
        const root = `${fixture.name} reactor`, directory = join(workspace, root);
        cpSync(resolve('test/fixtures/reactor'), directory, { recursive: true });
        // A fresh version proves the sibling comes from this reactor, never the local repository.
        const version = `debug-${fixture.name}-${process.pid}-${Date.now()}`;
        for (const module of ['', 'app', 'core']) {
          const pom = join(directory, module, 'pom.xml');
          let text = readFileSync(pom, 'utf8').replaceAll('1.0.0-SNAPSHOT', version).replace('<maven.compiler.release>8', '<maven.compiler.release>17');
          if (module === '') text = text.replace('</project>', (fixture.management ?? '') + '</project>');
          writeFileSync(pom, text);
        }
        const pom = join(directory, 'app/pom.xml');
        writeFileSync(pom, readFileSync(pom, 'utf8').replace('</dependencies>', fixture.dependencies + '</dependencies>')
          .replace('</project>', `<build><plugins><plugin><groupId>${fixture.group}</groupId><artifactId>${fixture.artifact}</artifactId>
            <version>${fixture.version}</version>${fixture.lifecycle ?? ''}<configuration>${fixture.configuration}</configuration></plugin>${fixture.compiler ?? ''}</plugins></build></project>`));
        if (fixture.http) {
          writeFileSync(join(directory, 'app/src/main/java/demo/app/GreetingService.java'), `package demo.app;
            @jakarta.inject.Singleton public class GreetingService {
              public String greet() { return new demo.core.Greeter().greet(System.getenv("LITHE_RUN_VALUE")); }
            }`);
          writeFileSync(join(directory, 'app/src/main/java/demo/app/GreetingController.java'), `package demo.app;
            @io.micronaut.http.annotation.Controller("/lithe") public class GreetingController {
              private final GreetingService service;
              public GreetingController(GreetingService service) { this.service = service; }
              @io.micronaut.http.annotation.Get(produces = "text/plain") public String index() { return service.greet(); }
            }`);
        }
        const marker = `FRAMEWORK=${fixture.name}:Hello, Provider`;
        writeFileSync(join(directory, 'app/src/main/java/demo/app/Main.java'), `package demo.app; import demo.core.Greeter; ${fixture.imports}
          ${fixture.declaration}
          System.out.println("FRAMEWORK=${fixture.name}:"+${fixture.greeting ?? 'new Greeter().greet(System.getenv("LITHE_RUN_VALUE"))'});
          System.out.println("POMFLAG="+System.getProperty("lithe.pom"));
          for (String arg : args) System.out.println("ARG="+java.util.Base64.getEncoder().encodeToString(arg.getBytes("UTF-8")));
          ${fixture.after ?? ''}
          ${fixture.end}`);
        const plan = (command, request) => coreCommand(command, { manifest, buildSystemID: 'maven', request });
        const planned = extra => ({ root, options: {}, pluginDirectory: pkg, workspaceStateDirectory: join(scratch, 'state'), tool: { executable: maven }, ...extra });
        const send = async value => client.request(value.provider.method, { ...value.provider.params, operationID: `framework-${++sequence}` });
        const current = plan('build.decodeModel', { workspaceRoot: workspace, root,
          result: await send(plan('build.modelPlan', planned({})).plan) }).model;
        const app = current.projects.find(value => value.name === 'app');
        const target = current.targets.find(value => value.projectID === app.id && value.name === fixture.provider);
        assert.ok(target, `${fixture.name} must publish its real effective target`);
        const port = await unusedPort();
        const launchOptions = { execution: 'service', runtimeArguments: [], arguments: ['a b', '雪'],
          environment: { LITHE_RUN_VALUE: 'Provider' }, debug: { runtime: 'jvm', port } };
        const prepared = await send(plan('build.prepareRunPlan', planned({ workspaceRoot: workspace, run: 'framework',
          project: { name: app.name, path: app.path }, target: { name: target.name, path: target.path, tags: target.tags }, launchOptions })).plan);
        const launch = plan('build.decodeRunPreparation', { workspaceRoot: workspace, root, launchOptions, result: prepared }).launch;
        let output = '', ready, ran, applicationFailed;
        const listening = new Promise(resolve => { ready = resolve; }), application = new Promise((resolve, reject) => { ran = resolve; applicationFailed = reject; });
        application.catch(() => {});
        const controller = new AbortController(), abort = () => controller.abort(signal.reason);
        signal.addEventListener('abort', abort, { once: true });
        const running = runCommand(maven, launch.arguments, { cwd: join(workspace, launch.workingDirectory),
          env: { ...process.env, ...launch.environment }, signal: controller.signal, timeoutMs: mavenTimeout(),
          onOutput: chunk => {
            output = (output + chunk).slice(-500000);
            if (output.includes('Exception in thread "main"')) applicationFailed(new Error('Application main failed: ' + output));
            if (output.includes('Listening for transport dt_socket at address: ' + port)) ready();
            if (output.includes(marker) && output.includes('POMFLAG=preserved')
              && (!fixture.http || /MICRONAUT_PORT=\d+/.test(output))
              && launchOptions.arguments.every(arg => output.includes('ARG=' + Buffer.from(arg).toString('base64')))) ran();
          } });
        const completed = running.then(() => { throw new Error('Framework exited before acceptance: ' + output); });
        // Attach the rejection handlers before any watchdog can reject the process.
        completed.catch(() => {});
        try {
          await bounded(Promise.race([listening, completed]), `${fixture.name} suspended JVM`, mavenTimeout());
          assert.ok(!output.includes(marker), 'application must not execute before JDI attachment');
          await runCommand(java, ['-cp', scratch + (existsSync(tools) ? delimiter + tools : ''), 'ResumeFramework', String(port)], { signal, timeoutMs: 10000 });
          await bounded(Promise.race([application, completed]), `${fixture.name} resumed application`, 30000);
          if (fixture.http) {
            const port = Number(/MICRONAUT_PORT=(\d+)/.exec(output)?.[1]);
            assert.ok(Number.isInteger(port) && port > 0 && port <= 65535);
            const response = await fetch(`http://127.0.0.1:${port}/lithe`, { signal: AbortSignal.timeout(5000) });
            assert.equal(response.status, 200);
            assert.equal(await response.text(), 'Hello, Provider', 'real Micronaut HTTP dispatch uses the injected reactor dependency');
            assert.ok(existsSync(join(directory, 'app/target/classes/demo/app/$GreetingService$Definition.class')),
              'the real Micronaut annotation processor generated the bean definition');
          }
        } catch (error) { throw new Error(`${error.message}\n${output}`, { cause: error }); }
        finally {
          signal.removeEventListener('abort', abort); controller.abort();
          await running.catch(error => {
            if (/process group did not disappear|cleanup:/.test(error.message)) throw error;
          });
          const reports = resolve(process.env.LITHE_REPORT_DIR ?? '.artifacts/test-stability');
          mkdirSync(reports, { recursive: true });
          writeFileSync(join(reports, `${fixture.name}-runtime.log`), output);
        }
      });
    }
    await report.step('stop the backend and preserve installed plugin bytes', async () => {
      assert.equal(await client.close(), 0);
      assert.deepEqual(bundleManifest(pkg), before);
    });
  } catch (error) { process.exitCode = 1; report.failure('framework debug acceptance failed', error); console.error(error.stack); }
  finally {
    signal.removeEventListener('abort', stop); stop();
    if (client) await bounded(client.exited, 'framework backend cleanup').catch(error => { process.exitCode = 1; console.error(error); });
    rmSync(scratch, { recursive: true, force: true }); report.write();
  }
});
