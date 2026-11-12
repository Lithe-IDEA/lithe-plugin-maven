#!/usr/bin/env node
// Stands in for Maven, so the integration lane's failure reporting is checked
// without Java or Maven:
//   FAKE_MAVEN_MODE=exit       Maven fails before writing the model
//   FAKE_MAVEN_MODE=malformed  Maven succeeds but the model is not JSON
import { writeFileSync } from "node:fs";

if (process.env.FAKE_MAVEN_MODE === "exit") {
  console.error("fake mvn: the build failed");
  process.exit(1);
}
const output = process.argv.slice(2).find((arg) => arg.startsWith("-Dlithe.modelOutput="));
if (output) writeFileSync(output.slice("-Dlithe.modelOutput=".length), "{nope");
