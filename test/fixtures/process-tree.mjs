// Synthetic subprocesses for the script lifecycle regressions. Readiness flows
// over IPC; timers only keep an idle process alive, never coordinate assertions.
import { spawn } from "node:child_process";
import { appendFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { runCommand, withInterrupts } from "../../scripts/process.mjs";

const [mode, records, action = "timeout"] = process.argv.slice(2);
const file = fileURLToPath(import.meta.url);
appendFileSync(records, `${process.pid}\n`);

if (mode === "owner") {
  await withInterrupts(async (signal) => {
    try {
      await runCommand(process.execPath, [file, "tree", records, action], {
        signal, timeoutMs: 10_000, graceMs: 100, killWaitMs: 1_000,
      });
    } catch (error) {
      console.error(error.message);
      process.exitCode = 1;
    } finally {
      writeFileSync(`${records}.cleaned`, "owner cleanup ran\n");
    }
  });
} else if (mode === "tree") {
  process.on("SIGTERM", () => {});
  const leaf = spawn(process.execPath, [file, "leaf", records], { stdio: ["ignore", "inherit", "inherit", "ipc"] });
  const readyDeadline = setTimeout(() => process.exit(91), 5_000);
  leaf.once("message", () => {
    clearTimeout(readyDeadline);
    console.log("tree ready");
    if (action === "exit") process.exit(0);
    if (action === "SIGINT" || action === "SIGTERM") process.kill(process.ppid, action);
  });
} else if (mode === "leaf") {
  process.on("SIGTERM", () => {});
  process.send("ready");
  process.disconnect();
  setInterval(() => {}, 60_000);
} else {
  throw new Error(`unknown fixture mode: ${mode}`);
}
