package com.scariflabs.horus.terminal

import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Automated stress and fuzz suite for the libghostty-vt backend.
 *
 * Verifies memory lifetimes, lack of native memory leaks via custom
 * tracking [GhosttyAllocator], thread-safety under concurrent load,
 * robustness against escape sequence bombs / fuzz inputs, and graceful
 * failure handling under native memory exhaustion.
 */
class LibGhosttyStressTest {

  @Before
  fun setUp() {
    TerminalPtyJni.nativeResetAllocatorStats()
    TerminalPtyJni.nativeSetAllocationFailureCap(0L)
  }

  @Test
  fun openCloseCyclesReportZeroNativeLeaks() {
    val initialBytes = TerminalPtyJni.nativeAllocatedBytes()

    // 1,000 open/close cycles
    for (i in 0 until 1_000) {
      val engine = NativeTerminalEngine(
        sessionId = "leak-cycle-$i",
        rows = 24,
        columns = 80,
        backend = TerminalEngineBackend.LIBGHOSTTY,
      ) { _, _ -> }

      assertTrue(engine.enqueue(1L, "Hello, world! Cycle $i\r\n".toByteArray(StandardCharsets.UTF_8)))
      val frame = engine.currentFrame()
      assertNotNull(frame)
      engine.close()
    }

    val leakedBytes = TerminalPtyJni.nativeAllocatedBytes() - initialBytes
    val leakedBlocks = TerminalPtyJni.nativeAllocatedBlocks()
    assertEquals("Native memory leaked after 1,000 open/close cycles", 0L, leakedBytes)
    assertEquals("Native blocks leaked after 1,000 open/close cycles", 0L, leakedBlocks)
  }

  @Test
  fun concurrentSessionsStreamingAndResizingReportZeroLeaks() {
    val initialBytes = TerminalPtyJni.nativeAllocatedBytes()
    val sessionCount = 4
    val durationMs = 2_000L
    val stopSignal = AtomicBoolean(false)
    val latch = CountDownLatch(sessionCount)
    val errors = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

    val threads = (0 until sessionCount).map { sessionIndex ->
      Thread {
        val engine = NativeTerminalEngine(
          sessionId = "stress-session-$sessionIndex",
          rows = 24,
          columns = 80,
          backend = TerminalEngineBackend.LIBGHOSTTY,
        ) { _, _ -> }

        try {
          var seq = 0L
          val startTime = System.currentTimeMillis()
          var step = 0
          while (!stopSignal.get() && System.currentTimeMillis() - startTime < durationMs) {
            step++
            // Stream output
            val chunk = "Session $sessionIndex step $step: https://example.com/test/$step \u001b[31mcolor\u001b[0m\r\n"
            engine.enqueue(++seq, chunk.toByteArray(StandardCharsets.UTF_8))

            // Resize periodically
            if (step % 10 == 0) {
              val cols = 40 + (step % 60)
              val rows = 15 + (step % 20)
              engine.resize(rows, cols)
            }

            // Extract formatted text and detect links
            val frame = engine.currentFrame()
            val textSnapshot = NativeTerminalText.from(frame.lines)
            assertNotNull(textSnapshot.text)

            Thread.sleep(5L)
          }
        } catch (t: Throwable) {
          errors.add(t)
        } finally {
          engine.close()
          latch.countDown()
        }
      }.apply {
        isDaemon = true
        start()
      }
    }

    assertTrue("Concurrent stress sessions did not finish within timeout", latch.await(10, TimeUnit.SECONDS))
    if (errors.isNotEmpty()) {
      throw AssertionError("Concurrent stress encountered errors: ${errors[0].message}", errors[0])
    }

    // Wait a brief moment for worker threads to fully unbind
    Thread.sleep(50L)

    val leakedBytes = TerminalPtyJni.nativeAllocatedBytes() - initialBytes
    val leakedBlocks = TerminalPtyJni.nativeAllocatedBlocks()
    assertEquals("Native memory leaked after concurrent streaming/resizing", 0L, leakedBytes)
    assertEquals("Native blocks leaked after concurrent streaming/resizing", 0L, leakedBlocks)
  }

  @Test
  fun bomb1_overlongUtf8() {
    val engine = NativeTerminalEngine("bomb1", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, byteArrayOf(0xC0.toByte(), 0xAF.toByte())))
      assertNotNull(engine.currentFrame())
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb2_splitSurrogates() {
    val engine = NativeTerminalEngine("bomb2", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())))
      assertTrue(engine.enqueue(2L, byteArrayOf(0x80.toByte(), 0xBF.toByte(), 0xFF.toByte())))
      assertNotNull(engine.currentFrame())
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb3_rep() {
    val engine = NativeTerminalEngine("bomb3", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "A\u001b[999999999b".toByteArray(StandardCharsets.UTF_8)))
      assertNotNull(engine.currentFrame())
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb4_giantCursor() {
    val engine = NativeTerminalEngine("bomb4", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[99999999;99999999H".toByteArray(StandardCharsets.UTF_8)))
      val frame = engine.currentFrame()
      assertTrue(frame.cursorRow < frame.rows)
      assertTrue(frame.cursorColumn < frame.columns)
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb5_decaln() {
    val engine = NativeTerminalEngine("bomb5", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      for (i in 0 until 100) {
        assertTrue(engine.enqueue(i + 1L, "\u001b#8".toByteArray(StandardCharsets.UTF_8)))
      }
      val deadline = System.currentTimeMillis() + 2_000L
      while (System.currentTimeMillis() < deadline && engine.currentFrame().lines[0].text[0] != "E") {
        Thread.sleep(5L)
      }
      assertEquals("E", engine.currentFrame().lines[0].text[0])
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb6_degenerateScroll() {
    val engine = NativeTerminalEngine("bomb6", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      assertTrue(engine.enqueue(1L, "\u001b[0;0r".toByteArray(StandardCharsets.UTF_8)))
      assertTrue(engine.enqueue(2L, "\u001b[1;1r".toByteArray(StandardCharsets.UTF_8)))
      assertTrue(engine.enqueue(3L, "\u001b[20;5r".toByteArray(StandardCharsets.UTF_8)))
      assertNotNull(engine.currentFrame())
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb7_hugeCsi() {
    val engine = NativeTerminalEngine("bomb7", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      val hugeCsi = "\u001b[" + "1;".repeat(150) + "m"
      assertTrue(engine.enqueue(1L, hugeCsi.toByteArray(StandardCharsets.UTF_8)))
      assertNotNull(engine.currentFrame())
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb8_altScreenToggle() {
    val engine = NativeTerminalEngine("bomb8", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      val altToggle = "\u001b[?1049h\u001b[?1049l".repeat(1_000)
      for (i in 0 until 10) {
        assertTrue(engine.enqueue(i + 1L, altToggle.toByteArray(StandardCharsets.UTF_8)))
      }
      assertNotNull(engine.currentFrame())
    } finally {
      engine.close()
    }
  }

  @Test
  fun bomb9_outputGap() {
    val engine = NativeTerminalEngine("bomb9", 24, 80, backend = TerminalEngineBackend.LIBGHOSTTY) { _, _ -> }
    try {
      for (i in 0 until 50) {
        engine.recoverFromOutputGap()
      }
      assertNotNull(engine.currentFrame())
    } finally {
      engine.close()
    }
  }

  @Test
  fun largeOscPayloadTruncationStress() {
    val engine = NativeTerminalEngine(
      sessionId = "osc-large-test",
      rows = 24,
      columns = 80,
      backend = TerminalEngineBackend.LIBGHOSTTY,
    ) { _, _ -> }

    try {
      var seq = 0L
      // 1MB OSC title payload: \u001b]0; [1MB of 'X'] \u0007
      val chunk = "X".repeat(64 * 1024)
      assertTrue(engine.enqueue(++seq, "\u001b]0;".toByteArray(StandardCharsets.UTF_8)))
      for (i in 0 until 16) {
        assertTrue(engine.enqueue(++seq, chunk.toByteArray(StandardCharsets.UTF_8)))
      }
      assertTrue(engine.enqueue(++seq, "\u0007Done\r\n".toByteArray(StandardCharsets.UTF_8)))

      val deadline = System.currentTimeMillis() + 3_000L
      while (System.currentTimeMillis() < deadline && !engine.currentFrame().lines[0].text.any { it.isNotEmpty() }) {
        Thread.sleep(10L)
      }
      val frame = engine.currentFrame()
      assertNotNull(frame)
    } finally {
      engine.close()
    }
  }

  @Test
  fun allocationFailureCapGracefulHandling() {
    // When the allocation cap is exceeded, custom tracking allocator returns NULL.
    // Ensure nativeEngineCreate fails cleanly (returns 0L) instead of crashing the process.
    TerminalPtyJni.nativeSetAllocationFailureCap(1024L) // very small cap, terminal_new requires more

    val handle = TerminalPtyJni.nativeEngineCreate(80, 24, -1)
    assertEquals("nativeEngineCreate should return 0 on allocation failure without crashing", 0L, handle)

    // Reset cap and verify creation succeeds
    TerminalPtyJni.nativeSetAllocationFailureCap(0L)
    val validHandle = TerminalPtyJni.nativeEngineCreate(80, 24, -1)
    assertNotEquals("nativeEngineCreate should succeed after resetting failure cap", 0L, validHandle)
    TerminalPtyJni.nativeEngineDestroy(validHandle)
  }
}
