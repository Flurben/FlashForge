#!/usr/bin/env node
// Puts the numbers that matter from two profiling results side by side.  node tools/perf-compare.js <before.json> <after.json>
const fs = require("fs");
const [a, b] = process.argv.slice(2, 4).map(f => JSON.parse(fs.readFileSync(f, "utf8")));
const pad = (s, n) => String(s ?? "-").padEnd(n);
const phase = (r, name) => r.phases.find(p => p.phase === name);
const timer = (r, name, key, field = "maxMs") => phase(r, name)?.timers?.[key]?.[field];
const row = (label, x, y) => console.log(`${pad(label, 46)} ${pad(x, 12)} ${pad(y, 12)}`);
console.log(`${pad("", 46)} ${pad("before", 12)} ${pad("after", 12)}`);
console.log(`failures: ${a.packetFailures} / ${b.packetFailures}, applied ${a.packetsApplied} / ${b.packetsApplied}, errors: ${a.crash || a.exportError || "none"} / ${b.crash || b.exportError || "none"}`);
for (const p of a.phases) {
  const q = phase(b, p.phase) || {};
  row(`${p.phase} fps`, p.fps, q.fps);
  row(`${p.phase} p50 / p99 ms`, `${p.frameMsP50} / ${p.frameMsP99}`, `${q.frameMsP50} / ${q.frameMsP99}`);
  row(`${p.phase} max ms, >50ms`, `${p.frameMsMax}, ${p.framesOver50ms}`, `${q.frameMsMax}, ${q.framesOver50ms}`);
  const t = o => o.threads?.["Render thread"]?.allocMbPerSec;
  row(`${p.phase} render thread alloc MB/s`, t(p), t(q));
  row(`${p.phase} gc count/ms`, `${p.gcCount}/${p.gcMs}`, `${q.gcCount}/${q.gcMs}`);
}
console.log("\nrecord timers (max ms over all record phases)");
const records = ["record_start", "record_fly_out", "record_hover_far", "record_fly_back", "record_hover_home", "record_stop"];
const maxOf = (r, key) => Math.max(0, ...records.map(n => timer(r, n, key) || 0));
for (const key of ["record.roll", "record.snapshot", "record.snapshotInstant", "record.snapshotSlice", "record.snapshotFinish", "record.thumbnail", "record.saveMeta", "record.tracker", "record.closeSegment", "record.saveRegistriesAndConfig", "writer.saveRegistriesAndConfig"]) row(key, maxOf(a, key), maxOf(b, key));
const avg = (r, key) => timer(r, "record_hover_far", key, "avgUs");
for (const key of ["record.tracker", "netty.recordPacket", "netty.journal"]) row(key + " avg us", avg(a, key), avg(b, key));
const peak = (r, key) => Math.max(0, ...records.map(n => phase(r, n)?.peaks?.[key] || 0));
for (const key of ["journal.payloadCount", "journal.payloadBytes", "writer.queueEntries", "writer.queuedBytes", "record.parkedBytes"]) row(key + " peak", peak(a, key), peak(b, key));
const writerName = r => Object.keys(phase(r, "record_fly_out").threads).find(n => n.includes("Flashback Reforged")) || "";
row("writer thread cpu% while flying", phase(a, "record_fly_out").threads[writerName(a)]?.cpuPct, phase(b, "record_fly_out").threads[writerName(b)]?.cpuPct);

console.log("\nopen and seek");
for (const key of ["open.total", "open.injectRegistries", "replay.applySnapshot", "replay.loadSegment", "handleSeeking:ClientboundLoginPacket", "handleSeeking:ClientboundUpdateTagsPacket"]) row(key, timer(a, "replay_open", key), timer(b, "replay_open", key));
row("snapshot chunks reused", ["record_fly_out", "record_hover_far", "record_fly_back", "record_hover_home"].map(n => timer(a, n, "snapshot.chunkReused", "n") || 0).join(" "), ["record_fly_out", "record_hover_far", "record_fly_back", "record_hover_home"].map(n => timer(b, n, "snapshot.chunkReused", "n") || 0).join(" "));
row("snapshot chunks encoded", ["record_fly_out", "record_hover_far", "record_fly_back", "record_hover_home"].map(n => timer(a, n, "snapshot.oneChunk", "n") || 0).join(" "), ["record_fly_out", "record_hover_far", "record_fly_back", "record_hover_home"].map(n => timer(b, n, "snapshot.oneChunk", "n") || 0).join(" "));
const payloadCosts = r => Object.entries(phase(r, "seeks").timers).filter(([k]) => k.startsWith("handleSeeking:custom:")).sort((x, y) => y[1].totalMs - x[1].totalMs).slice(0, 8).map(([k, v]) => k.replace("handleSeeking:custom:", "") + "=" + v.totalMs + "ms/" + v.n).join(" ");
console.log("payload cost after: " + payloadCosts(b));
for (const key of ["seek.total", "replay.applySnapshot", "replay.loadSegment", "seek.fastForward", "seek.resetWorld", "seek.scanSnapshot", "packet.decodeSeeking", "handleSeeking:ClientboundCustomPayloadPacket", "handleSeeking:ClientboundLevelChunkWithLightPacket"]) row(key + " total ms", timer(a, "seeks", key, "totalMs"), timer(b, "seeks", key, "totalMs"));
row("seek.chunkKept count", timer(a, "seeks", "seek.chunkKept", "n"), timer(b, "seeks", "seek.chunkKept", "n"));
row("seek.chunkKeptRestoringBlockEntities count", timer(a, "seeks", "seek.chunkKeptRestoringBlockEntities", "n"), timer(b, "seeks", "seek.chunkKeptRestoringBlockEntities", "n"));
row("handleSeeking chunks count", timer(a, "seeks", "handleSeeking:ClientboundLevelChunkWithLightPacket", "n"), timer(b, "seeks", "handleSeeking:ClientboundLevelChunkWithLightPacket", "n"));
(a.seeks || []).forEach((s, i) => {
  const t = (b.seeks || [])[i] || {};
  row(`seek ${s.from}->${s.to} world / terrain ms`, `${s.worldMs} / ${s.terrainReadyMs}`, `${t.worldMs} / ${t.terrainReadyMs}`);
});

console.log("\nexport");
for (const key of ["export.wholeFrame", "export.capture", "export.readBack", "export.waitForFreeBuffer", "export.submitWait", "export.audio", "export.waitForEncoder"]) row(key + " avg us", timer(a, "export_video", key, "avgUs"), timer(b, "export_video", key, "avgUs"));
row("video seconds", phase(a, "export_video").seconds, phase(b, "export_video").seconds);
row("png seconds", phase(a, "export_png").seconds, phase(b, "export_png").seconds);
row("export bytes", a.exportBytes, b.exportBytes);

console.log("\nreplay size");
const ra = a.replay, rb = b.replay;
row("disk MB", (ra.diskBytes / 1048576).toFixed(2), (rb.diskBytes / 1048576).toFixed(2));
row("MB per minute", ra.mbPerMinute, rb.mbPerMinute);
row("represented raw MB", (ra.rawBytes / 1048576).toFixed(1), (rb.rawBytes / 1048576).toFixed(1));
row("blobs.bin MB", "-", ((rb.otherFiles["blobs.bin"] || 0) / 1048576).toFixed(2));
row("segments MB", (ra.segments.reduce((n, s) => n + s.fileBytes, 0) / 1048576).toFixed(2), (rb.segments.reduce((n, s) => n + s.fileBytes, 0) / 1048576).toFixed(2));
for (const s of rb.segments) console.log(`  ${s.file} ticks=${s.ticks} ${(s.fileBytes / 1024).toFixed(0)}KB snapRaw=${(s.snapshotRawBytes / 1048576).toFixed(1)}MB streamRaw=${(s.streamRawBytes / 1048576).toFixed(2)}MB newContentRaw=${((s.newContentRawBytes || 0) / 1048576).toFixed(2)}MB`);
