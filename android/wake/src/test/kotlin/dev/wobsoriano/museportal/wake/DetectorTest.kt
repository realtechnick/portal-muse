// Ported from github.com/zserge/microwakeword (microwakeword_test.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.
package dev.wobsoriano.museportal.wake

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Ignore
import org.junit.Test

class DetectorTest {
  @Test fun detectHeyJarvis() = testDetection("hey_jarvis.tflite", "hey_jarvis.wav", 0.85, atSample = 53920, avg = "0.8965")

  @Test fun detectAlexa() = testDetection("alexa.tflite", "alexa.wav", 0.85, atSample = 48160, avg = "0.8612")

  @Ignore("okay_nabu.tflite model not working with synthesized sounds yet")
  @Test
  fun detectOkayNabu() = testDetection("okay_nabu.tflite", "okay_nabu.wav", 0.85, atSample = -1, avg = "")

  @Test fun noFalsePositive() = testNoDetection("hey_jarvis.tflite", "nowake.wav")

  // Each model should not trigger on the other's wake word.
  @Test
  fun noFalsePositiveCrossModel() {
    testNoDetection("hey_jarvis.tflite", "alexa.wav")
    testNoDetection("alexa.tflite", "hey_jarvis.wav")
  }

  /** Checks the detection lands where the Go test logs it, at the sliding average it logs. */
  private fun testDetection(model: String, wav: String, cutoff: Double, atSample: Int, avg: String) {
    val det = Detector.create(Config(testdata(model), probabilityCutoff = cutoff, slidingWindowSize = 5, featuresStepMs = 10))
    val samples = loadTestWav(testdata(wav))
    var i = 0
    while (i + CHUNK <= samples.size) {
      if (det.processAudio(samples, i, CHUNK)) {
        println("detected at sample %d (%.2fs), avg=%.4f".format(i, i / 16000.0, det.slidingAverage()))
        assertEquals(atSample, i)
        assertEquals(avg, "%.4f".format(det.slidingAverage()))
        return
      }
      i += CHUNK
    }
    fail("wake word not detected")
  }

  private fun testNoDetection(model: String, wav: String) {
    val det = Detector.create(Config(testdata(model), probabilityCutoff = 0.85, slidingWindowSize = 5, featuresStepMs = 10))
    val samples = loadTestWav(testdata(wav))
    var i = 0
    while (i + CHUNK <= samples.size) {
      if (det.processAudio(samples, i, CHUNK)) fail("false positive at sample %d (%.2fs)".format(i, i / 16000.0))
      i += CHUNK
    }
  }

  private companion object {
    const val CHUNK = 160
  }
}
