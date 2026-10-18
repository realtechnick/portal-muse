// Ported from github.com/zserge/microwakeword (interpreter/interpreter_test.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.
package dev.wobsoriano.museportal.wake.interpreter

import dev.wobsoriano.museportal.wake.loadFeatures
import dev.wobsoriano.museportal.wake.model.Model
import dev.wobsoriano.museportal.wake.testdata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpreterTest {
  @Test
  fun inferenceHeyJarvis() {
    val interp = Interpreter(Model.loadFile(testdata("hey_jarvis.tflite")))
    val frames = loadFeatures(testdata("hey_jarvis_features.bin"))
    println("Loaded ${frames.size} frames of ${frames[0].size} features")

    // The model expects 3 frames at a time (stride=3).
    val stride = 3
    val featureSize = 40
    val inputBuf = ByteArray(stride * featureSize)
    var maxProb = 0f
    var detectedAt = -1
    var i = 0
    while (i + stride <= frames.size) {
      for (s in 0 until stride) frames[i + s].copyInto(inputBuf, s * featureSize)
      interp.setInput(inputBuf)
      interp.invoke()
      val prob = interp.outputFloat32()
      if (prob.isNotEmpty() && prob[0] > maxProb) {
        maxProb = prob[0]
        detectedAt = i
      }
      i += stride
    }
    println("Max prob=%.4f at frame %d".format(maxProb, detectedAt))
    assertTrue("Expected high probability detection, got max %.4f".format(maxProb), maxProb >= 0.5f)
    // What the Go test logs for the same run.
    assertEquals(0.99609375f, maxProb)
    assertEquals(315, detectedAt)
  }

  @Test
  fun inferenceOkayNabu() {
    val m = Model.loadFile(testdata("okay_nabu.tflite"))
    val sg = m.subgraphs[0]
    val inT = sg.tensors[sg.inputs[0]]
    val interp = Interpreter(m)

    // A single inference with zeros, then a few more to exercise resource variables.
    val input = ByteArray(inT.shape[1] * inT.shape[2])
    interp.setInput(input)
    interp.invoke()
    assertArrayEquals("First inference output", intArrayOf(5), interp.outputUint8())
    repeat(5) {
      interp.setInput(input)
      interp.invoke()
    }
    assertArrayEquals("After 5 iterations output", intArrayOf(0), interp.outputUint8())
  }
}
