import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Prints where a flight recording's CPU and allocation samples landed. Not part of the mod.
 *
 *   java tools/JfrTop.java file.jfr [thread name part] [lines]
 *
 * Lines naming this mod are always shown, however far down the list they are.
 */
public class JfrTop {
    static final class Table {
        final Map<String, Double> self = new HashMap<>();
        final Map<String, Double> inclusive = new HashMap<>();
        double total;

        void add(List<RecordedFrame> frames, double weight) {
            if (frames.isEmpty()) return;
            total += weight;
            self.merge(name(frames.get(0)), weight, Double::sum);
            var seen = new HashSet<String>();
            for (RecordedFrame f : frames) if (seen.add(name(f))) inclusive.merge(name(f), weight, Double::sum);
        }
    }

    static String name(RecordedFrame f) {
        String type = f.getMethod().getType().getName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + f.getMethod().getName();
    }

    public static void main(String[] args) throws Exception {
        String thread = args.length > 1 ? args[1] : "";
        int lines = args.length > 2 ? Integer.parseInt(args[2]) : 14;
        Table cpu = new Table(), alloc = new Table();
        try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
            while (file.hasMoreEvents()) {
                RecordedEvent e = file.readEvent();
                String type = e.getEventType().getName();
                boolean isCpu = type.equals("jdk.ExecutionSample"), isAlloc = type.equals("jdk.ObjectAllocationSample");
                if (!isCpu && !isAlloc) continue;
                RecordedThread t = isCpu ? e.getThread("sampledThread") : e.getThread();
                if (t == null || t.getJavaName() == null || !t.getJavaName().contains(thread) || e.getStackTrace() == null) continue;
                if (isCpu) cpu.add(e.getStackTrace().getFrames(), 1);
                else alloc.add(e.getStackTrace().getFrames(), e.getLong("weight") / 1048576.0);
            }
        }
        print("CPU self", cpu.self, cpu.total, lines);
        print("CPU inclusive", cpu.inclusive, cpu.total, lines);
        print("Allocation self (MB)", alloc.self, alloc.total, lines);
        print("Allocation inclusive (MB)", alloc.inclusive, alloc.total, lines);
    }

    static void print(String title, Map<String, Double> values, double total, int lines) {
        System.out.printf("%n== %s, total %.0f%n", title, total);
        int[] n = {0};
        values.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(e -> {
            String k = e.getKey();
            boolean ours = k.contains("flashback") || k.contains("Recorder") || k.contains("ReplayPlayer") || k.contains("SegmentWriter") || k.contains("Snapshot") || k.contains("EditorUi") || k.contains("Panel") || k.contains("LocalHud") || k.contains("LocalPlayerTracker") || k.contains("PacketCodec") || k.contains("Journal") || k.contains("ImGui") || k.contains("FrameCapture") || k.contains("Visuals");
            if (n[0]++ < lines || (ours && e.getValue() / total > 0.001)) System.out.printf("  %5.1f%%  %s%n", e.getValue() / total * 100, k);
        });
    }
}
