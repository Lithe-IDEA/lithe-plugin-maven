// Talks to lib/lithe-maven-backend.jar the way Lithe does: LSP base-protocol
// frames over the process's stdio, `initialize` offering litheBuild 1, then
// lithe/build/* requests. Every wait is bounded, and the backend runs in its
// own process group so a test can always end whatever it left behind.
import { spawn } from "node:child_process";
import { pathToFileURL } from "node:url";

export const WAIT_MS = 20_000;

export class BackendClient {
  #buffer = Buffer.alloc(0);
  #pending = new Map();
  #nextId = 1;
  stderr = "";

  /** Starts `java -jar <jar> --state <state>`; `javaOptions` go before `-jar`. */
  constructor({ java = "java", jar, state, env = process.env, javaOptions = [], timeoutMs = WAIT_MS }) {
    this.timeoutMs = timeoutMs;
    this.child = spawn(java, [...javaOptions, "-jar", jar, "--state", state], {
      env, stdio: ["pipe", "pipe", "pipe"], detached: true,
    });
    this.exited = new Promise((resolve) => this.child.once("exit", (code, signal) => {
      this.#fail(`the backend exited with ${signal ?? `status ${code}`}`);
      resolve(signal ?? code);
    }));
    this.child.once("error", (error) => this.#fail(`cannot start the backend: ${error.message}`));
    this.child.stdout.on("data", (chunk) => this.#read(chunk));
    this.child.stderr.on("data", (chunk) => { this.stderr += chunk; });
    this.child.stdin.on("error", () => {});
  }

  /** Sends a request; `response` settles with the whole response message. */
  send(method, params) {
    const id = this.#nextId++;
    const response = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.#pending.delete(id);
        reject(new Error(`no response to ${method} #${id} within ${this.timeoutMs} ms\nbackend stderr:\n${this.stderr}`));
      }, this.timeoutMs);
      this.#pending.set(id, (message) => {
        clearTimeout(timer);
        if (message instanceof Error) reject(message);
        else resolve(message);
      });
    });
    this.#write({ jsonrpc: "2.0", id, method, params });
    return { id, response };
  }

  /** Sends a request and resolves with its result, or rejects with its error. */
  async request(method, params) {
    const message = await this.send(method, params).response;
    if (message.error) {
      const error = new Error(`${method} failed: ${message.error.message}`);
      error.response = message.error;
      throw error;
    }
    return message.result;
  }

  notify(method, params) {
    this.#write({ jsonrpc: "2.0", method, params });
  }

  cancel(id) {
    this.notify("$/cancelRequest", { id });
  }

  async initialize(root) {
    const result = await this.request("initialize", {
      processId: process.pid,
      rootUri: pathToFileURL(root).href,
      capabilities: { experimental: { litheBuild: { versions: [1] } } },
    });
    this.notify("initialized", {});
    return result;
  }

  /** `shutdown` and `exit`; resolves with the exit code. */
  async close() {
    if (this.child.exitCode === null && this.child.signalCode === null) {
      await this.request("shutdown", null);
      this.notify("exit", null);
      this.child.stdin.end();
    }
    return bounded(this.exited, "the backend to exit");
  }

  /** Ends the backend's whole process group, for teardown after a failure. */
  kill() {
    if (!this.child.pid) return;
    // macOS answers EPERM for a group whose members already exited but are not reaped yet.
    try { process.kill(-this.child.pid, "SIGKILL"); }
    catch (error) { if (error.code !== "ESRCH" && error.code !== "EPERM") throw error; }
  }

  #write(message) {
    const body = Buffer.from(JSON.stringify(message), "utf8");
    this.child.stdin.write(Buffer.concat([Buffer.from(`Content-Length: ${body.length}\r\n\r\n`, "ascii"), body]));
  }

  /** Rejects every waiting request; nothing more will answer them. */
  #fail(reason) {
    const error = new Error(`${reason}\nbackend stderr:\n${this.stderr}`);
    for (const settle of this.#pending.values()) settle(error);
    this.#pending.clear();
  }

  #read(chunk) {
    this.#buffer = Buffer.concat([this.#buffer, chunk]);
    for (;;) {
      const headerEnd = this.#buffer.indexOf("\r\n\r\n");
      if (headerEnd < 0) return;
      const length = Number(/Content-Length: *(\d+)/i.exec(this.#buffer.subarray(0, headerEnd).toString("ascii"))?.[1]);
      if (!Number.isSafeInteger(length)) return this.#broken("a frame without Content-Length");
      if (this.#buffer.length < headerEnd + 4 + length) return;
      let message;
      try { message = JSON.parse(this.#buffer.subarray(headerEnd + 4, headerEnd + 4 + length).toString("utf8")); }
      catch { return this.#broken("a frame that is not JSON"); }
      this.#buffer = this.#buffer.subarray(headerEnd + 4 + length);
      if (message.id !== undefined && message.method === undefined) {
        this.#pending.get(message.id)?.(message);
        this.#pending.delete(message.id);
      }
    }
  }

  #broken(what) {
    this.#fail(`the backend wrote ${what}`);
    this.kill();
  }
}

export function bounded(promise, description, timeoutMs = WAIT_MS) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`timed out waiting for ${description}`)), timeoutMs); }),
  ]).finally(() => clearTimeout(timer));
}
