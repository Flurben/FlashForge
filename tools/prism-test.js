#!/usr/bin/env node
// Runs the in-game self test through a Prism Launcher instance holding a real modpack.
//
//   node tools/prism-test.js [--instance <name>] [--scenario full] [--preset tfc:overworld] [--ticks 200]
//                            [--no-dh] [--shader <shader pack zip>]
//
// The built mod (and Distant Horizons, unless --no-dh) stay in the instance's mods folder. The test request is used up
// by the launch and shader settings are restored afterwards, so the instance plays normally between runs.
const fs = require("fs");
const path = require("path");
const { spawn, spawnSync } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const PRISM = path.join(process.env.LOCALAPPDATA, "Programs/PrismLauncher/prismlauncher.exe");
const DATA = path.join(process.env.APPDATA, "PrismLauncher");
const DH_URL = "https://cdn.modrinth.com/data/uCdwusMi/versions/6UnEfsRQ/DistantHorizons-3.3.3-1.20.1-fabric-forge.jar";
const TIME_LIMIT = 40 * 60 * 1000;

const args = process.argv.slice(2);
const option = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const instanceName = option("--instance", "TerraFirmaGreg-Modern 0.13.17");
const instance = path.join(DATA, "instances", instanceName);
const game = path.join(instance, ".minecraft");

function setCfg(text, values) {
  const lines = text.split(/\r?\n/);
  for (const [key, value] of Object.entries(values)) {
    const i = lines.findIndex(l => l.startsWith(key + "="));
    if (i >= 0) lines[i] = `${key}=${value}`;
    else lines.splice(1, 0, `${key}=${value}`);
  }
  return lines.join("\n");
}

function listJava() {
  const script = "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | ForEach-Object { $_.CommandLine }";
  return spawnSync("powershell", ["-NoProfile", "-Command", script], { encoding: "utf8" }).stdout || "";
}

function prismRunning() {
  return (spawnSync("tasklist", ["/FI", "IMAGENAME eq prismlauncher.exe"], { encoding: "utf8" }).stdout || "").includes("prismlauncher.exe");
}

async function main() {
  // The dev test doesn't produce this jar, so testing without building can silently test old code.
  const built = spawnSync(`"${path.join(ROOT, "gradlew.bat")}" build --no-daemon -q`, { cwd: ROOT, shell: true, stdio: "inherit", env: { ...process.env, JAVA_HOME: "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.20.101-hotspot" } });
  if (built.status !== 0) throw new Error("Build failed");

  const version = fs.readFileSync(path.join(ROOT, "gradle.properties"), "utf8").match(/mod_version\s*=\s*(\S+)/)[1];
  const mods = path.join(game, "mods");
  for (const f of fs.readdirSync(mods)) if (f.startsWith("flashback_reforged-") || f.startsWith("flashforge-")) fs.rmSync(path.join(mods, f));
  fs.copyFileSync(path.join(ROOT, "build/libs", `flashback_reforged-${version}.jar`), path.join(mods, `flashback_reforged-${version}.jar`));

  const dhFile = path.join(mods, path.basename(DH_URL));
  if (args.includes("--no-dh")) {
    fs.rmSync(dhFile, { force: true });
  } else if (!fs.existsSync(dhFile)) {
    fs.writeFileSync(dhFile, Buffer.from(await (await fetch(DH_URL)).arrayBuffer()));
  }

  // The mod reads and removes this file. Instance settings can't carry it: an open Prism keeps them cached.
  const request = [`flashback_reforged.selftest=${option("--scenario", "full")}`, "flashback_reforged.selftest.world=flashback_reforged_test"];
  if (option("--preset")) request.push(`flashback_reforged.selftest.preset=${option("--preset")}`);
  if (option("--ticks")) request.push(`flashback_reforged.selftest.ticks=${option("--ticks")}`);
  const requestFile = path.join(game, "flashback_reforged-selftest.properties");
  fs.writeFileSync(requestFile, request.join("\n") + "\n");

  // A fresh world each run: a run that crashed can leave a half generated one behind.
  fs.rmSync(path.join(game, "saves/flashback_reforged_test"), { recursive: true, force: true });

  const irisPath = path.join(game, "config/oculus.properties");
  const irisBefore = fs.existsSync(irisPath) ? fs.readFileSync(irisPath, "utf8") : null;
  const shader = option("--shader");
  fs.writeFileSync(irisPath, setCfg(irisBefore || "#\n", { shaderPack: shader || "", enableShaders: shader ? "true" : "false" }));

  const result = path.join(game, "flashback_reforged-selftest.json");
  fs.rmSync(result, { force: true });
  const crashes = path.join(game, "crash-reports");
  const crashesBefore = new Set(fs.existsSync(crashes) ? fs.readdirSync(crashes) : []);

  const wasRunning = prismRunning();
  const started = Date.now();
  spawn(PRISM, ["--launch", instanceName, "--offline", "FBReforgedTest"], { detached: true, stdio: "ignore" }).unref();

  // The instance's folder name appears in the game's command line, which is how its process is recognised.
  const needle = instanceName;
  let seen = false;
  let outcome = "timed out";
  while (Date.now() - started < TIME_LIMIT) {
    await new Promise(r => setTimeout(r, 10000));
    const running = listJava().includes(needle);
    seen = seen || running;
    if (seen && !running) {
      outcome = "game exited";
      break;
    }
    if (!seen && Date.now() - started > 180000) {
      outcome = "game never started";
      break;
    }
  }

  fs.rmSync(requestFile, { force: true });
  if (irisBefore === null) fs.rmSync(irisPath, { force: true });
  else fs.writeFileSync(irisPath, irisBefore);
  if (!wasRunning) spawnSync("taskkill", ["/IM", "prismlauncher.exe"]);

  console.log(`${outcome} after ${Math.round((Date.now() - started) / 1000)}s`);
  console.log(fs.existsSync(result) ? fs.readFileSync(result, "utf8") : "NO RESULT FILE");
  const newCrashes = (fs.existsSync(crashes) ? fs.readdirSync(crashes) : []).filter(f => !crashesBefore.has(f));
  if (newCrashes.length) console.log("NEW CRASH REPORTS:\n" + newCrashes.map(f => path.join(crashes, f)).join("\n"));
}

main().catch(e => {
  console.error(e);
  process.exit(1);
});
