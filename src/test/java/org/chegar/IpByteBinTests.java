package org.chegar;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.chegar.IpByteBinBenchmark.B_QUERY;
import static org.chegar.IpByteBinBenchmark.ipByteBinBytePanWags;
import static org.chegar.IpByteBinBenchmark.ipbb_byteArraysPanama;
import static org.chegar.IpByteBinBenchmark.ipbb_byteArraysPanama2;
import static org.chegar.IpByteBinBenchmark.ipbb_byteArraysPanamaStrideAsShort128;
import static org.chegar.IpByteBinBenchmark.ipbb_byteArraysScalarStrideAsInt;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class IpByteBinTests {

    Random random = new Random();

    static Stream<Integer> dimsProvider() {
        return Stream.of(8, 136, 384, 512, 768, 1024, 1536, 2048, 2056, 2066, 4096); //, 5000);
    }

    @ParameterizedTest
    @MethodSource("dimsProvider")
    public void testRandom(int dims) throws Exception {
        for (int i = 0; i < 100; i++) {
            final int baSize = dims / Byte.SIZE;
            byte[] d = randomByteArray(baSize);
            byte[] q = randomByteArray(d.length << 2);
            long expected = scalarIpByteBin(q, d);

            for (Method method : getIpByteBinMethods()) {
                System.out.println("Testing with:" + method);
                assertEquals(expected, method.invoke(null, q, d), "Testing with:" + method);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("dimsProvider")
    public void testMaxValue(int dims) throws Exception {
        final int baSize = dims / Byte.SIZE;
        byte[] d = new byte[baSize];
        byte[] q = new byte[d.length << 2];
        Arrays.fill(d, Byte.MAX_VALUE);
        Arrays.fill(q, Byte.MAX_VALUE);
        long expected = scalarIpByteBin(q, d);

        for (Method method : getIpByteBinMethods()) {
            System.out.println("Testing with:" + method);
            assertEquals(expected, method.invoke(null, q, d), "Testing with:" + method);
        }
    }

    @ParameterizedTest
    @MethodSource("dimsProvider")
    public void testMinValue(int dims) throws Exception {
        final int baSize = dims / Byte.SIZE;
        byte[] d = new byte[baSize];
        byte[] q = new byte[d.length << 2];
        Arrays.fill(d, Byte.MIN_VALUE);
        Arrays.fill(q, Byte.MIN_VALUE);
        long expected = scalarIpByteBin(q, d);

        for (Method method : getIpByteBinMethods()) {
            System.out.println("Testing with:" + method);
            assertEquals(expected, method.invoke(null, q, d), "Testing with:" + method);
        }
    }

    @ParameterizedTest
    @MethodSource("dimsProvider")
    public void testMinusOneValue(int dims) throws Exception {
        final int baSize = dims / Byte.SIZE;
        byte[] d = new byte[baSize];
        byte[] q = new byte[d.length << 2];
        Arrays.fill(d, (byte)-1);
        Arrays.fill(q, (byte)-1);
        long expected = scalarIpByteBin(q, d);

        for (Method method : getIpByteBinMethods()) {
            System.out.println("Testing with:" + method);
            assertEquals(expected, method.invoke(null, q, d), "Testing with:" + method);
        }
    }

    byte[] randomByteArray(int length) {
        byte[] ba = new byte[length];
        random.nextBytes(ba);
        return ba;
    }

    private static int scalarIpByteBin(byte[] q, byte[] d) {
        int res = 0;
        for (int i = 0; i < B_QUERY; i++) {
            final int qOffset = i * d.length;
            res += (popcount(q, qOffset, d, 0, d.length) << i);
        }
        return res;
    }

    private static int popcount(byte[] a, int aOffset, byte[] b, int bOffset, int length) {
        int res = 0;
        for (int j = 0; j < length; j++) {
            int value = (a[aOffset + j] & b[bOffset + j]) & 0xFF;
            for (int k = 0; k < Byte.SIZE; k++) {
                if ((value & (1 << k)) != 0) {
                    ++res;
                }
            }
        }
        return res;
    }

    static List<Method> getIpByteBinMethods() {
        return Arrays.stream(IpByteBinBenchmark.class.getDeclaredMethods())
                .filter(met -> Modifier.isStatic(met.getModifiers()))
                .filter(met -> met.getParameterCount() == 2)
                .filter(met -> (met.getParameters()[0].getType() == byte[].class) && (met.getParameters()[1].getType() == byte[].class))
                .filter(met -> !met.getName().equals("ipByteBinBytePanWags")) // skip for now
                .toList();
    }
}
