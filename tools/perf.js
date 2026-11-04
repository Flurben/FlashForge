#!/usr/bin/env node
// Runs the scripted profiling session and copies its results to a named file.
//
//   node tools/perf.js dev <out.json> [--dump <folder>] [--segment-ticks N]
//   node tools/perf.js prism <out.json> [--shader <pack zip>] [--no-dh] [--dump <folder>] [--segment-ticks N] [--jfr]
//
// "dev" uses the dev client on its own. "prism" goes through tools/prism-test.js, so it uses the TerraFirmaGreg instance.
const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const JAVA_HOME = "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot";
const [mode, out, ...rest] = process.argv.slice(2);
const option = (name, fallback) => {
  const i = rest.indexOf(name);
  return i >= 0 ? rest[i + 1] : fallback;
};
const extra = [];
if (option("--dump")) extra.push(`flashback_reforged.perf.dump=${path.resolve(option("--dump")).replace(/\\/g, "/")}`);
extra.push(`flashback_reforged.segmentTicks=${option("--segment-ticks", "1200")}`);
if (rest.includes("--jfr")) extra.push("flashback_reforged.perf.jfr=true");
// The check stops the game briefly each time it notes the world, which would show up in the frame times.
rest.forEach((arg, i) => {
  if (arg === "--request") extra.push(rest[i + 1]);
});
if (rest.includes("--no-check")) extra.push("flashback_reforged.perf.checkInterval=0");

let result;
if (mode === "dev") {
  const game = path.join(ROOT, "run");
  fs.rmSync(path.join(game, "saves/flashback_reforged_perf"), { recursive: true, force: true });
  fs.rmSync(path.join(game, "flashback_reforged-perf.json"), { force: true });
  fs.writeFileSync(path.join(game, "flashback_reforged-selftest.properties"), ["flashback_reforged.selftest=perf", ...extra].join("\n") + "\n");
  const run = spawnSync(`"${path.join(ROOT, "gradlew.bat")}" runClient --console=plain --no-daemon`, { cwd: ROOT, shell: true, env: { ...process.env, JAVA_HOME }, encoding: "utf8", maxBuffer: 1 << 30 });
  console.log("gradle exit", run.status);
  fs.writeFileSync(path.join(path.dirname(path.resolve(out)), path.basename(out) + ".log"), (run.stdout || "") + (run.stderr || ""));
  result = path.join(game, "flashback_reforged-perf.json");
} else {
  const game = path.join(process.env.APPDATA, "PrismLauncher/instances/TerraFirmaGreg-Modern 0.13.17/.minecraft");
  fs.rmSync(path.join(game, "saves/flashback_reforged_perf"), { recursive: true, force: true });
  fs.rmSync(path.join(game, "flashback_reforged-perf.json"), { force: true });
  const args = [path.join(__dirname, "prism-test.js"), "--scenario", "perf", "--preset", "tfc:overworld"];
  if (option("--shader")) args.push("--shader", option("--shader"));
  if (rest.includes("--no-dh")) args.push("--no-dh");
  for (const line of extra) args.push("--request", line);
  const run = spawnSync(process.execPath, args, { stdio: "inherit" });
  console.log("prism-test exit", run.status);
  result = path.join(game, "flashback_reforged-perf.json");
}
if (!fs.existsSync(result)) {
  console.log("NO PERF RESULT");
  process.exit(1);
}
fs.copyFileSync(result, out);
for (const f of fs.readdirSync(path.dirname(result))) {
  if (f.startsWith("flashback_reforged-perf-") && f.endsWith(".jfr")) fs.renameSync(path.join(path.dirname(result), f), out.replace(/\.json$/, "") + "-" + f.replace("flashback_reforged-perf-", ""));
}
console.log("wrote", out);
