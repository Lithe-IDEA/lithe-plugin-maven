// Stands in for `mvn` when scripts/test-backend.mjs drives the backend. It
// logs each run, writes the two documents the extension would write for the
// reactor in its working directory, and obeys a control file the test
// rewrites between steps:
//   { "gate": "<file>" }   write <file>.started, then wait until <file> exists
//   { "child": true }      start a SIGTERM-ignoring child and log its PID
//   { "exit": <status> }   print an [ERROR] line and exit with the status
//   { "output": "missing" | "malformed" }  succeed without the documents, or with invalid JSON
import { spawn } from "node:child_process";
import { appendFileSync, existsSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";

const configuration = existsSync(process.env.FAKE_MAVEN_CONTROL ?? "")
  ? JSON.parse(readFileSync(process.env.FAKE_MAVEN_CONTROL, "utf8"))
  : {};
const exporting = process.argv.some((argument) => argument.startsWith("-Dlithe.modelOutput="));
const control = exporting ? configuration : (configuration.execution ?? configuration);
const log = process.env.FAKE_MAVEN_LOG;
const argv = process.argv.slice(2);
appendFileSync(log, `${JSON.stringify({ pid: process.pid, self: process.env.FAKE_MAVEN_SELF, cwd: process.cwd(), argv })}\n`);

if (control.child) {
  const child = spawn(process.execPath, ["-e", "process.on('SIGTERM', () => {}); setInterval(() => {}, 60_000)"], { stdio: "ignore" });
  appendFileSync(`${log}.children`, `${child.pid}\n`);
}
if (control.gate) {
  writeFileSync(`${control.gate}.started`, `${process.pid}\n`);
  const deadline = Date.now() + 30_000;
  while (!existsSync(control.gate)) {
    if (Date.now() > deadline) process.exit(97);
    await new Promise((resolve) => setTimeout(resolve, 20));
  }
}
if (control.exit) {
  console.log("[INFO] Scanning for projects...");
  console.log("[ERROR] fake failure for the backend test");
  process.exit(control.exit);
}

const value = (name) => argv.find((arg) => arg.startsWith(`-D${name}=`))?.slice(name.length + 3);
if (value("lithe.dependenciesProject") !== undefined) throw new Error("the backend must export every module");
const modelOutput = value("lithe.modelOutput");
const dependenciesOutput = value("lithe.dependenciesOutput");
if (!exporting) {
  console.log("[INFO] native Maven execution");
  console.log("[WARNING] " + join(process.cwd(), "core/src/main/java/demo/core/Greeter.java") + ":[1,1] test diagnostic");
  const selector = argv.find(argument => argument.startsWith("-Dtest="))?.slice(7);
  if (selector && !control.noReport) {
    const [selectedSuite, method = "greets"] = selector.split("#");
    const suite = control.reportSuite ?? selectedSuite;
    const directory = join(process.cwd(), "core/target/surefire-reports");
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, "TEST-" + suite + ".xml"), `<testsuite><testcase classname="${suite}" name="${method}"/></testsuite>`);
  }
  if (control.flood) await new Promise((done, reject) => process.stdout.write("x".repeat(9 * 1024 * 1024), error => error ? reject(error) : done()));
  process.exit(0);
}
if (!modelOutput || !dependenciesOutput) throw new Error("expected both output properties");
if (control.output === "missing") process.exit(0);
if (control.output === "malformed") {
  writeFileSync(modelOutput, "{invalid JSON");
  writeFileSync(dependenciesOutput, "{invalid JSON");
  process.exit(0);
}

const tag = (xml, name) => new RegExp(`<${name}>([^<]*)</${name}>`).exec(xml)?.[1];
const withoutParent = (xml) => xml.replace(/<parent>[\s\S]*?<\/parent>/, "").replace(/<dependencies>[\s\S]*<\/dependencies>/, "");
function read(directory, parent) {
  const xml = readFileSync(join(directory, "pom.xml"), "utf8");
  const own = withoutParent(xml);
  const project = {
    groupId: tag(own, "groupId") ?? parent?.groupId,
    artifactId: tag(own, "artifactId"),
    version: tag(own, "version") ?? parent?.version,
    packaging: tag(own, "packaging") ?? "jar",
    path: directory,
    displayName: tag(own, "name"),
    parentPath: parent?.path,
  };
  project.dependencies = [...xml.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(([, body]) => ({
    name: tag(body, "artifactId"),
    group: tag(body, "groupId"),
    version: tag(body, "version").replace("${project.version}", project.version),
    scope: tag(body, "scope") ?? "compile",
    properties: { type: "jar" },
    children: [],
  }));
  const modules = [...xml.matchAll(/<module>([^<]+)<\/module>/g)].map(([, name]) => name);
  return [project, ...modules.flatMap((name) => read(join(directory, name), project))];
}
const projects = read(process.cwd());
const run = readFileSync(log, "utf8").trim().split("\n").length;

writeFileSync(modelOutput, JSON.stringify({
  format: "litheBuildModel",
  version: 1,
  fakeRun: run,
  projects: projects.map((project) => ({
    name: project.artifactId,
    path: project.path,
    ...(project.displayName ? { displayName: project.displayName } : {}),
    ...(project.parentPath ? { parent: project.parentPath } : {}),
    properties: { groupId: project.groupId, artifactId: project.artifactId, version: project.version, packaging: project.packaging },
  })),
  tasks: [{ name: "compiler:compile", project: projects[1].path, path: projects[1].path,
    template: "plugin-goal", group: "build", properties: { goal: "org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile" } }],
  contentRoots: [],
}));
writeFileSync(dependenciesOutput, JSON.stringify({
  format: "litheDependencies",
  version: 1,
  dependencies: projects.map((project) => ({
    name: project.artifactId,
    group: project.groupId,
    version: project.version,
    properties: { kind: "project", packaging: project.packaging },
    children: project.dependencies,
  })),
}));
