#!/usr/bin/env node
// Stands in for the Java that runs the build provider, so the integration
// lane's failure reporting is checked without Java or Maven:
//   FAKE_JAVA_MODE=exit       the provider cannot start
//   FAKE_JAVA_MODE=malformed  the provider answers with a frame that is not JSON
if (process.env.FAKE_JAVA_MODE === "exit") {
  console.error("fake java: cannot start the provider");
  process.exit(3);
}
process.stdout.write("Content-Length: 5\r\n\r\n{nope");
setInterval(() => {}, 60_000);
