package com.flurben.flashbackreforged.perf;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Timers for the profiling run. Off unless that run turns them on, so in normal play each call site costs one read of a
 * field. Work is split into named phases, and each phase reports frame times, per-thread CPU and allocation, and timers.
 */
public final class Perf {
    /** Checked before doing anything that costs more than a field read. */
    public static volatile boolean on;

    private static final Map<String, Stat> STATS = new ConcurrentHashMap<>();
    private static final Map<String, long[]> PEAKS = new ConcurrentHashMap<>();
    private static final List<Map<String, Object>> PHASES = new ArrayList<>();
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private static String phase;
    private static long phaseStart;
    private static long[] frames = new long[1 << 14];
    private static int frameCount;
    private static long lastFrame;
    private static Map<Long, long[]> threadsAtStart;
    private static long gcCountAtStart;
    private static long gcTimeAtStart;

    private Perf() {}

    private static final class Stat {
        long count;
        long total;
        long max;

        synchronized void add(long nanos) {
            this.count++;
            this.total += nanos;
            if (nanos > this.max) this.max = nanos;
        }
    }

    /** @return a start time, or 0 when timing is off. */
    public static long start() {
        return on ? System.nanoTime() : 0;
    }

    public static void end(String key, long start) {
        if (start != 0) add(key, System.nanoTime() - start);
    }

    public static void add(String key, long nanos) {
        if (!on) return;
        STATS.computeIfAbsent(key, k -> new Stat()).add(nanos);
    }

    /** Records the largest value seen in this phase. */
    public static void peak(String key, long value) {
        if (!on) return;
        long[] slot = PEAKS.computeIfAbsent(key, k -> new long[1]);
        synchronized (slot) {
            if (value > slot[0]) slot[0] = value;
        }
    }

    private static volatile long lastFrameSeen;
    private static Thread watchdog;

    /** During the profiling run, says what the render thread is doing whenever a frame takes seconds. */
    private static void startWatchdog() {
        if (watchdog != null) return;
        Thread render = Thread.currentThread();
        lastFrameSeen = System.nanoTime();
        watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long stalled = (System.nanoTime() - lastFrameSeen) / 1_000_000;
                if (!on || stalled < 1500) continue;
                StringBuilder out = new StringBuilder("[perf] no frame for " + stalled + " ms in " + phase + ":");
                StackTraceElement[] stack = render.getStackTrace();
                for (int i = 0; i < Math.min(30, stack.length); i++) out.append("\n    ").append(stack[i]);
                com.flurben.flashbackreforged.FlashbackReforged.LOGGER.info(out.toString());
            }
        }, "Flashback Reforged Stall Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Render thread, once per frame. */
    public static void frame() {
        if (!on) return;
        long now = System.nanoTime();
        lastFrameSeen = now;
        if (phase != null && lastFrame != 0) {
            if (frameCount == frames.length) frames = Arrays.copyOf(frames, frames.length * 2);
            frames[frameCount++] = now - lastFrame;
        }
        lastFrame = now;
    }

    public static void begin(String name) {
        if (phase != null) finish();
        on = true;
        startWatchdog();
        phase = name;
        phaseStart = System.nanoTime();
        frameCount = 0;
        lastFrame = 0;
        STATS.clear();
        PEAKS.clear();
        threadsAtStart = threadSample();
        long[] gc = gc();
        gcCountAtStart = gc[0];
        gcTimeAtStart = gc[1];
    }

    public static void finish() {
        if (phase == null) return;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", phase);
        double seconds = (System.nanoTime() - phaseStart) / 1e9;
        out.put("seconds", round(seconds));
        out.put("frames", frameCount);
        if (frameCount > 0) {
            long[] sorted = Arrays.copyOf(frames, frameCount);
            Arrays.sort(sorted);
            out.put("fps", round(frameCount / seconds));
            out.put("frameMsP50", ms(sorted[frameCount / 2]));
            out.put("frameMsP95", ms(sorted[(int) (frameCount * 0.95)]));
            out.put("frameMsP99", ms(sorted[(int) (frameCount * 0.99)]));
            out.put("frameMsMax", ms(sorted[frameCount - 1]));
            int over50 = 0, over100 = 0;
            for (int i = 0; i < frameCount; i++) {
                if (sorted[i] > 50_000_000L) over50++;
                if (sorted[i] > 100_000_000L) over100++;
            }
            out.put("framesOver50ms", over50);
            out.put("framesOver100ms", over100);
        }
        long[] gc = gc();
        out.put("gcCount", gc[0] - gcCountAtStart);
        out.put("gcMs", gc[1] - gcTimeAtStart);
        Runtime rt = Runtime.getRuntime();
        out.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        out.put("threads", threadDeltas(seconds));

        Map<String, Object> timers = new TreeMap<>();
        STATS.forEach((key, stat) -> {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("n", stat.count);
            t.put("totalMs", ms(stat.total));
            t.put("avgUs", stat.count == 0 ? 0 : round(stat.total / 1000.0 / stat.count));
            t.put("maxMs", ms(stat.max));
            timers.put(key, t);
        });
        out.put("timers", timers);
        Map<String, Object> peaks = new TreeMap<>();
        PEAKS.forEach((key, value) -> peaks.put(key, value[0]));
        out.put("peaks", peaks);
        PHASES.add(out);
        phase = null;
    }

    private static jdk.jfr.Recording flight;

    /** Starts a flight recording, for finding where time and memory go inside a phase. Replaces any one running. */
    public static void startFlight() {
        stopFlight(null);
        if (!Boolean.getBoolean("flashback_reforged.perf.jfr")) return;
        try {
            flight = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
            flight.start();
        } catch (Exception e) {
            com.flurben.flashbackreforged.FlashbackReforged.LOGGER.warn("Could not start a flight recording", e);
        }
    }

    public static void stopFlight(java.nio.file.Path file) {
        if (flight == null) return;
        try {
            flight.stop();
            if (file != null) flight.dump(file);
        } catch (Exception e) {
            com.flurben.flashbackreforged.FlashbackReforged.LOGGER.warn("Could not save a flight recording", e);
        }
        flight.close();
        flight = null;
    }

    public static List<Map<String, Object>> phases() {
        return PHASES;
    }

    private static double ms(long nanos) {
        return round(nanos / 1e6);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static long[] gc() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            time += Math.max(0, bean.getCollectionTime());
        }
        return new long[]{count, time};
    }

    private static long allocated(long id) {
        return THREADS instanceof com.sun.management.ThreadMXBean sun ? sun.getThreadAllocatedBytes(id) : 0;
    }

    private static Map<Long, long[]> threadSample() {
        Map<Long, long[]> out = new HashMap<>();
        for (long id : THREADS.getAllThreadIds()) out.put(id, new long[]{THREADS.getThreadCpuTime(id), allocated(id)});
        return out;
    }

    /** Busy threads, by name, with CPU as a percentage of one core. */
    private static Map<String, Object> threadDeltas(double seconds) {
        Map<String, long[]> byName = new TreeMap<>();
        for (long id : THREADS.getAllThreadIds()) {
            ThreadInfo info = THREADS.getThreadInfo(id);
            if (info == null) continue;
            long[] before = threadsAtStart.getOrDefault(id, new long[2]);
            long[] sum = byName.computeIfAbsent(info.getThreadName().replaceAll("[-#]?\\d+$", ""), k -> new long[2]);
            sum[0] += Math.max(0, THREADS.getThreadCpuTime(id) - before[0]);
            sum[1] += Math.max(0, allocated(id) - before[1]);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        byName.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0])).limit(14).forEach(e -> {
            if (e.getValue()[0] < 20_000_000L) return;
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("cpuPct", round(e.getValue()[0] / 1e9 / seconds * 100));
            t.put("allocMbPerSec", round((e.getValue()[1] >> 20) / seconds));
            out.put(e.getKey(), t);
        });
        return out;
    }
}
