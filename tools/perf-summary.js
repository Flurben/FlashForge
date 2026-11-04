#!/usr/bin/env node
// Prints a profiling result readably, or two side by side.  node tools/perf-summary.js <a.json> [b.json]
const fs = require("fs");
const files = process.argv.slice(2).map(f => JSON.parse(fs.readFileSync(f, "utf8")));
const show = (title, rows) => {
  console.log("\n== " + title);
  for (const r of rows) console.log(r);
};
const pad = (s, n) => String(s).padEnd(n);
for (const [i, r] of files.entries()) {
  console.log(`\n######## ${process.argv[2 + i]}  v${r.version} mods=${r.modCount} rd=${r.renderDistance} heap=${r.heapMaxMb}MB recordedTicks=${r.recordedTicks} failures=${r.packetFailures} applied=${r.packetsApplied} settle=${r.settleTicks} openToReady=${r.openToTerrainReadyTicks} ${r.crash || ""} ${r.exportError || ""}`);
  show("phases", r.phases.map(p => `${pad(p.phase, 22)} ${pad(p.seconds + "s", 9)} fps=${pad(p.fps, 8)} p50=${pad(p.frameMsP50, 7)} p95=${pad(p.frameMsP95, 7)} p99=${pad(p.frameMsP99, 7)} max=${pad(p.frameMsMax, 9)} >50ms=${pad(p.framesOver50ms, 4)} >100ms=${pad(p.framesOver100ms, 4)} gc=${p.gcCount}/${p.gcMs}ms heap=${p.heapUsedMb}`));
  for (const p of r.phases) {
    const t = Object.entries(p.timers).sort((a, b) => b[1].totalMs - a[1].totalMs).filter(([k, v]) => v.totalMs >= 3).slice(0, 26);
    if (t.length) show(p.phase + " timers", t.map(([k, v]) => `  ${pad(k, 44)} n=${pad(v.n, 8)} total=${pad(v.totalMs, 10)} avgUs=${pad(v.avgUs, 10)} maxMs=${v.maxMs}`));
    const th = Object.entries(p.threads).slice(0, 8);
    show(p.phase + " threads", th.map(([k, v]) => `  ${pad(k, 44)} cpu=${pad(v.cpuPct + "%", 9)} alloc=${v.allocMbPerSec}MB/s`));
    if (Object.keys(p.peaks).length) console.log("  peaks " + JSON.stringify(p.peaks));
  }
  if (r.seeks) show("seeks", r.seeks.map(s => `  ${pad(s.from, 6)} -> ${pad(s.to, 6)} world=${pad(s.worldMs, 7)}ms terrainReady=${s.terrainReadyMs}ms`));
  if (r.replay) {
    const a = r.replay;
    console.log(`\n== replay disk=${(a.diskBytes / 1048576).toFixed(2)}MB perMin=${a.mbPerMinute} raw=${(a.rawBytes / 1048576).toFixed(1)}MB snapshotRaw=${(a.snapshotRawBytes / 1048576).toFixed(1)}MB repeated=${(a.repeatedRawBytes / 1048576).toFixed(1)}MB other=${JSON.stringify(a.otherFiles)}`);
    for (const s of a.segments) console.log(`  ${s.file} ticks=${s.ticks} file=${(s.fileBytes / 1048576).toFixed(2)}MB snapRaw=${(s.snapshotRawBytes / 1048576).toFixed(1)}MB streamRaw=${(s.streamRawBytes / 1048576).toFixed(2)}MB`);
    for (const [k, v] of Object.entries(a.byKind).slice(0, 25)) console.log(`  ${pad(k, 52)} n=${pad(v.n, 8)} kb=${pad(v.kb, 8)} snapKb=${pad(v.snapshotKb, 8)} repeatKb=${v.repeatKb}`);
  }
  if (r.exportBytes !== undefined) console.log(`export bytes=${r.exportBytes}`);
}
