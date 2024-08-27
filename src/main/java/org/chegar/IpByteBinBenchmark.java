package org.chegar;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;
import org.apache.lucene.util.BitUtil;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static jdk.incubator.vector.VectorOperators.B2S;
import static jdk.incubator.vector.VectorOperators.ZERO_EXTEND_B2S;

// TODO: lower fork count to speedup dev iterations
@Fork(value = 1, jvmArgsPrepend = {"--enable-preview", "--add-modules=jdk.incubator.vector"})
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
/**
 * Output is ops per microsecond - so bigger is better.
 *
 * Build with maven, e.g.
 *  $ JAVA_HOME=/Users/chegar/binaries/jdk-22.0.2.jdk/Contents/Home/ mvn clean test verify
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

    static final VarHandle VH_NATIVE_INT = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder()).withInvokeExactBehavior();
    static final VarHandle VH_NATIVE_LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder()).withInvokeExactBehavior();

    static final VectorSpecies<Byte> BYTE_64_SPECIES = ByteVector.SPECIES_64;
    static final VectorSpecies<Byte> BYTE_128_SPECIES = ByteVector.SPECIES_128;
    static final VectorSpecies<Short> SHORT_128_SPECIES = ShortVector.SPECIES_128;

    static final VectorSpecies<Byte> BYTE_PREFERRED;
    static final VectorSpecies<Short> SHORT_PREFERRED;
    static final VectorSpecies<Integer> INT_PREFERRED;
    static final VectorSpecies<Long> LONG_PREFERRED;

    static {
        // default to platform supported bitsize
        int vectorBitSize = VectorShape.preferredShape().vectorBitSize();
        // but allow easy overriding for testing
        String v = System.getProperty("maxVectorBitSize");
        if (v != null) {
            vectorBitSize = Integer.parseInt(v);
        }
        BYTE_PREFERRED = VectorSpecies.of(byte.class, VectorShape.forBitSize(vectorBitSize));
        SHORT_PREFERRED = VectorSpecies.of(short.class, VectorShape.forBitSize(vectorBitSize));
        INT_PREFERRED = VectorSpecies.of(int.class, VectorShape.forBitSize(vectorBitSize));
        LONG_PREFERRED = VectorSpecies.of(long.class, VectorShape.forBitSize(vectorBitSize));
    }

    public static final int B_QUERY = 4;

    @Param({ "384", "768", "1024" })
    int dims;

    long[] qLong;
    long[] dLong;
    byte[] qBytes;
    byte[] dBytes;
    MemorySegment qSeg;
    MemorySegment dSeg;
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
        qSeg = MemorySegment.ofArray(qBytes);
        dSeg = MemorySegment.ofArray(dBytes);
        sanity();
    }

    // just for debugging
     public static void main(String... args) {
         var test = new IpByteBinBenchmark();
         test.dims = 4096; //384;
         test.setup();
     }

    @Benchmark
    public long ipbb_longArraysScalarBench() {
        return ipbb_longArraysScalar(qLong, dLong, B);
    }

    static long ipbb_longArraysScalar(long[] q, long[] d, int B) {
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

    @Benchmark
    public long ipbb_byteArraysScalarStrideAsIntBench() {
        return ipbb_byteArraysScalarStrideAsInt(qBytes, dBytes);
    }

    static long ipbb_byteArraysScalarStrideAsInt(byte[] q, byte[] d) {
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
                subRet += Integer.bitCount((q[i * size + r] & d[r]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }


    @Benchmark
    public long ipbb_longArraysScalarConstBench() {
        return ipbb_longArraysScalarConst(qLong, dLong);
    }

    // Using constants, for B_QUERY and B, give 2x perf
    // observation: no vectorization, popcnt yes, but on rbx etc, 64 bits at a time.
    //  all loops unrolled. 4x6 popcnts directly
    static long ipbb_longArraysScalarConst(long[] q, long[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            // hardcoding the loop limit allows to unroll without vectorization. Consider for bytecode generation
            for (int j = 0; j < d.length; j++) { // size: 6  <<<<<<<<<<<<<<<<<<<<<<
                long estimatedDist = q[i * 6 + j] & d[j];
                subRet += Long.bitCount(estimatedDist);
            }
            ret += subRet << i;
        }
        return ret;
    }

    @Benchmark
    public long ipbb_byteArraysScalarConstBench() {
        return ipbb_byteArraysScalarConst(qBytes, dBytes);
    }

    // 384 bits/ 8 = 48 bytes
    // 48 bytes / 8 = 6 longs
    // delete - not fast - 34.589
//    static long ipbb_byteArraysScalarConst(byte[] q, byte[] d) {
//        long subRet0 = 0, subRet1 = 0, subRet2 = 0, subRet3 = 0;
//        int i = 0;
//        if (d.length >= 48) {
//            i = 48;
//            for (int j = 0; j < 48; j += 8) {
//                long ld = (long) VH_NATIVE_LONG.get(d, j);
//                long l0 = (long) VH_NATIVE_LONG.get(q, j);
//                long l1 = (long) VH_NATIVE_LONG.get(q, d.length + j);
//                long l2 = (long) VH_NATIVE_LONG.get(q, 2 * d.length + j);
//                long l3 = (long) VH_NATIVE_LONG.get(q, 3 * d.length + j);
//                subRet0 += Long.bitCount(l0 & ld);
//                subRet1 += Long.bitCount(l1 & ld);
//                subRet2 += Long.bitCount(l2 & ld);
//                subRet3 += Long.bitCount(l3 & ld);
//            }
//        }
//        int limit = d.length & ~(7);
//        for (; i < limit; i+=8) {
//            long ld = (long) VH_NATIVE_LONG.get(d, i);
//            long l0 = (long) VH_NATIVE_LONG.get(q, i);
//            long l1 = (long) VH_NATIVE_LONG.get(q, d.length + i);
//            long l2 = (long) VH_NATIVE_LONG.get(q, 2 * d.length + i);
//            long l3 = (long) VH_NATIVE_LONG.get(q, 3 * d.length + i);
//            subRet0 += Long.bitCount(l0 & ld);
//            subRet1 += Long.bitCount(l1 & ld);
//            subRet2 += Long.bitCount(l2 & ld);
//            subRet3 += Long.bitCount(l3 & ld);
//        }
//        // tail as bytes
//        for (; i < d.length; i++) {
//            subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
//            subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
//            subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
//            subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
//        }
//        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
//    }

    // 384 bits/ 8 = 48 bytes
    // 48 bytes / 8 = 6 longs
    // not fast - 35 ops (with -XX:-UseSuperWord), 43ops by default
    static long ipbb_byteArraysScalarConst(byte[] q, byte[] d) {
        long subRet0 = 0, subRet1 = 0, subRet2 = 0, subRet3 = 0;
        int limit = d.length & ~(7);
        for (int j = 0; j < limit; j+=Long.BYTES) {
            long ld = (long) VH_NATIVE_LONG.get(d, j);
            long l0 = (long) VH_NATIVE_LONG.get(q, j);
            long l1 = (long) VH_NATIVE_LONG.get(q, d.length + j);
            long l2 = (long) VH_NATIVE_LONG.get(q, 2 * d.length + j);
            long l3 = (long) VH_NATIVE_LONG.get(q, 3 * d.length + j);
            subRet0 += Long.bitCount(l0 & ld);
            subRet1 += Long.bitCount(l1 & ld);
            subRet2 += Long.bitCount(l2 & ld);
            subRet3 += Long.bitCount(l3 & ld);
        }
        // tail as bytes
        for (int i = limit; i < d.length; i++) {
            subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
            subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
            subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
            subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

//    static long ipbb_byteArraysScalarConst(byte[] q, byte[] d) {
//        long subRet0 = 0, subRet1 = 0, subRet2 = 0, subRet3 = 0;
//        int limit = d.length & ~(7);
//        for (int j = 0; j < limit; j+=8) {
//            long ld = (long) VH_NATIVE_LONG.get(d, j);
//            long l0 = (long) VH_NATIVE_LONG.get(q, j);
//            long l1 = (long) VH_NATIVE_LONG.get(q, d.length + j);
//            long l2 = (long) VH_NATIVE_LONG.get(q, 2 * d.length + j);
//            long l3 = (long) VH_NATIVE_LONG.get(q, 3 * d.length + j);
//            subRet0 += Long.bitCount(l0 & ld);
//            subRet1 += Long.bitCount(l1 & ld);
//            subRet2 += Long.bitCount(l2 & ld);
//            subRet3 += Long.bitCount(l3 & ld);
//        }
//        // tail as bytes
//        for (int i = limit; i < d.length; i++) {
//            subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
//            subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
//            subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
//            subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
//        }
//        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
//    }

//     static long ipbb_byteArraysScalarConst(byte[] q, byte[] d) {
//        long subRet0 = 0, subRet1 = 0, subRet2 = 0, subRet3 = 0;
//        int limit = d.length / Long.BYTES;
//        for (int j = 0; j < limit; j++) { // size: 6
//            long l1 = (long) VH_NATIVE_LONG.get(q, j * Long.BYTES);
//            long l2 = (long) VH_NATIVE_LONG.get(d, j * Long.BYTES);
//            long estimatedDist = l1 & l2;
//            subRet0 += Long.bitCount(estimatedDist);
//
//            l1 = (long) VH_NATIVE_LONG.get(q, d.length + j * Long.BYTES);
//            l2 = (long) VH_NATIVE_LONG.get(d, j * Long.BYTES);
//            estimatedDist = l1 & l2;
//            subRet1 += Long.bitCount(estimatedDist);
//
//            l1 = (long) VH_NATIVE_LONG.get(q, 2 * d.length + j * Long.BYTES);
//            l2 = (long) VH_NATIVE_LONG.get(d, j * Long.BYTES);
//            estimatedDist = l1 & l2;
//            subRet2 += Long.bitCount(estimatedDist);
//
//            l1 = (long) VH_NATIVE_LONG.get(q, 3 * d.length + j * Long.BYTES);
//            l2 = (long) VH_NATIVE_LONG.get(d, j * Long.BYTES);
//            estimatedDist = l1 & l2;
//            subRet3 += Long.bitCount(estimatedDist);
//        }
//         // tail as bytes
//         for (int i = limit * Long.BYTES; i < d.length; i++) {
//             subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
//             subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
//             subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
//             subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
//         }
//        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
//    }
//    static long ipbb_byteArraysScalarConst(byte[] q, byte[] d) {
//        long ret = 0;
//        for (int i = 0; i < B_QUERY; i++) {
//            long subRet = 0;
//            for (int j = 0; j < d.length; j+=Long.BYTES) { // size: 6
//                long l1 = (long) VH_NATIVE_LONG.get(q, i * 6 + j);
//                long l2 = (long) VH_NATIVE_LONG.get(d, j);
//                long estimatedDist =  l1 & l2; //q[i * 6 + j] & d[j];
//                subRet += Long.bitCount(estimatedDist);
//            }
//            ret += subRet << i;
//        }
//        return ret;
//    }

    @Benchmark
    public long ipbb_longArraysScalarConstUnrolledBench() {
        return ipbb_longArraysScalarConstUnrolled(qLong, dLong);
    }

    // fastest - 130.595 ( -XX:-UseSuperWord makes no difference )
    static long ipbb_longArraysScalarConstUnrolled(long[] q, long[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
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

    @Benchmark
    public long ipbb_byteArraysScalarConstUnrolledNEW() {
        return ipbb_byteArraysScalarConstUnrolledNEW(qBytes, dBytes);
    }

    // this is fast - 125.028, but hard codes the dims (6). Uses just popcount, sequentially
    //  ( -XX:-UseSuperWord makes no difference )
    static long ipbb_byteArraysScalarConstUnrolledNEW(byte[] q, byte[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            long estimatedDist0 = (long) VH_NATIVE_LONG.get(q, i * 48) & (long) VH_NATIVE_LONG.get(d, 0);
            int subRet0 = Long.bitCount(estimatedDist0);
            long estimatedDist1 = (long) VH_NATIVE_LONG.get(q, i * 48 + Long.BYTES) & (long) VH_NATIVE_LONG.get(d, Long.BYTES);
            int subRet1 = Long.bitCount(estimatedDist1);
            long estimatedDist2 = (long) VH_NATIVE_LONG.get(q, i * 48 + 2*Long.BYTES) & (long) VH_NATIVE_LONG.get(d, 2*Long.BYTES);
            int subRet2 = Long.bitCount(estimatedDist2);
            long estimatedDist3 = (long) VH_NATIVE_LONG.get(q, i * 48 + 3*Long.BYTES) & (long) VH_NATIVE_LONG.get(d, 3*Long.BYTES);
            int subRet3 = Long.bitCount(estimatedDist3);
            long estimatedDist4 = (long) VH_NATIVE_LONG.get(q, i * 48 + 4*Long.BYTES) & (long) VH_NATIVE_LONG.get(d, 4*Long.BYTES);
            int subRet4 = Long.bitCount(estimatedDist4);
            long estimatedDist5 = (long) VH_NATIVE_LONG.get(q, i * 48 + 5*Long.BYTES) & (long) VH_NATIVE_LONG.get(d, 5*Long.BYTES);
            int subRet5 = Long.bitCount(estimatedDist5);
            ret += (subRet0 + subRet1 + subRet2 + subRet3 + subRet4 + subRet5) << i;
        }
        return ret;
    }

    @Benchmark
    public long ipbb_byteArraysScalarConstUnrolledBench() {
        return ipbb_byteArraysScalarConstUnrolled(qBytes, dBytes);
    }

    static long ipbb_byteArraysScalarConstUnrolled(byte[] q, byte[] d) {
        int ret = 0;
        int subRet0 = 0;
        int subRet1 = 0;
        int subRet2 = 0;
        int subRet3 = 0;

        int i = 0;
        for (; i < d.length - Long.BYTES; i += Long.BYTES) {
            // long dValue = (long) VH_NATIVE_LONG.get(d, i);
            subRet0 += Long.bitCount((long) VH_NATIVE_LONG.get(q, i) & (long) VH_NATIVE_LONG.get(d, i));
            subRet1 += Long.bitCount((long) VH_NATIVE_LONG.get(q, i + d.length) & (long) VH_NATIVE_LONG.get(d, i));
            subRet2 += Long.bitCount((long) VH_NATIVE_LONG.get(q, i + 2 * d.length) & (long) VH_NATIVE_LONG.get(d, i));
            subRet3 += Long.bitCount((long) VH_NATIVE_LONG.get(q, i + 3 * d.length) & (long) VH_NATIVE_LONG.get(d, i));
        }
//        for (; i < d.length - Integer.BYTES; i += Integer.BYTES) {
//            int dValue = (int) VH_NATIVE_INT.get(d, i);
//            subRet0 += Integer.bitCount((int) VH_NATIVE_INT.get(q, i) & dValue);
//            subRet1 += Integer.bitCount((int) VH_NATIVE_INT.get(q, i + d.length) & dValue);
//            subRet2 += Integer.bitCount((int) VH_NATIVE_INT.get(q, i + 2 * d.length) & dValue);
//            subRet3 += Integer.bitCount((int) VH_NATIVE_INT.get(q, i + 3 * d.length) & dValue);
//        }
        // tail as bytes
        for (; i < d.length; i++) {
            subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
            subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
            subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
            subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
        }
        ret += subRet0;
        ret += subRet1 << 1;
        ret += subRet2 << 2;
        ret += subRet3 << 3;
        return ret;
    }

    @Benchmark
    public long ipbb_longArraysScalarConstUnrolledUnrolledBench() {
        return ipbb_longArrysScalarConstUnrolledUnrolled(qLong, dLong);
    }

    static long ipbb_longArrysScalarConstUnrolledUnrolled(long[] q, long[] d) {
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


    @Benchmark
    public long ipbb_byteArraysScalar64XXBench() {
        return ipbb_byteArraysScalar64XX(qBytes, dBytes);
    }

    // delete me - no panama - not fast - 34.519
    static long ipbb_byteArraysScalar64XX(byte[] q, byte[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            int j = 0;
            for (; j < d.length - 7; j+=Long.BYTES) {
                long ld = (long) VH_NATIVE_LONG.get(d, j);
                long lq = (long) VH_NATIVE_LONG.get(q, i * d.length + j);
                subRet += Long.bitCount(ld & lq);
            }
            for (; j < d.length; j++) {
                subRet += Integer.bitCount((q[i * d.length + j] & d[j]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    @Benchmark
    public long ipbb_LongPanamaUnrolledBench() {
        return ipByteBinLongPanUnrolled(qLong, dLong);
    }

    public static long ipByteBinLongPanUnrolled(long[] q, long[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int limit = LongVector.SPECIES_PREFERRED.loopBound(d.length);
        int r = 0;
        LongVector sum0 = LongVector.zero(LongVector.SPECIES_PREFERRED);
        LongVector sum1 = LongVector.zero(LongVector.SPECIES_PREFERRED);
        LongVector sum2 = LongVector.zero(LongVector.SPECIES_PREFERRED);
        LongVector sum3 = LongVector.zero(LongVector.SPECIES_PREFERRED);

        for (; r < limit; r += LongVector.SPECIES_PREFERRED.length()) {
            LongVector vq0 = LongVector.fromArray(LongVector.SPECIES_PREFERRED, q, r);
            LongVector vq1 = LongVector.fromArray(LongVector.SPECIES_PREFERRED, q, r + d.length);
            LongVector vq2 = LongVector.fromArray(LongVector.SPECIES_PREFERRED, q, r + d.length * 2);
            LongVector vq3 = LongVector.fromArray(LongVector.SPECIES_PREFERRED, q, r + d.length * 3);
            LongVector vd = LongVector.fromArray(LongVector.SPECIES_PREFERRED, d, r);
            LongVector vres0 = vq0.and(vd).lanewise(VectorOperators.BIT_COUNT);
            LongVector vres1 = vq1.and(vd).lanewise(VectorOperators.BIT_COUNT);
            LongVector vres2 = vq2.and(vd).lanewise(VectorOperators.BIT_COUNT);
            LongVector vres3 = vq3.and(vd).lanewise(VectorOperators.BIT_COUNT);
            sum0 = sum0.add(vres0);
            sum1 = sum1.add(vres1);
            sum2 = sum2.add(vres2);
            sum3 = sum3.add(vres3);
        }
        subRet0 += sum0.reduceLanes(VectorOperators.ADD);
        subRet1 += sum1.reduceLanes(VectorOperators.ADD);
        subRet2 += sum2.reduceLanes(VectorOperators.ADD);
        subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        for (; r < d.length; r++) {
            subRet0 += Long.bitCount(q[r] & d[r]);
            subRet1 += Long.bitCount(q[r + d.length] & d[r]);
            subRet2 += Long.bitCount(q[r + 2 * d.length] & d[r]);
            subRet3 += Long.bitCount(q[r + 3 * d.length] & d[r]);
        }
        ret += subRet0;
        ret += subRet1 << 1;
        ret += subRet2 << 2;
        ret += subRet3 << 3;
        return ret;
    }

    @Benchmark
    public long ipbb_byteArraysPanamaBench2() {
        return ipbb_byteArraysPanama2(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanama2(byte[] q, byte[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            int r = 0;
            final int limit = BYTE_64_SPECIES.loopBound(d.length);
            ShortVector acc = ShortVector.zero(SHORT_128_SPECIES);
            for (; r < limit; r+=BYTE_64_SPECIES.length()) {
                var vq = (ShortVector) ByteVector.fromArray(BYTE_64_SPECIES, q, d.length * i + r).convertShape(ZERO_EXTEND_B2S, SHORT_128_SPECIES, 0);
                var vd = (ShortVector) ByteVector.fromArray(BYTE_64_SPECIES, d, r).convertShape(ZERO_EXTEND_B2S, SHORT_128_SPECIES, 0);
                var vres = vq.and(vd);
                vres = vres.lanewise(VectorOperators.BIT_COUNT);
                acc = acc.add(vres);
            }
            long subRet = acc.reduceLanes(VectorOperators.ADD);

            // tail
            for (int l = limit; l < d.length; l++) {
                subRet += Integer.bitCount((q[d.length * i + l] & d[l]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

    @Benchmark
    public long ipbb_byteArraysPanamaBench() {
        return ipbb_byteArraysPanama(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanama(byte[] q, byte[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            final int limit = BYTE_SPECIES.loopBound(d.length);
            // iterate in chunks of 256 items to ensure we don't overflow the accumulator
            for (int j = 0; j < limit; j += 256) {
                ByteVector acc = ByteVector.zero(BYTE_SPECIES);
                int innerLimit = Math.min(limit - j, 256);
                for (int k = 0; k < innerLimit; k+=BYTE_SPECIES.length()) {
                    var vq = ByteVector.fromArray(BYTE_SPECIES, q, (d.length * i) + (j + k));
                    var vd = ByteVector.fromArray(BYTE_SPECIES, d, j + k);
                    var vres = vq.and(vd);
                    acc = acc.add(vres.lanewise(VectorOperators.BIT_COUNT));
                }
                ShortVector sumShort1 = acc.reinterpretAsShorts().and((short) 0xFF);
                ShortVector sumShort2 = acc.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
                subRet += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);
            }
            // tail
            for (int l = limit; l < d.length; l++) {
                subRet += Integer.bitCount((q[d.length * i + l] & d[l]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    static int unsignedSum(ByteVector bv) {
        // convert to LongVector because Vector.get is slow
        var lv = (LongVector) bv.reinterpretAsLongs();
        return sumBytes(lv.lane(0))
                + sumBytes(lv.lane(1));  // 2 x 64
               // + sumBytes(lv.lane(2))
               // + sumBytes(lv.lane(3));
    }

    static int sumBytes(long w) {
        return ((int)w & 0xFF)
                + (((int)(w >>> 8))  & 0xFF)
                + (((int)(w >>> 16)) & 0xFF)
                + (((int)(w >>> 24)) & 0xFF)
                + (((int)(w >>> 32)) & 0xFF)
                + (((int)(w >>> 40)) & 0xFF)
                + (((int)(w >>> 48)) & 0xFF)
                + (((int)(w >>> 56)) & 0xFF);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsIntBench() {
        return ipbb_byteArraysPanamaStrideAsInt(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsInt(byte[] q, byte[] d) {
        final MemorySegment qSeg = MemorySegment.ofArray(q);
        final MemorySegment dSeg = MemorySegment.ofArray(d);
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            int limit = ByteVector.SPECIES_PREFERRED.loopBound(d.length);
            var sum = IntVector.zero(IntVector.SPECIES_PREFERRED);
            for (int r = 0; r < limit; r += ByteVector.SPECIES_PREFERRED.length()) {
                var vq = ByteVector.fromMemorySegment(ByteVector.SPECIES_PREFERRED, qSeg, d.length * i + r, ByteOrder.nativeOrder()).reinterpretAsInts();
                var vd = IntVector.fromMemorySegment(IntVector.SPECIES_PREFERRED, dSeg, r, ByteOrder.nativeOrder()).reinterpretAsInts();
                var vres = vq.and(vd);
                vres = vres.lanewise(VectorOperators.BIT_COUNT);
                sum = sum.add(vres);
            }
            long subRet = sum.reduceLanes(VectorOperators.ADD);
            // tail
            for (int l = limit; l < d.length; l++) {
                subRet += Integer.bitCount((q[d.length * i + l] & d[l]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    private static final VectorSpecies<Byte> BYTE_SPECIES_128 = ByteVector.SPECIES_128;
    private static final VectorSpecies<Byte> BYTE_SPECIES_256 = ByteVector.SPECIES_256;

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsLongUnrolled256Bench() {
        return ipbb_byteArraysPanamaStrideAsLongUnrolled256(qBytes, dBytes);
    }

    static long ipbb_byteArraysPanamaStrideAsLongUnrolled256(byte[] q, byte[] d) {
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int i = 0;

        if (d.length >= ByteVector.SPECIES_256.vectorByteSize() * 2) {
            int limit = ByteVector.SPECIES_256.loopBound(d.length);
            var sum0 = LongVector.zero(LongVector.SPECIES_256);
            var sum1 = LongVector.zero(LongVector.SPECIES_256);
            var sum2 = LongVector.zero(LongVector.SPECIES_256);
            var sum3 = LongVector.zero(LongVector.SPECIES_256);
            for (; i < limit; i += ByteVector.SPECIES_256.length()) {
                var vq0 = ByteVector.fromArray(BYTE_SPECIES_256, q, i).reinterpretAsLongs();
                var vq1 = ByteVector.fromArray(BYTE_SPECIES_256, q, i + d.length).reinterpretAsLongs();
                var vq2 = ByteVector.fromArray(BYTE_SPECIES_256, q, i + d.length * 2).reinterpretAsLongs();
                var vq3 = ByteVector.fromArray(BYTE_SPECIES_256, q, i + d.length * 3).reinterpretAsLongs();
                var vd = ByteVector.fromArray(BYTE_SPECIES_256, d, i).reinterpretAsLongs();
                sum0 = sum0.add(vq0.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum1 = sum1.add(vq1.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum2 = sum2.add(vq2.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum3 = sum3.add(vq3.and(vd).lanewise(VectorOperators.BIT_COUNT));
            }
            subRet0 += sum0.reduceLanes(VectorOperators.ADD);
            subRet1 += sum1.reduceLanes(VectorOperators.ADD);
            subRet2 += sum2.reduceLanes(VectorOperators.ADD);
            subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        }

        if (d.length - i >= ByteVector.SPECIES_128.vectorByteSize()) {
            var sum0 = LongVector.zero(LongVector.SPECIES_128);
            var sum1 = LongVector.zero(LongVector.SPECIES_128);
            var sum2 = LongVector.zero(LongVector.SPECIES_128);
            var sum3 = LongVector.zero(LongVector.SPECIES_128);
            int limit = ByteVector.SPECIES_128.loopBound(d.length);
            for (; i < limit; i += ByteVector.SPECIES_128.length()) {
                var vq0 = ByteVector.fromArray(BYTE_SPECIES_128, q, i).reinterpretAsLongs();
                var vq1 = ByteVector.fromArray(BYTE_SPECIES_128, q, i + d.length).reinterpretAsLongs();
                var vq2 = ByteVector.fromArray(BYTE_SPECIES_128, q, i + d.length * 2).reinterpretAsLongs();
                var vq3 = ByteVector.fromArray(BYTE_SPECIES_128, q, i + d.length * 3).reinterpretAsLongs();
                var vd = ByteVector.fromArray(BYTE_SPECIES_128, d, i).reinterpretAsLongs();
                sum0 = sum0.add(vq0.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum1 = sum1.add(vq1.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum2 = sum2.add(vq2.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum3 = sum3.add(vq3.and(vd).lanewise(VectorOperators.BIT_COUNT));
            }
            subRet0 += sum0.reduceLanes(VectorOperators.ADD);
            subRet1 += sum1.reduceLanes(VectorOperators.ADD);
            subRet2 += sum2.reduceLanes(VectorOperators.ADD);
            subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        }
        // tail as bytes
        for (; i < d.length; i++) {
            subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
            subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
            subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
            subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_memSegmentPanamaStrideAsLongUnrolled256Bench() {
        return ipbb_memSegmentPanamaStrideAsLongUnrolled256(qSeg, dSeg);
    }

    // just for testing
    static long ipbb_memSegmentPanamaStrideAsLongUnrolled256Sanity(byte[] q, byte[] d) {
        return ipbb_memSegmentPanamaStrideAsLongUnrolled256(MemorySegment.ofArray(q), MemorySegment.ofArray(d));
    }

    static final ByteOrder NO = ByteOrder.nativeOrder();

    static long ipbb_memSegmentPanamaStrideAsLongUnrolled256(MemorySegment q, MemorySegment d) {
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int i = 0;
        final int dSize = (int) d.byteSize();

        if (dSize >= LongVector.SPECIES_256.vectorByteSize() * 2) {
            var sum0 = LongVector.zero(LongVector.SPECIES_256);
            var sum1 = LongVector.zero(LongVector.SPECIES_256);
            var sum2 = LongVector.zero(LongVector.SPECIES_256);
            var sum3 = LongVector.zero(LongVector.SPECIES_256);
            int limit = (int) ByteVector.SPECIES_256.loopBound(d.byteSize());
            for (; i < limit; i += LongVector.SPECIES_256.vectorByteSize()) {
                var vq0 = LongVector.fromMemorySegment(LongVector.SPECIES_256, q, i, NO);
                var vq1 = LongVector.fromMemorySegment(LongVector.SPECIES_256, q, i + dSize, NO);
                var vq2 = LongVector.fromMemorySegment(LongVector.SPECIES_256, q, i + dSize * 2, NO);
                var vq3 = LongVector.fromMemorySegment(LongVector.SPECIES_256, q, i + dSize * 3, NO);
                var vd = LongVector.fromMemorySegment(LongVector.SPECIES_256, d, i, NO);
                sum0 = sum0.add(vq0.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum1 = sum1.add(vq1.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum2 = sum2.add(vq2.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum3 = sum3.add(vq3.and(vd).lanewise(VectorOperators.BIT_COUNT));
            }
            subRet0 += sum0.reduceLanes(VectorOperators.ADD);
            subRet1 += sum1.reduceLanes(VectorOperators.ADD);
            subRet2 += sum2.reduceLanes(VectorOperators.ADD);
            subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        }

        if (dSize - i >= LongVector.SPECIES_128.vectorByteSize()) {
            var sum0 = LongVector.zero(LongVector.SPECIES_128);
            var sum1 = LongVector.zero(LongVector.SPECIES_128);
            var sum2 = LongVector.zero(LongVector.SPECIES_128);
            var sum3 = LongVector.zero(LongVector.SPECIES_128);
            int limit = (int) ByteVector.SPECIES_128.loopBound(d.byteSize());
            for (; i < limit; i += LongVector.SPECIES_128.vectorByteSize()) {
                var vq0 = LongVector.fromMemorySegment(LongVector.SPECIES_128, q, i, NO);
                var vq1 = LongVector.fromMemorySegment(LongVector.SPECIES_128, q, i + dSize, NO);
                var vq2 = LongVector.fromMemorySegment(LongVector.SPECIES_128, q, i + dSize * 2, NO);
                var vq3 = LongVector.fromMemorySegment(LongVector.SPECIES_128, q, i + dSize * 3, NO);
                var vd = LongVector.fromMemorySegment(LongVector.SPECIES_128, d, i, NO);
                sum0 = sum0.add(vq0.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum1 = sum1.add(vq1.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum2 = sum2.add(vq2.and(vd).lanewise(VectorOperators.BIT_COUNT));
                sum3 = sum3.add(vq3.and(vd).lanewise(VectorOperators.BIT_COUNT));
            }
            subRet0 += sum0.reduceLanes(VectorOperators.ADD);
            subRet1 += sum1.reduceLanes(VectorOperators.ADD);
            subRet2 += sum2.reduceLanes(VectorOperators.ADD);
            subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        }
        // tail as bytes
        for (; i < dSize; i++) {
            final byte dByte = d.get(JAVA_BYTE, i);
            subRet0 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i)) & 0xFF);
            subRet1 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i + dSize)) & 0xFF);
            subRet2 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i + 2 * dSize)) & 0xFF);
            subRet3 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i + 3 * dSize)) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsLongUnrolledBench128() {
        return ipbb_byteArraysPanamaStrideAsLongUnrolled128(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsLongUnrolled128(byte[] q, byte[] d) {
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int i = 0;

        LongVector sum0 = LongVector.zero(LongVector.SPECIES_128);
        LongVector sum1 = LongVector.zero(LongVector.SPECIES_128);
        LongVector sum2 = LongVector.zero(LongVector.SPECIES_128);
        LongVector sum3 = LongVector.zero(LongVector.SPECIES_128);
        int limit = ByteVector.SPECIES_128.loopBound(d.length);
        for (; i < limit; i += ByteVector.SPECIES_128.length()) {
            var vd = ByteVector.fromArray(ByteVector.SPECIES_128, d, i).reinterpretAsLongs();
            var vq0 = ByteVector.fromArray(ByteVector.SPECIES_128, q, i).reinterpretAsLongs();
            var vq1 = ByteVector.fromArray(ByteVector.SPECIES_128, q, i + d.length).reinterpretAsLongs();
            var vq2 = ByteVector.fromArray(ByteVector.SPECIES_128, q, i + d.length * 2).reinterpretAsLongs();
            var vq3 = ByteVector.fromArray(ByteVector.SPECIES_128, q, i + d.length * 3).reinterpretAsLongs();
            sum0 = sum0.add(vq0.and(vd).lanewise(VectorOperators.BIT_COUNT));
            sum1 = sum1.add(vq1.and(vd).lanewise(VectorOperators.BIT_COUNT));
            sum2 = sum2.add(vq2.and(vd).lanewise(VectorOperators.BIT_COUNT));
            sum3 = sum3.add(vq3.and(vd).lanewise(VectorOperators.BIT_COUNT));
        }
        subRet0 += sum0.reduceLanes(VectorOperators.ADD);
        subRet1 += sum1.reduceLanes(VectorOperators.ADD);
        subRet2 += sum2.reduceLanes(VectorOperators.ADD);
        subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        // tail as bytes
        for (; i < d.length; i++) {
            int dValue = d[i];
            subRet0 += Integer.bitCount((dValue & q[i]) & 0xFF);
            subRet1 += Integer.bitCount((dValue & q[i + d.length]) & 0xFF);
            subRet2 += Integer.bitCount((dValue & q[i + 2 * d.length]) & 0xFF);
            subRet3 += Integer.bitCount((dValue & q[i + 3 * d.length]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsIntUnrolledBench128() {
        return ipbb_byteArraysPanamaStrideAsIntUnrolled128(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsIntUnrolled128(byte[] q, byte[] d) {
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int i = 0;

        var sum0 = IntVector.zero(IntVector.SPECIES_128);
        var sum1 = IntVector.zero(IntVector.SPECIES_128);
        var sum2 = IntVector.zero(IntVector.SPECIES_128);
        var sum3 = IntVector.zero(IntVector.SPECIES_128);
        int limit = ByteVector.SPECIES_128.loopBound(d.length);
        for (; i < limit; i += ByteVector.SPECIES_128.length()) {
            var vd = ByteVector.fromArray(BYTE_SPECIES_128, d, i).reinterpretAsInts();
            var vq0 = ByteVector.fromArray(BYTE_SPECIES_128, q, i).reinterpretAsInts();
            var vq1 = ByteVector.fromArray(BYTE_SPECIES_128, q, i + d.length).reinterpretAsInts();
            var vq2 = ByteVector.fromArray(BYTE_SPECIES_128, q, i + d.length * 2).reinterpretAsInts();
            var vq3 = ByteVector.fromArray(BYTE_SPECIES_128, q, i + d.length * 3).reinterpretAsInts();
            sum0 = sum0.add(vd.and(vq0).lanewise(VectorOperators.BIT_COUNT));
            sum1 = sum1.add(vd.and(vq1).lanewise(VectorOperators.BIT_COUNT));
            sum2 = sum2.add(vd.and(vq2).lanewise(VectorOperators.BIT_COUNT));
            sum3 = sum3.add(vd.and(vq3).lanewise(VectorOperators.BIT_COUNT));
        }
        subRet0 += sum0.reduceLanes(VectorOperators.ADD);
        subRet1 += sum1.reduceLanes(VectorOperators.ADD);
        subRet2 += sum2.reduceLanes(VectorOperators.ADD);
        subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        // tail as bytes
        for (; i < d.length; i++) {
            int dValue = d[i];
            subRet0 += Integer.bitCount((dValue & q[i]) & 0xFF);
            subRet1 += Integer.bitCount((dValue & q[i + d.length]) & 0xFF);
            subRet2 += Integer.bitCount((dValue & q[i + 2 * d.length]) & 0xFF);
            subRet3 += Integer.bitCount((dValue & q[i + 3 * d.length]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_memSegmentsPanamaStrideAsShortUnrolledBench128() {
        return ipByteBinByte128(qSeg, dSeg);
    }

    // testing only
    static long ipbb_memSegmentsPanamaStrideAsShortUnrolledBench128Sanity(byte[] q, byte[] d) {
        return ipByteBinByte128(MemorySegment.ofArray(q), MemorySegment.ofArray(d));
    }

    public static long ipByteBinByte128(MemorySegment q, MemorySegment d) {
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int i = 0;

        // iterate in chunks of 256 bytes to ensure we don't overflow the accumulator
        // (256bytes/16lanes=16itrs)
        final int limit = (int) ByteVector.SPECIES_128.loopBound(d.byteSize());
        final int dSize = (int) d.byteSize();
        for (; i < limit; ) {
            ByteVector acc0 = ByteVector.zero(ByteVector.SPECIES_128);
            ByteVector acc1 = ByteVector.zero(ByteVector.SPECIES_128);
            ByteVector acc2 = ByteVector.zero(ByteVector.SPECIES_128);
            ByteVector acc3 = ByteVector.zero(ByteVector.SPECIES_128);
            int innerLimit = i + Math.min(limit - i, 256);
            for (; i < innerLimit; i += ByteVector.SPECIES_128.length()) {
                var vd = ByteVector.fromMemorySegment(ByteVector.SPECIES_128, d, i, NO);
                var vq0 = ByteVector.fromMemorySegment(ByteVector.SPECIES_128, q, i, NO);
                var vq1 = ByteVector.fromMemorySegment(ByteVector.SPECIES_128, q, i + dSize, NO);
                var vq2 = ByteVector.fromMemorySegment(ByteVector.SPECIES_128, q, i + 2 * dSize, NO);
                var vq3 = ByteVector.fromMemorySegment(ByteVector.SPECIES_128, q, i + 3 * dSize, NO);
                acc0 = acc0.add(vd.and(vq0).lanewise(VectorOperators.BIT_COUNT));
                acc1 = acc1.add(vd.and(vq1).lanewise(VectorOperators.BIT_COUNT));
                acc2 = acc2.add(vd.and(vq2).lanewise(VectorOperators.BIT_COUNT));
                acc3 = acc3.add(vd.and(vq3).lanewise(VectorOperators.BIT_COUNT));
            }
            ShortVector sumShort1 = acc0.reinterpretAsShorts().and((short) 0xFF);
            ShortVector sumShort2 = acc0.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet0 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);

            sumShort1 = acc1.reinterpretAsShorts().and((short) 0xFF);
            sumShort2 = acc1.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet1 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);

            sumShort1 = acc2.reinterpretAsShorts().and((short) 0xFF);
            sumShort2 = acc2.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet2 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);

            sumShort1 = acc3.reinterpretAsShorts().and((short) 0xFF);
            sumShort2 = acc3.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet3 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);
        }
        // tail as bytes
        for (; i < d.byteSize(); i++) {
            byte dByte = d.get(JAVA_BYTE, i);
            subRet0 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i)) & 0xFF);
            subRet1 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i + d.byteSize())) & 0xFF);
            subRet2 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i + 2 * d.byteSize())) & 0xFF);
            subRet3 += Integer.bitCount((dByte & q.get(JAVA_BYTE, i + 3 * d.byteSize())) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsLongUnrolled256OnlyBench() {
        return ipbb_byteArraysPanamaStrideAsLongUnrolled256Only(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsLongUnrolled256Only(byte[] q, byte[] d) {
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int i = 0;
        LongVector sum0 = LongVector.zero(LongVector.SPECIES_256);
        LongVector sum1 = LongVector.zero(LongVector.SPECIES_256);
        LongVector sum2 = LongVector.zero(LongVector.SPECIES_256);
        LongVector sum3 = LongVector.zero(LongVector.SPECIES_256);

        int limit = ByteVector.SPECIES_256.loopBound(d.length);
        for (; i < limit; i += ByteVector.SPECIES_256.length()) {
            LongVector vq0 = ByteVector.fromArray(ByteVector.SPECIES_256, q, i).reinterpretAsLongs();
            LongVector vq1 = ByteVector.fromArray(ByteVector.SPECIES_256, q, i + d.length).reinterpretAsLongs();
            LongVector vq2 = ByteVector.fromArray(ByteVector.SPECIES_256, q, i + d.length * 2).reinterpretAsLongs();
            LongVector vq3 = ByteVector.fromArray(ByteVector.SPECIES_256, q, i + d.length * 3).reinterpretAsLongs();
            LongVector vd = ByteVector.fromArray(ByteVector.SPECIES_256, d, i).reinterpretAsLongs();
            LongVector vres0 = vq0.and(vd).lanewise(VectorOperators.BIT_COUNT);
            LongVector vres1 = vq1.and(vd).lanewise(VectorOperators.BIT_COUNT);
            LongVector vres2 = vq2.and(vd).lanewise(VectorOperators.BIT_COUNT);
            LongVector vres3 = vq3.and(vd).lanewise(VectorOperators.BIT_COUNT);
            sum0 = sum0.add(vres0);
            sum1 = sum1.add(vres1);
            sum2 = sum2.add(vres2);
            sum3 = sum3.add(vres3);
        }
        subRet0 += sum0.reduceLanes(VectorOperators.ADD);
        subRet1 += sum1.reduceLanes(VectorOperators.ADD);
        subRet2 += sum2.reduceLanes(VectorOperators.ADD);
        subRet3 += sum3.reduceLanes(VectorOperators.ADD);
        // tail as bytes
        for (; i < d.length; i++) {
            subRet0 += Integer.bitCount((q[i] & d[i]) & 0xFF);
            subRet1 += Integer.bitCount((q[i + d.length] & d[i]) & 0xFF);
            subRet2 += Integer.bitCount((q[i + 2 * d.length] & d[i]) & 0xFF);
            subRet3 += Integer.bitCount((q[i + 3 * d.length] & d[i]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsShortUnrolled128Bench() {
        return ipbb_byteArraysPanamaStrideAsShortUnrolled128(qBytes, dBytes);
    }

    //pByteBinBenchmark.ipbb_byteArraysPanamaStrideAsShortUnrolled128Bench      384  thrpt    5  100.354 ± 0.978  ops/us
    //IpByteBinBenchmark.ipbb_byteArraysPanamaStrideAsShortUnrolled128Bench      768  thrpt    5   76.854 ± 0.577  ops/us
    //IpByteBinBenchmark.ipbb_byteArraysPanamaStrideAsShortUnrolled128Bench     1024  thrpt    5   67.999 ± 0.399  ops/us
    public static long ipbb_byteArraysPanamaStrideAsShortUnrolled128(byte[] q, byte[] d) {
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int i = 0;

        final int limit = BYTE_128_SPECIES.loopBound(d.length);
        // iterate in chunks of 256 bytes to ensure we don't overflow the accumulator (256bytes/16lanes=16itrs)
        while (i < limit) {
            ByteVector acc0 = ByteVector.zero(BYTE_128_SPECIES);
            ByteVector acc1 = ByteVector.zero(BYTE_128_SPECIES);
            ByteVector acc2 = ByteVector.zero(BYTE_128_SPECIES);
            ByteVector acc3 = ByteVector.zero(BYTE_128_SPECIES);
            int innerLimit = i + Math.min(limit - i, 256);
            for (; i < innerLimit; i += BYTE_128_SPECIES.length()) {
                ByteVector vd = ByteVector.fromArray(BYTE_128_SPECIES, d, i);
                ByteVector vq0 = ByteVector.fromArray(BYTE_128_SPECIES, q, i);
                ByteVector vq1 = ByteVector.fromArray(BYTE_128_SPECIES, q, i + d.length);
                ByteVector vq2 = ByteVector.fromArray(BYTE_128_SPECIES, q, i + 2 * d.length);
                ByteVector vq3 = ByteVector.fromArray(BYTE_128_SPECIES, q, i + 3 * d.length);
                acc0 = acc0.add(vd.and(vq0).lanewise(VectorOperators.BIT_COUNT));
                acc1 = acc1.add(vd.and(vq1).lanewise(VectorOperators.BIT_COUNT));
                acc2 = acc2.add(vd.and(vq2).lanewise(VectorOperators.BIT_COUNT));
                acc3 = acc3.add(vd.and(vq3).lanewise(VectorOperators.BIT_COUNT));
            }
            ShortVector sumShort1 = acc0.reinterpretAsShorts().and((short) 0xFF);
            ShortVector sumShort2 = acc0.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet0 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);

            sumShort1 = acc1.reinterpretAsShorts().and((short) 0xFF);
            sumShort2 = acc1.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet1 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);

            sumShort1 = acc2.reinterpretAsShorts().and((short) 0xFF);
            sumShort2 = acc2.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet2 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);

            sumShort1 = acc3.reinterpretAsShorts().and((short) 0xFF);
            sumShort2 = acc3.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
            subRet3 += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);
        }
        // tail as bytes
        for (; i < d.length; i++) {
            byte dValue = d[i];
            subRet0 += Integer.bitCount((dValue & q[i]) & 0xFF);
            subRet1 += Integer.bitCount((dValue & q[i + d.length]) & 0xFF);
            subRet2 += Integer.bitCount((dValue & q[i + 2 * d.length]) & 0xFF);
            subRet3 += Integer.bitCount((dValue & q[i + 3 * d.length]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsShortBench() {
        return ipbb_byteArraysPanamaStrideAsShort128(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsShort128(byte[] q, byte[] d) {
        long ret = 0;
        for (int i = 0; i < B_QUERY; i++) {
            final int qOffset = d.length * i;
            long subRet = 0;
            final int limit = BYTE_128_SPECIES.loopBound(d.length);
            // iterate in chunks of 256 bytes to ensure we don't overflow the accumulator (256bytes/16lanes=16itrs)
            for (int j =0; j < limit; j += 256) {
                ByteVector acc = ByteVector.zero(BYTE_128_SPECIES);
                int innerLimit = Math.min(limit - i, 256);
                for (int k = 0; k < innerLimit; k += BYTE_128_SPECIES.length()) {
                    ByteVector vq = ByteVector.fromArray(BYTE_128_SPECIES, q, qOffset + j + k);
                    ByteVector vd = ByteVector.fromArray(BYTE_128_SPECIES, d, j + k);
                    ByteVector vres = vq.and(vd);
                    vres = vres.lanewise(VectorOperators.BIT_COUNT);
                    acc = acc.add(vres);
                }
                ShortVector sumShort1 = acc.reinterpretAsShorts().and((short) 0xFF);
                ShortVector sumShort2 = acc.reinterpretAsShorts().lanewise(VectorOperators.LSHR, 8);
                subRet += sumShort1.add(sumShort2).reduceLanes(VectorOperators.ADD);
            }
            //tail
            for (int t = limit; t < d.length; t++) {
                subRet += Integer.bitCount((q[qOffset + t] & d[t]) & 0xFF);
            }
            ret += subRet << i;
        }
        return ret;
    }

    @Benchmark
    public long ipByteBinBytePanWags() {
        return ipByteBinBytePanWags(qBytes, dBytes);
    }

    public static long ipByteBinBytePanWags(byte[] q, byte[] d) {
        int vectorSize = d.length / BYTE_SPECIES.length();
        long ret = 0;
        int size = d.length;
        for (int i = 0; i < B_QUERY; i++) {
            long subRet = 0;
            for (int r = 0; r < vectorSize; r++) {
                int offset = BYTE_SPECIES.length() * r;
                ByteVector vq = ByteVector.fromArray(BYTE_SPECIES, q, d.length * i + offset);
                ByteVector vd = ByteVector.fromArray(BYTE_SPECIES, d, offset);
                ByteVector vres = vq.and(vd);
                vres = vres.lanewise(VectorOperators.BIT_COUNT);
                subRet += Math.abs(vres.reduceLanes(VectorOperators.ADD));
            }

            // FIXME: come back and pad the arrays with zeros instead of dealing with the tail?
            // tail
            int remainder = d.length % BYTE_SPECIES.length();
            if(remainder != 0) {
                for(int j = d.length-remainder; j < d.length; j += Integer.BYTES) {
                    subRet += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, i * size + j) & (int) BitUtil.VH_NATIVE_INT.get(d, j));
                }
            }
            ret += subRet << i;
        }
        return ret;
    }

    @Benchmark
    public long ipbb_BytePanWideCountBench() {
        return ipByteBinBytePanWideCount(qBytes, dBytes);
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

    @Benchmark
    public long ipbb_BytePanUnrolled128Bench() {
        return ipByteBinBytePanUnrolled128(qBytes, dBytes);
    }

    public static long ipByteBinBytePanUnrolled128(byte[] q, byte[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int limit = BYTE_128_SPECIES.loopBound(d.length);
        int r = 0;
        for (; r < limit; r+=BYTE_128_SPECIES.length()) {
            ByteVector vq0 = ByteVector.fromArray(BYTE_128_SPECIES, q, r);
            ByteVector vq1 = ByteVector.fromArray(BYTE_128_SPECIES, q, r + d.length);
            ByteVector vq2 = ByteVector.fromArray(BYTE_128_SPECIES, q, r + 2 * d.length);
            ByteVector vq3 = ByteVector.fromArray(BYTE_128_SPECIES, q, r + 3 * d.length);
            ByteVector vd = ByteVector.fromArray(BYTE_128_SPECIES, d, r);
            ByteVector vres0 = vq0.and(vd);
            ByteVector vres1 = vq1.and(vd);
            ByteVector vres2 = vq2.and(vd);
            ByteVector vres3 = vq3.and(vd);
            vres0 = vres0.lanewise(VectorOperators.BIT_COUNT);
            subRet0 += Math.abs(vres0.reduceLanes(VectorOperators.ADD));
            vres1 = vres1.lanewise(VectorOperators.BIT_COUNT);
            subRet1 += Math.abs(vres1.reduceLanes(VectorOperators.ADD));
            vres2 = vres2.lanewise(VectorOperators.BIT_COUNT);
            subRet2 += Math.abs(vres2.reduceLanes(VectorOperators.ADD));
            vres3 = vres3.lanewise(VectorOperators.BIT_COUNT);
            subRet3 += Math.abs(vres3.reduceLanes(VectorOperators.ADD));
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
        final long expected = ipbb_longArraysScalarBench();

//        if (ipByteBinConstLongBench() != expected) {
//            throw new AssertionError("expected:" + expected + " != ipByteBinConstLongBench:" + ipByteBinConstLongBench());
//        }
//        if (ipByteBinConstUnrolledLongBench() != expected) {
//            throw new AssertionError("expected:" + expected + " != ipByteBinConstUnrolledLongBench:" + ipByteBinConstUnrolledLongBench());
//        }
//        if (ipByteBinConstUnrolledUnrolledLongBench() != expected) {
//            throw new AssertionError("expected:" + expected + " != ipByteBinConstUnrolledUnrolledLongBench:" + ipByteBinConstUnrolledUnrolledLongBench());
//        }
//        if (ipByteBinByteBench() != expected) {
//            throw new AssertionError("expected:" + expected + " != ipByteBinByteBench:" + ipByteBinByteBench());
//        }
//        if (ipbb_byteArraysPanamaBench() != expected) {
//            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaBench:" + ipbb_byteArraysPanamaBench());
//        }
        if (ipbb_byteArraysPanamaBench2() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaBench2:" + ipbb_byteArraysPanamaBench2());
        }
        if (ipbb_byteArraysPanamaStrideAsShortBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsShortBench:" + ipbb_byteArraysPanamaStrideAsShortBench());
        }
        if (ipbb_byteArraysPanamaStrideAsIntBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsIntBench:" + ipbb_byteArraysPanamaStrideAsIntBench());
        }
//        if (ipByteBinBytePanWags() != expected) {
//            throw new AssertionError("expected:" + expected + " != ipByteBinBytePanWags:" + ipByteBinBytePanWags());
//        }
        if (ipbb_LongPanamaUnrolledBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_LongPanamaUnrolledBench:" + ipbb_LongPanamaUnrolledBench());
        }
        if (ipbb_BytePanUnrolled128Bench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_BytePanUnrolledBench128:" + ipbb_BytePanUnrolled128Bench());
        }
        if (ipbb_BytePanWideCountBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_BytePanWideCountBench:" + ipbb_BytePanWideCountBench());
        }
        if (ipbb_byteArraysPanamaStrideAsLongUnrolledBench128() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsLongUnrolledBench128:" + ipbb_byteArraysPanamaStrideAsLongUnrolledBench128());
        }
        if (ipbb_byteArraysPanamaStrideAsLongUnrolled256Bench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsLongUnrolled256Bench:" + ipbb_byteArraysPanamaStrideAsLongUnrolled256Bench());
        }
        if (ipbb_byteArraysPanamaStrideAsLongUnrolled256OnlyBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsLongUnrolled256OnlyBench:" + ipbb_byteArraysPanamaStrideAsLongUnrolled256OnlyBench());
        }
        if (ipbb_byteArraysPanamaStrideAsShortUnrolled128Bench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsShortUnrolled128Bench:" + ipbb_byteArraysPanamaStrideAsShortUnrolled128Bench());
        }
    }
}
