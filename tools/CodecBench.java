import io.airlift.compress.zstd.ZstdInputStream;
import io.airlift.compress.zstd.ZstdOutputStream;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.XZInputStream;
import org.tukaani.xz.XZOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compares compressors on dumped replay data, for choosing a recording format. Not part of the mod.
 *
 *   java -cp aircompressor.jar;xz.jar tools/CodecBench.java raw.bin [more files]
 */
public class CodecBench {
    interface Out {
        OutputStream wrap(OutputStream o) throws IOException;
    }

    interface In {
        InputStream wrap(InputStream i) throws IOException;
    }

    public static void main(String[] args) throws Exception {
        for (String file : args) {
            byte[] data = Files.readAllBytes(Path.of(file));
            System.out.printf("%n%s: %.1f MB raw%n", file, data.length / 1048576.0);
            run("gzip -1", data, o -> gzip(o, Deflater.BEST_SPEED), GZIPInputStream::new);
            run("gzip -6", data, o -> gzip(o, 6), GZIPInputStream::new);
            run("gzip -9", data, o -> gzip(o, 9), GZIPInputStream::new);
            run("zstd", data, ZstdOutputStream::new, ZstdInputStream::new);
            run("xz -1", data, o -> new XZOutputStream(o, new LZMA2Options(1)), XZInputStream::new);
            run("xz -6", data, o -> new XZOutputStream(o, new LZMA2Options(6)), XZInputStream::new);
        }
    }

    private static OutputStream gzip(OutputStream o, int level) throws IOException {
        return new GZIPOutputStream(o, 1 << 16, true) {
            {
                def.setLevel(level);
            }
        };
    }

    private static void run(String name, byte[] data, Out out, In in) throws Exception {
        long bestCompress = Long.MAX_VALUE, bestDecompress = Long.MAX_VALUE;
        byte[] packed = null;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(data.length / 2);
            try (OutputStream o = out.wrap(bytes)) {
                // Replay entries arrive a piece at a time, so feed this in similar pieces.
                for (int p = 0; p < data.length; p += 4096) o.write(data, p, Math.min(4096, data.length - p));
            }
            bestCompress = Math.min(bestCompress, System.nanoTime() - start);
            packed = bytes.toByteArray();
        }
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            try (InputStream s = in.wrap(new ByteArrayInputStream(packed))) {
                byte[] buffer = new byte[1 << 16];
                while (s.read(buffer) > 0) {
                }
            }
            bestDecompress = Math.min(bestDecompress, System.nanoTime() - start);
        }
        double mb = data.length / 1048576.0;
        System.out.printf("  %-8s %8.2f MB  ratio %5.2f  compress %6.0f MB/s  decompress %6.0f MB/s%n", name, packed.length / 1048576.0,
                (double) data.length / packed.length, mb / (bestCompress / 1e9), mb / (bestDecompress / 1e9));
    }
}
