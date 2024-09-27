package org.chegar;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(org.openjdk.jmh.annotations.Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
public class MemorySegmentBench {

    static final ValueLayout.OfLong LAYOUT_LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    Arena arena;
    MemorySegmentIndexInput indexInput;
    int offset;

    @Setup
    public void setup() throws Exception {
        arena = Arena.ofConfined();
        MemorySegment segment;
        Path path = Path.of("some.file");
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW)) {
            Files.write(path, new byte[100]);
        }
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            segment = channel.map(FileChannel.MapMode.READ_ONLY, 0, 100, arena);
            indexInput = MemorySegmentIndexInput.newInstance(segment);
        }
        offset = 12; // just some random offset

        // pollution
        var otherIndexInput = MemorySegmentIndexInput.newOtherInstance(segment);
        for (int i = 0; i < 1000; i++) {
            otherIndexInput.readLong(20);
            readLongTrampoline(otherIndexInput);
        }
    }

    @Benchmark
    public long readLongBench() {
        return readLongTrampoline(indexInput);
    }

    long readLongTrampoline(MemorySegmentIndexInput indexInput) {
        return indexInput.readLong(offset);
    }

    static abstract class MemorySegmentIndexInput {
        final MemorySegment segment;

        MemorySegmentIndexInput(MemorySegment segment) {
            this.segment = segment;
        }

        static MemorySegmentIndexInput newInstance(MemorySegment segment) {
            return new SingleSegmentImpl(segment);
        }

        static MemorySegmentIndexInput newOtherInstance(MemorySegment segment) {
            return new OtherSegmentImpl(segment);
        }

        long readLong(long offset) {
            try {
                return segment.get(LAYOUT_LE_LONG, offset);
            } catch (Exception e) {
                int x = 1;
                for (int i = 0; i < 10; i++) {
                    x += 2;
                    x += x;
                    x++;
                    x += x;
                }
                assert false;
                return x;

            }
        }

        static class SingleSegmentImpl extends MemorySegmentIndexInput {
            SingleSegmentImpl(MemorySegment segment) {
                super(segment);
            }

            @Override
            long readLong(long offset) {
                return segment.get(LAYOUT_LE_LONG, offset);
            }
        }

        static class OtherSegmentImpl extends MemorySegmentIndexInput {
            OtherSegmentImpl(MemorySegment segment) {
                super(segment);
            }

            @Override
            long readLong(long offset) {
                return super.readLong(offset);
            }
        }
    }
}