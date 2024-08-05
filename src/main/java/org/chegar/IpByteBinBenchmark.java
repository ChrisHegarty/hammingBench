package org.chegar;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import org.apache.lucene.util.BitUtil;
import org.openjdk.jmh.annotations.*;

import java.lang.foreign.MemorySegment;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static jdk.incubator.vector.VectorOperators.B2S;
import static jdk.incubator.vector.VectorOperators.ZERO_EXTEND_B2S;

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

    static final VectorSpecies<Byte> BYTE_64_SPECIES = ByteVector.SPECIES_64;
    static final VectorSpecies<Byte> BYTE_128_SPECIES = ByteVector.SPECIES_128;
    static final VectorSpecies<Byte> BYTE_256_SPECIES = ByteVector.SPECIES_256;
    static final VectorSpecies<Byte> BYTE_512_SPECIES = ByteVector.SPECIES_512;
    static final VectorSpecies<Short> SHORT_128_SPECIES = ShortVector.SPECIES_128;
    static final VectorSpecies<Short> SHORT_256_SPECIES = ShortVector.SPECIES_256;

    public static final int B_QUERY = 4;

    @Param({ "384", "768", "1024" })
    int dims;

    long[] qLong;
    long[] dLong;
    byte[] qBytes;
    byte[] dBytes;
    int B; // == dims

    @Setup
    public void setup() {
        Random rand = new Random();
        B = (dims + 63) / 64 * 64;
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
         test.dims = 4096; //384;
         test.setup();
     }

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
    public long ipbb_byteArraysScalarStrideAsIntUnrollBench() {
        return ipbb_byteArraysScalarStrideUnrollAsInt(qBytes, dBytes);
    }

    static long ipbb_byteArraysScalarStrideUnrollAsInt(byte[] q, byte[] d) {
        int r = 0;
        int subRet0 = 0;
        int subRet1 = 0;
        int subRet2 = 0;
        int subRet3 = 0;
        for (final int upperBound = d.length & -Integer.BYTES; r < upperBound; r += Integer.BYTES) {
            final int dInt = (int) BitUtil.VH_NATIVE_INT.get(d, r);
            subRet0 +=
                    Integer.bitCount(
                            (int) BitUtil.VH_NATIVE_INT.get(q, r)
                                    & dInt);
            subRet1 +=
              Integer.bitCount(
                (int) BitUtil.VH_NATIVE_INT.get(q, r + d.length)
                  & dInt);
            subRet2 +=
              Integer.bitCount(
                (int) BitUtil.VH_NATIVE_INT.get(q, r + 2 * d.length)
                  & dInt);
            subRet3 +=
              Integer.bitCount(
                (int) BitUtil.VH_NATIVE_INT.get(q, r + 3 * d.length)
                  & dInt);
        }
        for (; r < d.length; r++) {
            subRet0 += Integer.bitCount((q[r] & d[r]) & 0xFF);
            subRet1 += Integer.bitCount((q[r + d.length] & d[r]) & 0xFF);
            subRet2 += Integer.bitCount((q[r + d.length * 2] & d[r]) & 0xFF);
            subRet3 += Integer.bitCount((q[r + d.length * 3] & d[r]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
    }

    @Benchmark
    public long ipbb_byteLongArraysScalarConstUnrolledBQueryBench() {
        return ipbb_byteLongArraysScalarConstUnrolledBQuery(qBytes, dBytes);
    }

    static long ipbb_byteLongArraysScalarConstUnrolledBQuery(byte[] q, byte[] d) {
        int r = 0;
        int subRet0 = 0;
        int subRet1 = 0;
        int subRet2 = 0;
        int subRet3 = 0;
        for (final int upperBound = d.length & -Long.BYTES; r < upperBound; r += Long.BYTES) {
            final long dLong = (long) BitUtil.VH_NATIVE_LONG.get(d, r);
            subRet0 +=
              Long.bitCount(
                (long) BitUtil.VH_NATIVE_LONG.get(q, r)
                  & dLong);
            subRet1 +=
              Long.bitCount(
                (long) BitUtil.VH_NATIVE_LONG.get(q, r + d.length)
                  & dLong);
            subRet2 +=
              Long.bitCount(
                (long) BitUtil.VH_NATIVE_LONG.get(q, r + 2 * d.length)
                  & dLong);
            subRet3 +=
              Long.bitCount(
                (long) BitUtil.VH_NATIVE_LONG.get(q, r + 3 * d.length)
                  & dLong);
        }
        for (; r < d.length; r++) {
            subRet0 += Integer.bitCount((q[r] & d[r]) & 0xFF);
            subRet1 += Integer.bitCount((q[r + d.length] & d[r]) & 0xFF);
            subRet2 += Integer.bitCount((q[r + d.length * 2] & d[r]) & 0xFF);
            subRet3 += Integer.bitCount((q[r + d.length * 3] & d[r]) & 0xFF);
        }
        return subRet0 + (subRet1 << 1) + (subRet2 << 2) + (subRet3 << 3);
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

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsLongUnrolledBench() {
        return ipbb_byteArraysPanamaStrideAsLongUnrolled(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsLongUnrolled(byte[] q, byte[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int limit = ByteVector.SPECIES_PREFERRED.loopBound(d.length);
        int r = 0;
        LongVector sum0 = LongVector.zero(LongVector.SPECIES_PREFERRED);
        LongVector sum1 = LongVector.zero(LongVector.SPECIES_PREFERRED);
        LongVector sum2 = LongVector.zero(LongVector.SPECIES_PREFERRED);
        LongVector sum3 = LongVector.zero(LongVector.SPECIES_PREFERRED);

        for (; r < limit; r += ByteVector.SPECIES_PREFERRED.length()) {
            LongVector vq0 = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, q, r).reinterpretAsLongs();
            LongVector vq1 = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, q, r + d.length).reinterpretAsLongs();
            LongVector vq2 = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, q, r + d.length * 2).reinterpretAsLongs();
            LongVector vq3 = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, q, r + d.length * 3).reinterpretAsLongs();
            LongVector vd = ByteVector.fromArray(ByteVector.SPECIES_PREFERRED, d, r).reinterpretAsLongs();
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
        // tail as ints
        for (; r < d.length-Long.BYTES; r+=Long.BYTES) {
            long dInt = (long) BitUtil.VH_NATIVE_LONG.get(d, r);
            subRet0 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r) & dInt);
            subRet1 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r + d.length) & dInt);
            subRet2 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r + 2 * d.length) & dInt);
            subRet3 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r + 3 * d.length) & dInt);
        }
        for (; r < d.length-Integer.BYTES; r+=Integer.BYTES) {
            int dInt = (int) BitUtil.VH_NATIVE_INT.get(d, r);
            subRet0 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r) & dInt);
            subRet1 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r + d.length) & dInt);
            subRet2 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r + 2 * d.length) & dInt);
            subRet3 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r + 3 * d.length) & dInt);
        }
        // tail as bytes
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

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsLongUnrolledBench256() {
        return ipbb_byteArraysPanamaStrideAsLongUnrolled256(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsLongUnrolled256(byte[] q, byte[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;
        int limit = ByteVector.SPECIES_256.loopBound(d.length);
        int r = 0;
        LongVector sum0 = LongVector.zero(LongVector.SPECIES_256);
        LongVector sum1 = LongVector.zero(LongVector.SPECIES_256);
        LongVector sum2 = LongVector.zero(LongVector.SPECIES_256);
        LongVector sum3 = LongVector.zero(LongVector.SPECIES_256);

        for (; r < limit; r += ByteVector.SPECIES_256.length()) {
            LongVector vq0 = ByteVector.fromArray(ByteVector.SPECIES_256, q, r).reinterpretAsLongs();
            LongVector vq1 = ByteVector.fromArray(ByteVector.SPECIES_256, q, r + d.length).reinterpretAsLongs();
            LongVector vq2 = ByteVector.fromArray(ByteVector.SPECIES_256, q, r + d.length * 2).reinterpretAsLongs();
            LongVector vq3 = ByteVector.fromArray(ByteVector.SPECIES_256, q, r + d.length * 3).reinterpretAsLongs();
            LongVector vd = ByteVector.fromArray(ByteVector.SPECIES_256, d, r).reinterpretAsLongs();
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
        // tail as ints
        for (; r < d.length-Long.BYTES; r+=Long.BYTES) {
            long dInt = (long) BitUtil.VH_NATIVE_LONG.get(d, r);
            subRet0 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r) & dInt);
            subRet1 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r + d.length) & dInt);
            subRet2 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r + 2 * d.length) & dInt);
            subRet3 += Long.bitCount((long) BitUtil.VH_NATIVE_LONG.get(q, r + 3 * d.length) & dInt);
        }
        for (; r < d.length-Integer.BYTES; r+=Integer.BYTES) {
            int dInt = (int) BitUtil.VH_NATIVE_INT.get(d, r);
            subRet0 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r) & dInt);
            subRet1 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r + d.length) & dInt);
            subRet2 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r + 2 * d.length) & dInt);
            subRet3 += Integer.bitCount((int) BitUtil.VH_NATIVE_INT.get(q, r + 3 * d.length) & dInt);
        }
        // tail as bytes
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


    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsShortUnrolledBench() {
        return ipbb_byteArraysPanamaStrideAsShortUnrolled128(qBytes, dBytes);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsShortUnrolledBench256() {
        return ipbb_byteArraysPanamaStrideAsShortUnrolled256(qBytes, dBytes);
    }

    @Benchmark
    public long ipbb_byteArraysPanamaStrideAsShortUnrolledBench512() {
        return ipbb_byteArraysPanamaStrideAsShortUnrolled512(qBytes, dBytes);
    }

    public static long ipbb_byteArraysPanamaStrideAsShortUnrolled128(byte[] q, byte[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;

        final int limit = BYTE_128_SPECIES.loopBound(d.length);
        // iterate in chunks of 256 bytes to ensure we don't overflow the accumulator (256bytes/16lanes=16itrs)
        for (int j =0; j < limit; j += BYTE_128_SPECIES.length() * 8) {
            ByteVector acc0 = ByteVector.zero(BYTE_128_SPECIES);
            ByteVector acc1 = ByteVector.zero(BYTE_128_SPECIES);
            ByteVector acc2 = ByteVector.zero(BYTE_128_SPECIES);
            ByteVector acc3 = ByteVector.zero(BYTE_128_SPECIES);
            int innerLimit = Math.min(limit - j, BYTE_128_SPECIES.length() * 8);
            for (int k = 0; k < innerLimit; k += BYTE_128_SPECIES.length()) {
                ByteVector vd = ByteVector.fromArray(BYTE_128_SPECIES, d, j + k);
                ByteVector vq0 = ByteVector.fromArray(BYTE_128_SPECIES, q, j + k);
                ByteVector vq1 = ByteVector.fromArray(BYTE_128_SPECIES, q, j + k + d.length);
                ByteVector vq2 = ByteVector.fromArray(BYTE_128_SPECIES, q, j + k + 2 * d.length);
                ByteVector vq3 = ByteVector.fromArray(BYTE_128_SPECIES, q, j + k + 3 * d.length);
                ByteVector vres0 = vq0.and(vd);
                ByteVector vres1 = vq1.and(vd);
                ByteVector vres2 = vq2.and(vd);
                ByteVector vres3 = vq3.and(vd);
                vres0 = vres0.lanewise(VectorOperators.BIT_COUNT);
                vres1 = vres1.lanewise(VectorOperators.BIT_COUNT);
                vres2 = vres2.lanewise(VectorOperators.BIT_COUNT);
                vres3 = vres3.lanewise(VectorOperators.BIT_COUNT);
                acc0 = acc0.add(vres0);
                acc1 = acc1.add(vres1);
                acc2 = acc2.add(vres2);
                acc3 = acc3.add(vres3);
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
        for (int r = limit; r < d.length; r++) {
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

    public static long ipbb_byteArraysPanamaStrideAsShortUnrolled256(byte[] q, byte[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;

        final int limit = BYTE_256_SPECIES.loopBound(d.length);
        // iterate in chunks of 256 bytes to ensure we don't overflow the accumulator (256bytes/16lanes=16itrs)
        for (int j =0; j < limit; j += BYTE_256_SPECIES.length() * 8) {
            ByteVector acc0 = ByteVector.zero(BYTE_256_SPECIES);
            ByteVector acc1 = ByteVector.zero(BYTE_256_SPECIES);
            ByteVector acc2 = ByteVector.zero(BYTE_256_SPECIES);
            ByteVector acc3 = ByteVector.zero(BYTE_256_SPECIES);
            int innerLimit = Math.min(limit - j, BYTE_256_SPECIES.length() * 8);
            for (int k = 0; k < innerLimit; k += BYTE_256_SPECIES.length()) {
                ByteVector vd = ByteVector.fromArray(BYTE_256_SPECIES, d, j + k);
                ByteVector vq0 = ByteVector.fromArray(BYTE_256_SPECIES, q, j + k);
                ByteVector vq1 = ByteVector.fromArray(BYTE_256_SPECIES, q, j + k + d.length);
                ByteVector vq2 = ByteVector.fromArray(BYTE_256_SPECIES, q, j + k + 2 * d.length);
                ByteVector vq3 = ByteVector.fromArray(BYTE_256_SPECIES, q, j + k + 3 * d.length);
                ByteVector vres0 = vq0.and(vd);
                ByteVector vres1 = vq1.and(vd);
                ByteVector vres2 = vq2.and(vd);
                ByteVector vres3 = vq3.and(vd);
                vres0 = vres0.lanewise(VectorOperators.BIT_COUNT);
                vres1 = vres1.lanewise(VectorOperators.BIT_COUNT);
                vres2 = vres2.lanewise(VectorOperators.BIT_COUNT);
                vres3 = vres3.lanewise(VectorOperators.BIT_COUNT);
                acc0 = acc0.add(vres0);
                acc1 = acc1.add(vres1);
                acc2 = acc2.add(vres2);
                acc3 = acc3.add(vres3);
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
        for (int r = limit; r < d.length; r++) {
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

    public static long ipbb_byteArraysPanamaStrideAsShortUnrolled512(byte[] q, byte[] d) {
        long ret = 0;
        long subRet0 = 0;
        long subRet1 = 0;
        long subRet2 = 0;
        long subRet3 = 0;

        final int limit = BYTE_512_SPECIES.loopBound(d.length);
        // iterate in chunks of 256 bytes to ensure we don't overflow the accumulator (256bytes/16lanes=16itrs)
        for (int j =0; j < limit; j += BYTE_512_SPECIES.length() * 8) {
            ByteVector acc0 = ByteVector.zero(BYTE_512_SPECIES);
            ByteVector acc1 = ByteVector.zero(BYTE_512_SPECIES);
            ByteVector acc2 = ByteVector.zero(BYTE_512_SPECIES);
            ByteVector acc3 = ByteVector.zero(BYTE_512_SPECIES);
            int innerLimit = Math.min(limit - j, BYTE_512_SPECIES.length() * 8);
            for (int k = 0; k < innerLimit; k += BYTE_512_SPECIES.length()) {
                ByteVector vd = ByteVector.fromArray(BYTE_512_SPECIES, d, j + k);
                ByteVector vq0 = ByteVector.fromArray(BYTE_512_SPECIES, q, j + k);
                ByteVector vq1 = ByteVector.fromArray(BYTE_512_SPECIES, q, j + k + d.length);
                ByteVector vq2 = ByteVector.fromArray(BYTE_512_SPECIES, q, j + k + 2 * d.length);
                ByteVector vq3 = ByteVector.fromArray(BYTE_512_SPECIES, q, j + k + 3 * d.length);
                ByteVector vres0 = vq0.and(vd);
                ByteVector vres1 = vq1.and(vd);
                ByteVector vres2 = vq2.and(vd);
                ByteVector vres3 = vq3.and(vd);
                vres0 = vres0.lanewise(VectorOperators.BIT_COUNT);
                vres1 = vres1.lanewise(VectorOperators.BIT_COUNT);
                vres2 = vres2.lanewise(VectorOperators.BIT_COUNT);
                vres3 = vres3.lanewise(VectorOperators.BIT_COUNT);
                acc0 = acc0.add(vres0);
                acc1 = acc1.add(vres1);
                acc2 = acc2.add(vres2);
                acc3 = acc3.add(vres3);
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
        for (int r = limit; r < d.length; r++) {
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

        if (ipbb_byteArraysPanamaBench2() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaBench2:" + ipbb_byteArraysPanamaBench2());
        }
        if (ipbb_byteArraysPanamaStrideAsShortBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsShortBench:" + ipbb_byteArraysPanamaStrideAsShortBench());
        }
        if (ipbb_byteArraysPanamaStrideAsIntBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsIntBench:" + ipbb_byteArraysPanamaStrideAsIntBench());
        }
        if (ipbb_BytePanUnrolled128Bench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_BytePanUnrolledBench128:" + ipbb_BytePanUnrolled128Bench());
        }
        if (ipbb_BytePanWideCountBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_BytePanWideCountBench:" + ipbb_BytePanWideCountBench());
        }
        if (ipbb_byteArraysPanamaStrideAsLongUnrolledBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsLongUnrolledBench:" + ipbb_byteArraysPanamaStrideAsLongUnrolledBench());
        }
        if (ipbb_byteArraysPanamaStrideAsShortUnrolledBench() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsShortUnrolledBench:" + ipbb_byteArraysPanamaStrideAsShortUnrolledBench());
        }
        if (ipbb_byteArraysPanamaStrideAsShortUnrolledBench256() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsShortUnrolledBench256:" + ipbb_byteArraysPanamaStrideAsShortUnrolledBench256());
        }
        if (ipbb_byteArraysPanamaStrideAsShortUnrolledBench512() != expected) {
            throw new AssertionError("expected:" + expected + " != ipbb_byteArraysPanamaStrideAsShortUnrolledBench512:" + ipbb_byteArraysPanamaStrideAsShortUnrolledBench512());
        }
    }
}
