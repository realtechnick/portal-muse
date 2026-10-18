// Ported from github.com/zserge/microwakeword (audiofrontend/frontend_test.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.
package dev.wobsoriano.museportal.wake.audiofrontend

import dev.wobsoriano.museportal.wake.loadFeatures
import dev.wobsoriano.museportal.wake.loadTestWav
import dev.wobsoriano.museportal.wake.testdata
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendTest {
  @Test
  fun frontendVsGolden() {
    val samples = loadTestWav(testdata("hey_jarvis.wav"))
    val golden = loadFeatures(testdata("hey_jarvis_features.bin"))

    val fe = Frontend(Config.default(10)) // 10ms step, matching the golden data
    val frames = fe.processAllInt8(samples)
    println("Kotlin frames: ${frames.size}, Golden frames: ${golden.size}")

    val minFrames = minOf(golden.size, frames.size)
    assertTrue("No frames produced", minFrames > 0)

    // Per-frame mean absolute error
    var totalMAE = 0.0
    var maxDiff = 0
    for (i in 0 until minFrames) {
      var frameMAE = 0.0
      for (j in 0 until 40) {
        val diff = abs(frames[i][j] - golden[i][j])
        maxDiff = maxOf(maxDiff, diff)
        frameMAE += diff.toDouble()
      }
      totalMAE += frameMAE / 40.0
    }
    totalMAE /= minFrames.toDouble()
    println("Mean absolute error: %.2f, Max diff: %d".format(totalMAE, maxDiff))

    // The frontend is a floating-point approximation of the fixed-point C version, so the golden
    // features are not matched exactly; the downstream model still detects correctly.
    assertTrue("MAE too high: %.2f (want < 25)".format(totalMAE), totalMAE < 25)

    // What the Go test logs for the same audio.
    assertEquals(615, frames.size)
    assertEquals("16.06", "%.2f".format(totalMAE))
    assertEquals(171, maxDiff)
  }
}
