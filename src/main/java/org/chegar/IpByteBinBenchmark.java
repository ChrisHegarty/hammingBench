package org.chegar;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.apache.lucene.util.BitUtil;
import org.openjdk.jmh.annotations.*;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static jdk.incubator.vector.VectorOperators.B2S;

// TODO: lower fork count to speedup dev iterations
@Fork(value = 1, jvmArgsPrepend = {"--add-modules=jdk.incubator.vector"})
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
/**
 * Output is ops per microsecond - so bigger is better.
 *
 * Build with maven, e.g.
 *  $ JAVA_HOME=/Users/chegar/binaries/jdk-22.0.2.jdk/Contents/Home/ mvn clean verify
 *
 * Run on the command line:
 *  /Users/chegar/binaries/jdk-22.0.2.jdk/Contents/Home/bin/java \
 *  --add-modules=jdk.incubator.vector \
 *  -jar target/benchmarks.jar \
 *  org.chegar.IpByteBinBenchmark.ipByteBin.*
 *
 *  The final arg is a regex of the benchmark, so ipByteBinConst.* will run methods that
 *  match that regex - useful to narrow down benchmarks to run during development.
 */
public class IpByteBinBenchmark {

    public static final int B_QUERY = 4;

    @Param({ "384" })
    int dims;

    long[] qLong;
    long[] dLong;
    byte[] qBytes;
    byte[] dBytes;
    int B; // == dims

    @Setup
    public void setup() {
        Random rand = new Random();
        if (dims % Long.BYTES != 0) {
            throw new AssertionError();
        }
        B = dims;
        this.qBytes = new byte[(B * B_QUERY) / Byte.SIZE];
        this.dBytes = new byte[B / Byte.SIZE];
        this.qLong = new long[(B * B_QUERY) / Long.SIZE];
        this.dLong = new long[B / Long.SIZE];
        for (int i = 0; i < qLong.length; i++) {
            var l = rand.nextLong();
            qLong[i] = l;
            BitUtil.VH_NATIVE_LONG.set(qBytes, i * Long.BYTES, l);
        }
        for (int i = 0; i < dLong.length; i++) {
            var l = rand.nextLong();
            dLong[i] = l;
            BitUtil.VH_NATIVE_LONG.set(dBytes, i * Long.BYTES, l);
        }

        sanity();
    }

    // just for debugging
     public static void main(String... args) {
         var test = new IpByteBinBenchmark();
         test.dims = 384;
         test.setup();
     }

    @Benchmark
    public long ipByteBinLongBench() {
        return ipByteBin(qLong, dLong, B);
    }

    @Benchmark
    public long ipByteBinByteBench() {
        return ipByteBinByte(qBytes, dBytes);
    }

    @Benchmark
    public long ipByteBinByteLongBench() {
        return ipByteBinByteLong(qBytes, dBytes);
    }

    @Benchmark
    public long ipByteBinConstLongBench() {
        return ipByteBinConst(qLong, dLong);
    }

    @Benchmark
    public long ipByteBinConstUnrolledLongBench() {
        return ipByteBinConstUnrolled(qLong, dLong);
    }

    @Benchmark
    public long ipByteBinConstUnrolledUnrolledLongBench() {
        return ipByteBinConstUnrolledUnrolled(qLong, dLong);
    }

    @Benchmark
    public long ipByteBinConstUnrolled2LongBench() {
        return ipByteBinConstUnrolled2(qLong, dLong);
    }

    @Benchmark
    public long ipByteBinPanByteBench() {
        return ipByteBinBytePan(qBytes, dBytes);
    }

    @Benchmark
    public long ipByteBinPanByteBenchUnrolled() {
        return ipByteBinBytePanUnrolled(qBytes, dBytes);
    }

    @Benchmark
    public long ipByteBinPanByteBenchWideCount() {
        return ipByteBinBytePanWideCount(qBytes, dBytes);
    }

    public static long ipByteBin(long[] q, long[] d, int B) {
        long ret = 0;
        int size = B / 64;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            for (int j = 0; j < size; j++) {
                long estimatedDist = q[i * size + j] & d[j];
                subRet += Long.bitCount(estimatedDist);
            }
            ret += subRet << i;
        }
        return ret;
    }

    public static long ipByteBinByte(byte[] q, byte[] d) {
        long ret = 0;
        int size = d.length;
        for (int i = 0; i < B_QUERY; i++) {
            int r = 0;
            long subRet = 0;
            for (final int upperBound = d.length & -Integer.BYTES; r < upperBound; r += Integer.BYTES) {
                subRet +=
                        Integer.bitCount(
                                (int) BitUtil.VH_NATIVE_INT.get(q, i * size + r)
                                        & (int) BitUtil.VH_NATIVE_INT.get(d, r));
            }
            for (; r < d.length; r++) {
                subRet += Integer.bitCount((q[i * size + r] & d[i]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    public static long ipByteBinByteLong(byte[] q, byte[] d) {
      long ret = 0;
      int size = d.length;
      for (int i = 0; i < B_QUERY; i++) {
        int r = 0;
        long subRet = 0;
        for (final int upperBound = d.length & -Long.BYTES; r < upperBound; r += Long.BYTES) {
          subRet +=
              Long.bitCount(
                  (long) BitUtil.VH_NATIVE_LONG.get(q, i * size + r)
                      & (long) BitUtil.VH_NATIVE_LONG.get(d, r));
        }
        for (; r < d.length; r++) {
          subRet += Long.bitCount((q[i * size + r] & d[i]) & 0xFF);
        }
        ret += subRet << i;
      }
      return ret;
    }

    // Using constants, for B_QUERY and B, give 2x perf
    // observation: no vectorization, popcnt yes, but on rbx etc, 64 bits at a time.
    //  all loops unrolled. 4x6 popcnts directly
    static long ipByteBinConst(long[] q, long[] d) {
        long ret = 0;
        for (int i = 0; i < 4; i++) { // B_QUERY: 4
            long subRet = 0;
            for (int j = 0; j < 6; j++) { // size: 6
                long estimatedDist = q[i * 6 + j] & d[j];
                subRet += Long.bitCount(estimatedDist);
            }
            ret += subRet << i;
        }
        return ret;
    }

    // IpByteBinBenchmark.ipByteBinBench                           thrpt    5   44.771 ± 0.130  ops/us
    // IpByteBinBenchmark.ipByteBinConstBench                      thrpt    5   99.366 ± 0.179  ops/us
    // IpByteBinBenchmark.ipByteBinConstUnrolledBench              thrpt    5  130.479 ± 0.832  ops/us
    // IpByteBinBenchmark.ipByteBinConstUnrolledUnrolledBench      thrpt    5  107.191 ± 2.864  ops/us
    static long ipByteBinConstUnrolled(long[] q, long[] d) {
        long ret = 0;
        for (int i = 0; i < 4; i++) { // B_QUERY: 4
            long estimatedDist0 = q[i * 6 + 0] & d[0];
            int subRet0 = Long.bitCount(estimatedDist0);
            long estimatedDist1 = q[i * 6 + 1] & d[1];
            int subRet1 = Long.bitCount(estimatedDist1);
            long estimatedDist2 = q[i * 6 + 2] & d[2];
            int subRet2 = Long.bitCount(estimatedDist2);
            long estimatedDist3 = q[i * 6 + 3] & d[3];
            int subRet3 = Long.bitCount(estimatedDist3);
            long estimatedDist4 = q[i * 6 + 4] & d[4];
            int subRet4 = Long.bitCount(estimatedDist4);
            long estimatedDist5 = q[i * 6 + 5] & d[5];
            int subRet5 = Long.bitCount(estimatedDist5);
            ret += (subRet0 + subRet1 + subRet2 + subRet3 + subRet4 + subRet5) << i;
        }
        return ret;
    }

    // IpByteBinBenchmark.ipByteBinConstUnrolledUnrolledBench      thrpt    5  107.191 ± 2.864  ops/us
    static long ipByteBinConstUnrolledUnrolled(long[] q, long[] d) {
        long acc0, acc1, acc2, acc3;
        {
            long estimatedDist0 = q[0] & d[0];
            int subRet0 = Long.bitCount(estimatedDist0);
            long estimatedDist1 = q[1] & d[1];
            int subRet1 = Long.bitCount(estimatedDist1);
            long estimatedDist2 = q[2] & d[2];
            int subRet2 = Long.bitCount(estimatedDist2);
            long estimatedDist3 = q[3] & d[3];
            int subRet3 = Long.bitCount(estimatedDist3);
            long estimatedDist4 = q[4] & d[4];
            int subRet4 = Long.bitCount(estimatedDist4);
            long estimatedDist5 = q[5] & d[5];
            int subRet5 = Long.bitCount(estimatedDist5);
            acc0 = (subRet0 + subRet1 + subRet2 + subRet3 + subRet4 + subRet5) << 0;
        }
        {
            long estimatedDist0 = q[6 + 0] & d[0];
            int subRet0 = Long.bitCount(estimatedDist0);
            long estimatedDist1 = q[6 + 1] & d[1];
            int subRet1 = Long.bitCount(estimatedDist1);
            long estimatedDist2 = q[6 + 2] & d[2];
            int subRet2 = Long.bitCount(estimatedDist2);
            long estimatedDist3 = q[6 + 3] & d[3];
            int subRet3 = Long.bitCount(estimatedDist3);
            long estimatedDist4 = q[6 + 4] & d[4];
            int subRet4 = Long.bitCount(estimatedDist4);
            long estimatedDist5 = q[6 + 5] & d[5];
            int subRet5 = Long.bitCount(estimatedDist5);
            acc1 = (subRet0 + subRet1 + subRet2 + subRet3 + subRet4 + subRet5) << 1;
        }
        {
            long estimatedDist0 = q[2 * 6 + 0] & d[0];
            int subRet0 = Long.bitCount(estimatedDist0);
            long estimatedDist1 = q[2 * 6 + 1] & d[1];
            int subRet1 = Long.bitCount(estimatedDist1);
            long estimatedDist2 = q[2 * 6 + 2] & d[2];
            int subRet2 = Long.bitCount(estimatedDist2);
            long estimatedDist3 = q[2 * 6 + 3] & d[3];
            int subRet3 = Long.bitCount(estimatedDist3);
            long estimatedDist4 = q[2 * 6 + 4] & d[4];
            int subRet4 = Long.bitCount(estimatedDist4);
            long estimatedDist5 = q[2 * 6 + 5] & d[5];
            int subRet5 = Long.bitCount(estimatedDist5);
            acc2 = (subRet0 + subRet1 + subRet2 + subRet3 + subRet4 + subRet5) << 2;
        }
        {
            long estimatedDist0 = q[3* 6 + 0] & d[0];
            int subRet0 = Long.bitCount(estimatedDist0);
            long estimatedDist1 = q[3 * 6 + 1] & d[1];
            int subRet1 = Long.bitCount(estimatedDist1);
            long estimatedDist2 = q[3 * 6 + 2] & d[2];
            int subRet2 = Long.bitCount(estimatedDist2);
            long estimatedDist3 = q[3 * 6 + 3] & d[3];
            int subRet3 = Long.bitCount(estimatedDist3);
            long estimatedDist4 = q[3 * 6 + 4] & d[4];
            int subRet4 = Long.bitCount(estimatedDist4);
            long estimatedDist5 = q[3 * 6 + 5] & d[5];
            int subRet5 = Long.bitCount(estimatedDist5);
            acc3 = (subRet0 + subRet1 + subRet2 + subRet3 + subRet4 + subRet5) << 3;
        }
        return acc0 + acc1 + acc2 + acc3;
    }

    static long ipByteBinConstUnrolled2(long[] q, long[] d) {
        long sum0 = 0, sum1 = 0, sum2 = 0, sum3 = 0;
        for (int i = 0; i < 6; i++) {
            sum0 += Long.bitCount(q[i] & d[i]);
            sum1 += Long.bitCount(q[6 + i] & d[i]);
            sum2 += Long.bitCount(q[2 * 6 + i] & d[i]);
            sum3 += Long.bitCount(q[3 * 6 + i] & d[i]);
        }
        return (sum0) + (sum1 << 1) + (sum2 << 2) + (sum3 << 3);
    }

    static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

    public static long ipByteBinBytePan(byte[] q, byte[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            int limit = BYTE_SPECIES.loopBound(d.length);
            int r = 0;
            for (; r < limit; r+=BYTE_SPECIES.length()) {
                ByteVector vq = ByteVector.fromArray(BYTE_SPECIES, q, d.length * i + r);
                ByteVector vd = ByteVector.fromArray(BYTE_SPECIES, d, r);
                ByteVector vres = vq.and(vd);
                vres = vres.lanewise(VectorOperators.BIT_COUNT);
                subRet += vres.reduceLanes(VectorOperators.ADD);
            }
            for (; r < d.length; r++) {
                subRet += Integer.bitCount((q[i * d.length + r] & d[r]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    public static long ipByteBinBytePanWideCount(byte[] q, byte[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            int limit = BYTE_SPECIES.loopBound(d.length);
            int r = 0;
            ByteVector sum = ByteVector.zero(BYTE_SPECIES);
            for (; r < limit; r+=BYTE_SPECIES.length()) {
                ByteVector vq = ByteVector.fromArray(BYTE_SPECIES, q, d.length * i + r);
                ByteVector vd = ByteVector.fromArray(BYTE_SPECIES, d, r);
                ByteVector vres = vq.and(vd);
                vres = vres.lanewise(VectorOperators.BIT_COUNT);
                sum = sum.add(vres);
            }
            ShortVector sumShort1 = sum.convertShape(B2S, ShortVector.SPECIES_PREFERRED, 0).reinterpretAsShorts();
            ShortVector sumShort2 = sum.convertShape(B2S, ShortVector.SPECIES_PREFERRED, 1).reinterpretAsShorts();
            subRet += (sumShort1.reduceLanes(VectorOperators.ADD) + sumShort2.reduceLanes(VectorOperators.ADD));
            for (; r < d.length; r++) {
                subRet += Integer.bitCount((q[i * d.length + r] & d[r]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    public static long ipByteBinBytePanUnrolled(byte[] q, byte[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int limit = BYTE_SPECIES.loopBound(d.length);
        int r = 0;
        for (; r < limit; r+=BYTE_SPECIES.length()) {
            ByteVector vq0 = ByteVector.fromArray(BYTE_SPECIES, q, r);
            ByteVector vq1 = ByteVector.fromArray(BYTE_SPECIES, q, r + d.length);
            ByteVector vq2 = ByteVector.fromArray(BYTE_SPECIES, q, r + 2 * d.length);
            ByteVector vq3 = ByteVector.fromArray(BYTE_SPECIES, q, r + 3 * d.length);
            ByteVector vd = ByteVector.fromArray(BYTE_SPECIES, d, r);
            ByteVector vres0 = vq0.and(vd);
            ByteVector vres1 = vq1.and(vd);
            ByteVector vres2 = vq2.and(vd);
            ByteVector vres3 = vq3.and(vd);
            vres0 = vres0.lanewise(VectorOperators.BIT_COUNT);
            subRet0 += vres0.reduceLanes(VectorOperators.ADD);
            vres1 = vres1.lanewise(VectorOperators.BIT_COUNT);
            subRet1 += vres1.reduceLanes(VectorOperators.ADD);
            vres2 = vres2.lanewise(VectorOperators.BIT_COUNT);
            subRet2 += vres2.reduceLanes(VectorOperators.ADD);
            vres3 = vres3.lanewise(VectorOperators.BIT_COUNT);
            subRet3 += vres3.reduceLanes(VectorOperators.ADD);
        }
        for (; r < d.length; r++) {
            subRet0 += Integer.bitCount((q[r] & d[r]) & 0xFF);
            subRet1 += Integer.bitCount((q[r + d.length] & d[r]) & 0xFF);
            subRet2 += Integer.bitCount((q[r + 2 * d.length] & d[r]) & 0xFF);
            subRet3 += Integer.bitCount((q[r + 3 * d.length] & d[r]) & 0xFF);
        }
        ret += subRet0;
        ret += subRet1 << 1;
        ret += subRet2 << 2;
        ret += subRet3 << 3;
        return ret;
    }

    void sanity() {
        final long expected = ipByteBinLongBench();
        if (ipByteBinConstLongBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinConstLongBench:" + ipByteBinConstLongBench());
        }
        if (ipByteBinConstUnrolledLongBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinConstUnrolledLongBench:" + ipByteBinConstUnrolledLongBench());
        }
        if (ipByteBinConstUnrolledUnrolledLongBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinConstUnrolledUnrolledLongBench:" + ipByteBinConstUnrolledUnrolledLongBench());
        }
        if (ipByteBinByteBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinByteBench:" + ipByteBinByteBench());
        }
        if (ipByteBinByteLongBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinByteLongBench:" + ipByteBinByteLongBench());
        }
        if (ipByteBinPanByteBenchUnrolled() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinPanByteBenchUnrolled:" + ipByteBinPanByteBenchUnrolled());
        }
        if (ipByteBinPanByteBenchWideCount() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinPanByteBenchWideCount:" + ipByteBinPanByteBenchWideCount());
        }
        if (ipByteBinConstUnrolled2LongBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipByteBinConstUnrolled2LongBench:" + ipByteBinConstUnrolled2LongBench());
        }
    }
}
