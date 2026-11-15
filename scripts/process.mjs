// Own each command's POSIX process group, including Maven's forked JVMs.
// Async spawn keeps the watchdog and interrupt handlers runnable even when
// the command ignores SIGTERM; execFileSync's timeout cannot guarantee that.
//
// Windows has no process groups and Node cannot start mvn.cmd without a
// shell, so there the command runs under cmd.exe, which resolves `mvn`
// through PATHEXT as Lithe does, and stopping it kills the whole process tree
// with taskkill while the command is still running.
import { spawn } from "node:child_process";
import { performance } from "node:perf_hooks";

export const MAVEN_TIMEOUT_MS = 120_000;
export const ZIP_TIMEOUT_MS = 30_000;
const GRACE_MS = 500;
const KILL_WAIT_MS = 2_000;
const WINDOWS = process.platform === "win32";

/**
 * The cmd.exe command line for one command. Arguments that cmd.exe or a batch
 * file would expand or split are rejected rather than escaped.
 */
export function windowsCommandLine(command, args) {
  return [command, ...args].map((arg) => {
    if (/["%!\r\n\0]/.test(arg)) throw new Error(`cannot pass ${JSON.stringify(arg)} through cmd.exe`);
    if (/^[A-Za-z0-9_\-+=.,:/\\@${}]+$/.test(arg)) return arg;
    // Backslashes before the closing quote would escape it for the program.
    return `"${arg.replace(/(\\+)$/, "$1$1")}"`;
  }).join(" ");
}

export function mavenTimeout() {
  const value = process.env.LITHE_MAVEN_TIMEOUT_MS;
  if (value === undefined) return MAVEN_TIMEOUT_MS;
  const timeout = Number(value);
  if (!/^\d+$/.test(value) || !Number.isSafeInteger(timeout) || timeout < 1 || timeout > 600_000) {
    throw new Error("LITHE_MAVEN_TIMEOUT_MS must be an integer from 1 to 600000");
  }
  return timeout;
}

export function runCommand(command, args, {
  cwd = process.cwd(), env = process.env, stdio = "inherit", timeoutMs = MAVEN_TIMEOUT_MS,
  graceMs = GRACE_MS, killWaitMs = KILL_WAIT_MS, signal,
} = {}) {
  for (const [name, value] of Object.entries({ timeoutMs, graceMs, killWaitMs })) {
    if (!Number.isSafeInteger(value) || value < 1 || value > 3_600_000) {
      throw new Error(`${name} must be an integer from 1 to 3600000`);
    }
  }
  signal?.throwIfAborted();
  const started = performance.now();
  const invocation = [command, ...args].map((arg) => JSON.stringify(arg)).join(" ");
  const line = WINDOWS ? windowsCommandLine(command, args) : undefined;

  return new Promise((resolve, reject) => {
    const child = WINDOWS
      ? spawn(process.env.ComSpec ?? "cmd.exe", ["/d", "/v:off", "/s", "/c", `"${line}"`], {
        cwd, env, stdio, windowsHide: true, windowsVerbatimArguments: true,
      })
      : spawn(command, args, { cwd, env, stdio, detached: true, shell: false });
    let closed = false;
    let code;
    let exitSignal;
    let failure;
    let stopping = false;
    let settled = false;
    let graceTimer;
    let killTimer;
    let pollTimer;

    const diagnostic = (message) => new Error(
      `${message} after ${Math.round(performance.now() - started)} ms\ncommand: ${invocation}\ncwd: ${cwd}`,
    );
    let killing;
    const groupExists = () => {
      // Windows cannot find a tree once its root has exited; send() kills it before.
      if (WINDOWS || !child.pid) return false;
      try { process.kill(-child.pid, 0); return true; }
      catch (error) { if (error.code === "ESRCH") return false; throw error; }
    };
    const send = (value) => {
      if (!child.pid) return;
      if (WINDOWS) {
        if (child.exitCode !== null || child.signalCode !== null || killing) return;
        killing = spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore", windowsHide: true });
        killing.once("error", (error) => { failure ??= diagnostic(`cannot run taskkill: ${error.message}`); });
        killing.once("exit", () => { killing = undefined; });
        return;
      }
      try { process.kill(-child.pid, value); }
      catch (error) { if (error.code !== "ESRCH") failure ??= diagnostic(`cannot send ${value}: ${error.message}`); }
    };
    const finish = () => {
      if (settled) return;
      settled = true;
      clearTimeout(timeoutTimer);
      clearTimeout(graceTimer);
      clearTimeout(killTimer);
      clearTimeout(pollTimer);
      signal?.removeEventListener("abort", onAbort);
      if (failure) reject(failure);
      else if (code !== 0) reject(diagnostic(`command exited with ${exitSignal ? `signal ${exitSignal}` : `status ${code}`}`));
      else resolve();
    };
    // Poll only the owned OS process group, with the cleanup deadline below.
    const inspect = () => {
      try {
        if (closed && !groupExists()) return finish();
      } catch (error) { failure ??= diagnostic(`cannot inspect process group: ${error.message}`); }
      pollTimer = setTimeout(inspect, 20);
    };
    const stop = () => {
      if (stopping) return;
      stopping = true;
      clearTimeout(timeoutTimer);
      send("SIGTERM");
      graceTimer = setTimeout(() => send("SIGKILL"), graceMs);
      killTimer = setTimeout(() => {
        send("SIGKILL");
        const cleanup = diagnostic("process group did not disappear before the cleanup deadline");
        failure = failure ? new Error(`${failure.message}\ncleanup: ${cleanup.message}`, { cause: failure }) : cleanup;
        // A descendant may keep a pipe open after its parent has exited.
        for (const stream of child.stdio) stream?.destroy?.();
        child.unref();
        finish();
      }, graceMs + killWaitMs);
      inspect();
    };
    const onAbort = () => {
      failure ??= diagnostic(`command cancelled: ${signal.reason?.message ?? signal.reason ?? "abort"}`);
      stop();
    };
    const timeoutTimer = setTimeout(() => {
      failure ??= diagnostic(`command timed out (limit ${timeoutMs} ms)`);
      stop();
    }, timeoutMs);
    child.once("error", (error) => {
      failure ??= diagnostic(`cannot run command: ${error.message}`);
      stop();
    });
    child.once("exit", (status, value) => {
      code = status;
      exitSignal = value;
      stop();
    });
    child.once("close", () => { closed = true; stop(); });
    signal?.addEventListener("abort", onAbort, { once: true });
    if (signal?.aborted) onAbort();
  });
}

// Keep handlers installed through scratch cleanup and report writing. A repeated
// interrupt still follows the same bounded escalation instead of orphaning JVMs.
export async function withInterrupts(body) {
  const controller = new AbortController();
  let interrupted;
  const handlers = new Map(["SIGINT", "SIGTERM"].map((name) => [name, () => {
    interrupted ??= name;
    controller.abort(new Error(name));
  }]));
  for (const [name, handler] of handlers) process.on(name, handler);
  try { await body(controller.signal); }
  finally {
    for (const [name, handler] of handlers) process.off(name, handler);
    if (interrupted) process.exitCode = interrupted === "SIGINT" ? 130 : 143;
  }
}
