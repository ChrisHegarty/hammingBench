package org.chegar;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@Fork(1)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
public class BitCountBenchmark {
    @Param({ "100000" })
    int times = 100_000;

    @Param({ "1024" })
    int size = 1024;

    long[][] la;
    int[][] ia;

    @Setup
    public void setup() {
        Random rand = new Random();
        this.ia = new int[times][size];
        this.la = new long[times][size / 2];
        for (int i = 0; i < times; i++) {
            for (int j = 0; j < size / 2; j++) {
                int x = rand.nextInt();
                int y = rand.nextInt();
                ia[i][j * 2] = x;
                ia[i][j * 2 + 1] = y;
                la[i][j] = (((long)x) << 32) | (y & 0xffffffffL);
            }
        }

        if (bitCountLong() != bitCountInt()) {
            throw new AssertionError();
        }
    }

    @Benchmark
    public int bitCountLong() {
        int tot = 0;
        for (int i = 0; i < times; i++) {
            tot += longBitCount(la[i]);
        }
        return tot;
    }

    @Benchmark
    public int bitCountInt()  {
        int tot = 0;
        for (int i = 0; i < times; i++) {
            tot += intBitCount(ia[i]);
        }
        return tot;    }

    static int longBitCount(long[] la) {
        int distance = 0;
        for (int i = 0;
             i < la.length;
             i++) {
            long v = la[i];
            int c = Long.bitCount(v);
            distance += c;
        }
        return distance;
    }

    static int intBitCount(int[] ia) {
        int distance = 0;
        for (int i = 0;
             i < ia.length;
             i++) {
            int v = ia[i];
            int c = Integer.bitCount(v);
            distance += c;
        }
        return distance;
    }

    public static void main(String... args) {
        BitCountBenchmark bench = new BitCountBenchmark();
        bench.setup();

        int res = 0;
        if (args.length == 1 && args[0].equals("-int")) {
            System.out.println("running int ...");
            res += bench.bitCountInt();
        } else {
            System.out.println("running long ...");
            res += bench.bitCountLong();
        }
        System.out.println(res);
    }
}
