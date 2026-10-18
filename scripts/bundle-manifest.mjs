// Read-only file inventory: names, kinds, permissions, link targets and bytes.
import { createHash } from "node:crypto";
import { lstatSync, readdirSync, readFileSync, readlinkSync } from "node:fs";
import { join } from "node:path";

export function bundleManifest(root) {
  const entries = [];
  const walk = (path, relative) => {
    const stat = lstatSync(path);
    const entry = { path: relative, mode: stat.mode & 0o777 };
    if (stat.isSymbolicLink()) {
      entries.push({ ...entry, kind: "link", target: readlinkSync(path) });
    } else if (stat.isDirectory()) {
      entries.push({ ...entry, kind: "directory" });
      for (const name of readdirSync(path).sort()) walk(join(path, name), relative ? `${relative}/${name}` : name);
    } else if (stat.isFile()) {
      entries.push({ ...entry, kind: "file", size: stat.size, sha256: createHash("sha256").update(readFileSync(path)).digest("hex") });
    } else {
      throw new Error(`unsupported bundle entry: ${path}`);
    }
  };
  walk(root, "");
  return { root, sha256: createHash("sha256").update(JSON.stringify(entries)).digest("hex"), entries };
}
