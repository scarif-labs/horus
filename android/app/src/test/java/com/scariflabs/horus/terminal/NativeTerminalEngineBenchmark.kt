package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Parser throughput microbenchmark, skipped unless HORUS_BENCH=1:
 *   HORUS_BENCH=1 ./gradlew :app:testDebugUnitTest --rerun \
 *     --tests '*.NativeTerminalEngineBenchmark'
 *
 * Feeds a few MB of TUI-style redraws (synchronized-output frames full of CUP,
 * EL and truecolour SGR) through [NativeTerminalEngine.enqueue] in PTY-sized
 * chunks, ending each pass with a DSR 5 whose reply marks the parser done.
 * Allocation and CPU time are the parser thread's own counters over the pass;
 * CPU throughput is the steadier number on a loaded machine.
 * Results go to stdout (in the JUnit XML report) and build/horus-bench.txt.
 */
class NativeTerminalEngineBenchmark {
  @Test
  fun parseThroughput() {
    assumeTrue("set HORUS_BENCH=1 to run", System.getenv("HORUS_BENCH") == "1")
    val stream = redrawStream(targetBytes = 4 shl 20)
    val chunks = (stream.indices step CHUNK_BYTES).map { stream.copyOfRange(it, minOf(it + CHUNK_BYTES, stream.size)) }
    val megabytes = stream.size / (1024.0 * 1024.0)
    val results = (0 until WARMUP_PASSES + MEASURED_PASSES).map { runPass(chunks) }.drop(WARMUP_PASSES)
    val throughputs = results.map { megabytes / (it.wallNanos / 1e9) }.sorted()
    val cpuThroughputs = results.map { megabytes / (it.cpuNanos / 1e9) }.sorted()
    val allocations = results.map { it.allocatedBytes / megabytes / (1024.0 * 1024.0) }.sorted()
    val report = buildString {
      append("NativeTerminalEngine parse benchmark: %.2f MB in %d-byte chunks, %d warm-up + %d measured passes\n"
        .format(megabytes, CHUNK_BYTES, WARMUP_PASSES, MEASURED_PASSES))
      append("  wall throughput MB/s: median %.1f, min %.1f, max %.1f\n"
        .format(throughputs[throughputs.size / 2], throughputs.first(), throughputs.last()))
      append("  parser-thread CPU throughput MB/s: median %.1f, min %.1f, max %.1f\n"
        .format(cpuThroughputs[cpuThroughputs.size / 2], cpuThroughputs.first(), cpuThroughputs.last()))
      append("  parser-thread allocation MB per input MB: median %.2f\n".format(allocations[allocations.size / 2]))
    }
    print(report)
    runCatching { java.io.File("build/horus-bench.txt").appendText(report) }
  }

  private class Pass(val wallNanos: Long, val cpuNanos: Long, val allocatedBytes: Long)

  /** Runs one pass on a fresh engine. */
  private fun runPass(chunks: List<ByteArray>): Pass {
    val parserThreadId = AtomicLong(-1)
    val sentinel = AtomicReference(CountDownLatch(1))
    val engine = NativeTerminalEngine("bench", ROWS, COLUMNS) { _, bytes ->
      if (String(bytes, StandardCharsets.UTF_8) == "\u001b[0n") {
        parserThreadId.set(Thread.currentThread().id)
        sentinel.get().countDown()
      }
    }
    try {
      // The first sentinel only identifies the parser thread.
      var seq = 1L
      check(engine.enqueue(seq++, DSR))
      check(sentinel.get().await(5, TimeUnit.SECONDS))
      sentinel.set(CountDownLatch(1))
      val allocatedBefore = threadCounter("getThreadAllocatedBytes", parserThreadId.get())
      val cpuBefore = threadCounter("getThreadCpuTime", parserThreadId.get())
      val started = System.nanoTime()
      for (chunk in chunks + listOf(DSR)) {
        while (!engine.enqueue(seq, chunk)) Thread.yield()
        seq += 1
      }
      check(sentinel.get().await(60, TimeUnit.SECONDS)) { "parser never answered the sentinel" }
      val elapsed = System.nanoTime() - started
      return Pass(
        wallNanos = elapsed,
        cpuNanos = threadCounter("getThreadCpuTime", parserThreadId.get()) - cpuBefore,
        allocatedBytes = threadCounter("getThreadAllocatedBytes", parserThreadId.get()) - allocatedBefore,
      )
    } finally {
      engine.close()
    }
  }

  /**
   * A per-thread ThreadMXBean counter (getThreadCpuTime,
   * getThreadAllocatedBytes). Reflective because unit tests compile against
   * android.jar, which has no java.lang.management.
   */
  private fun threadCounter(name: String, threadId: Long): Long {
    val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    return Class.forName("com.sun.management.ThreadMXBean")
      .getMethod(name, Long::class.javaPrimitiveType)
      .invoke(threads, threadId) as Long
  }

  /**
   * Full-screen redraws as Claude Code / Codex emit them: each frame is a
   * DEC 2026 synchronized update that homes each row, clears it, and paints
   * styled spans.
   */
  private fun redrawStream(targetBytes: Int): ByteArray {
    val random = Random(2026)
    val words = listOf("const", "value", "=", "await", "fetch(url);", "// TODO", "return", "{", "}", "Running", "tests…")
    val out = StringBuilder(targetBytes + 4096)
    while (out.length < targetBytes) {
      out.append("\u001b[?2026h\u001b[?25l")
      for (row in 1..ROWS) {
        out.append("\u001b[").append(row).append(";1H\u001b[K")
        repeat(4 + random.nextInt(6)) {
          when (random.nextInt(4)) {
            0 -> out.append("\u001b[38;2;").append(random.nextInt(256)).append(';')
              .append(random.nextInt(256)).append(';').append(random.nextInt(256)).append('m')
            1 -> out.append("\u001b[1;4m")
            2 -> out.append("\u001b[48;5;").append(random.nextInt(256)).append('m')
            else -> out.append("\u001b[0m")
          }
          out.append(words[random.nextInt(words.size)]).append(' ')
        }
        out.append("\u001b[0m")
      }
      out.append("\u001b[12;40H\u001b[?25h\u001b[?2026l")
    }
    return out.toString().toByteArray(StandardCharsets.UTF_8)
  }

  private companion object {
    const val ROWS = 50
    const val COLUMNS = 160
    const val CHUNK_BYTES = 16 * 1024
    const val WARMUP_PASSES = 5
    const val MEASURED_PASSES = 10
    val DSR = "\u001b[5n".toByteArray(StandardCharsets.UTF_8)
  }
}
