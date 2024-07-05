


Contrary to intuition `Integer::bitcount` performs better that `Long::bitcount`
on the same underlying data, on AArch64.

## Summary

`Long::bitcount` is not vectorized, while `Integer::bitcount` is. This is a
counterintuitive and effectively an implementation bug in the Hotspot C2 
compiler for AArch64.

Linux x64 shows no such issue - performance of int and long bit counts is
identical.

### Background

We ran into this anomaly when implementing hamming distance between two bit
vectors. The values for each bit vector are stored in a `byte[]`. We then chose
to view the vector data as `long`, via `MethodHandles::byteArrayViewVarHandle(long[].class, ..)`,
in order to perform an xor between the vectors and count the set bits.
Surprisingly, implementing the same but using `MethodHandles::byteArrayViewVarHandle(int[].class, ..)`
performs significantly better on ARM.

There may be more going on between our implementation of hamming distance and
plain use of `bitCount`, but it's likely that this is the root cause for the
performance differential that we see. Additionally, it is useful to analyse
the plain use of `bitCount` independently (of our hamming distance impl).

### Benchmark

The benchmark, `BitCountBenchmark`, measures _throughput_ in seconds, so bigger
numbers are better. 

#### Linux - x64 Skylake
```
davekim$ /home/chegar/binaries/jdk-22.0.1/bin/java -jar target/benchmarks.jar 
...
Benchmark                       (size)  (times)   Mode  Cnt   Score   Error  Units
BitCountBenchmark.bitCountInt     1024   100000  thrpt    5  47.019 ± 0.157  ops/s
BitCountBenchmark.bitCountLong    1024   100000  thrpt    5  47.511 ± 0.263  ops/s
```

#### Mac M2 - AArch64
```
/Users/chegar/binaries/jdk-22.0.1.jdk/Contents/Home/bin/java -jar target/benchmarks.jar 
...
Benchmark                       (size)  (times)   Mode  Cnt   Score   Error  Units
BitCountBenchmark.bitCountInt     1024   100000  thrpt    5  64.153 ± 5.019  ops/s
BitCountBenchmark.bitCountLong    1024   100000  thrpt    5  47.843 ± 0.847  ops/s
```

I disabled loop unrolling during investigation to make it more straightforward
to look at the disassembly, which helped with identifying the underlying issue.

Disabling loop unrolling, shows more intuitive results - the long variant is
~2x faster than the int variant.
```
$ /Users/chegar/binaries/jdk-22.0.1.jdk/Contents/Home/bin/java -jar target/benchmarks.jar -jvmArgs="-XX:LoopUnrollLimit=0"
...
Benchmark                       (size)  (times)   Mode  Cnt   Score   Error  Units
BitCountBenchmark.bitCountInt     1024   100000  thrpt    5  14.849 ± 0.168  ops/s
BitCountBenchmark.bitCountLong    1024   100000  thrpt    5  28.814 ± 3.616  ops/s
```

#### Disassembly

Just the unrolled loop body. (Note: JDK 21u was used when gathering the disassembly)

`Long::bitCount` - unrolling appears to "just" stamp out the non-unrolled loop
body 8 times, nothing more. So, one unrolled iteration operates on 8 64-bit long
values at a time, which covers 512 bits per loop iteration.

```
;; B9: #	out( B9 B10 ) <- in( B8 B9 ) Loop( B9-B9 inner main of N60 strip mined) Freq: 697183
  0x000000010ec5b170:   add	x11, x10, w17, sxtw #3      ;*laload {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::longBitCount@12 (line 91)
  0x000000010ec5b174:   ldr	d16, [x11, #16]
  0x000000010ec5b178:   cnt	v16.8b, v16.8b
  0x000000010ec5b17c:   addv	b16, v16.8b
  0x000000010ec5b180:   mov	x12, v16.d[0]
  0x000000010ec5b184:   add	w13, w0, w12
  0x000000010ec5b188:   ldr	d16, [x11, #24]
  0x000000010ec5b18c:   cnt	v16.8b, v16.8b
  0x000000010ec5b190:   addv	b16, v16.8b
  0x000000010ec5b194:   mov	x12, v16.d[0]
  0x000000010ec5b198:   add	w13, w13, w12
  0x000000010ec5b19c:   ldr	d16, [x11, #32]
  0x000000010ec5b1a0:   cnt	v16.8b, v16.8b
  0x000000010ec5b1a4:   addv	b16, v16.8b
  0x000000010ec5b1a8:   mov	x12, v16.d[0]
  0x000000010ec5b1ac:   add	w13, w13, w12
  0x000000010ec5b1b0:   ldr	d16, [x11, #40]
  0x000000010ec5b1b4:   cnt	v16.8b, v16.8b
  0x000000010ec5b1b8:   addv	b16, v16.8b
  0x000000010ec5b1bc:   mov	x12, v16.d[0]
  0x000000010ec5b1c0:   add	w13, w13, w12
  0x000000010ec5b1c4:   ldr	d16, [x11, #48]
  0x000000010ec5b1c8:   cnt	v16.8b, v16.8b
  0x000000010ec5b1cc:   addv	b16, v16.8b
  0x000000010ec5b1d0:   mov	x12, v16.d[0]
  0x000000010ec5b1d4:   add	w13, w13, w12
  0x000000010ec5b1d8:   ldr	d16, [x11, #56]
  0x000000010ec5b1dc:   cnt	v16.8b, v16.8b
  0x000000010ec5b1e0:   addv	b16, v16.8b
  0x000000010ec5b1e4:   mov	x12, v16.d[0]
  0x000000010ec5b1e8:   ldr	d16, [x11, #64]
  0x000000010ec5b1ec:   cnt	v16.8b, v16.8b
  0x000000010ec5b1f0:   addv	b16, v16.8b
  0x000000010ec5b1f4:   mov	x15, v16.d[0]
  0x000000010ec5b1f8:   add	w13, w13, w12
  0x000000010ec5b1fc:   ldr	d16, [x11, #72]
  0x000000010ec5b200:   cnt	v16.8b, v16.8b
  0x000000010ec5b204:   addv	b16, v16.8b
  0x000000010ec5b208:   mov	x11, v16.d[0]
  0x000000010ec5b20c:   add	w13, w13, w15
  0x000000010ec5b210:   add	w17, w17, #0x8              ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::longBitCount@25 (line 90)
  0x000000010ec5b214:   add	w0, w13, w11                ;*iadd {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::longBitCount@23 (line 93)
  0x000000010ec5b218:   cmp	w17, w3
  0x000000010ec5b21c:   b.lt	0x000000010ec5b170  // b.tstop;*if_icmpge {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::longBitCount@7 (line 89)
```

`Integer::bitCount` - unrolling vectorizes. So, one unrolled iteration operates
on 32 32-bit int values at a time - effectively loading 4 32-bit int values into
a single 128-bit register. And it does this 8 times, which covers 1024 bits per
loop iteration.

```
 ;; B12: #	out( B11 B13 ) <- in( B15 B11 ) Loop( B12-B11 inner main of N92 strip mined) Freq: 9.80518e+06
  0x000000010c7043b4:   add	x11, x10, w14, sxtw #2      ;*iaload {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::intBitCount@12 (line 103)
  0x000000010c7043b8:   ldr	q18, [x11, #16]
  0x000000010c7043bc:   ldr	q19, [x11, #32]
  0x000000010c7043c0:   cnt	v18.16b, v18.16b
  0x000000010c7043c4:   uaddlp	v18.8h, v18.16b
  0x000000010c7043c8:   uaddlp	v18.4s, v18.8h
  0x000000010c7043cc:   ldr	q20, [x11, #48]
  0x000000010c7043d0:   add	v17.4s, v17.4s, v18.4s
  0x000000010c7043d4:   cnt	v18.16b, v19.16b
  0x000000010c7043d8:   uaddlp	v18.8h, v18.16b
  0x000000010c7043dc:   uaddlp	v18.4s, v18.8h
  0x000000010c7043e0:   ldr	q19, [x11, #64]
  0x000000010c7043e4:   add	v17.4s, v17.4s, v18.4s
  0x000000010c7043e8:   cnt	v18.16b, v20.16b
  0x000000010c7043ec:   uaddlp	v18.8h, v18.16b
  0x000000010c7043f0:   uaddlp	v18.4s, v18.8h
  0x000000010c7043f4:   ldr	q20, [x11, #80]
  0x000000010c7043f8:   add	v17.4s, v17.4s, v18.4s
  0x000000010c7043fc:   cnt	v18.16b, v19.16b
  0x000000010c704400:   uaddlp	v18.8h, v18.16b
  0x000000010c704404:   uaddlp	v18.4s, v18.8h
  0x000000010c704408:   ldr	q19, [x11, #96]
  0x000000010c70440c:   add	v17.4s, v17.4s, v18.4s
  0x000000010c704410:   cnt	v18.16b, v20.16b
  0x000000010c704414:   uaddlp	v18.8h, v18.16b
  0x000000010c704418:   uaddlp	v18.4s, v18.8h
  0x000000010c70441c:   ldr	q20, [x11, #112]
  0x000000010c704420:   add	v17.4s, v17.4s, v18.4s
  0x000000010c704424:   cnt	v18.16b, v19.16b
  0x000000010c704428:   uaddlp	v18.8h, v18.16b
  0x000000010c70442c:   uaddlp	v18.4s, v18.8h
  0x000000010c704430:   ldr	q19, [x11, #128]
  0x000000010c704434:   add	v17.4s, v17.4s, v18.4s
  0x000000010c704438:   cnt	v18.16b, v20.16b
  0x000000010c70443c:   uaddlp	v18.8h, v18.16b
  0x000000010c704440:   uaddlp	v18.4s, v18.8h
  0x000000010c704444:   add	v17.4s, v17.4s, v18.4s
  0x000000010c704448:   cnt	v18.16b, v19.16b
  0x000000010c70444c:   uaddlp	v18.8h, v18.16b
  0x000000010c704450:   uaddlp	v18.4s, v18.8h
  0x000000010c704454:   add	v17.4s, v17.4s, v18.4s      ;*iadd {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::intBitCount@23 (line 105)
  0x000000010c704458:   add	w12, w14, #0x20             ;*iinc {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::intBitCount@25 (line 102)
  0x000000010c70445c:   cmp	w12, w15
  0x000000010c704460:   b.lt	0x000000010c7043b0  // b.tstop;*if_icmpge {reexecute=0 rethrow=0 return_oop=0}
                                                            ; - org.chegar.BitCountBenchmark::intBitCount@7 (line 101)
```
