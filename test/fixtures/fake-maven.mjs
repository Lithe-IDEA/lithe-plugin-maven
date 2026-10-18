#!/usr/bin/env node
// Deterministic command failures for the integration script's reporting paths.
import { writeFileSync } from "node:fs";
if (process.env.FAKE_MAVEN_MODE === "missing") process.exit(0);
const output = process.argv.find((arg) => arg.startsWith("-Dlithe.modelOutput="));
if (!output) throw new Error("expected a model output argument");
writeFileSync(output.slice("-Dlithe.modelOutput=".length), "{invalid JSON");
