// Ported from github.com/zserge/microwakeword (model/model_test.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.
package dev.wobsoriano.museportal.wake.model

import dev.wobsoriano.museportal.wake.testdata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
  @Test
  fun parseHeyJarvis() {
    val m = Model.loadFile(testdata("hey_jarvis.tflite"))
    assertEquals("Version", 3, m.version)
    assertEquals("Subgraphs", 2, m.subgraphs.size)

    val sg = m.subgraphs[0]
    assertEquals("Subgraph 0 tensors", 70, sg.tensors.size)
    assertEquals("Subgraph 0 ops", 45, sg.operators.size)
    assertEquals("Inputs", 1, sg.inputs.size)
    assertEquals("Outputs", 1, sg.outputs.size)

    val inT = sg.tensors[sg.inputs[0]]
    assertArrayEquals("Input shape", intArrayOf(1, 3, 40), inT.shape)
    assertEquals("Input type", TensorType.INT8, inT.type)

    val outT = sg.tensors[sg.outputs[0]]
    assertEquals("Output type", TensorType.UINT8, outT.type)

    // All 12 expected op types are present.
    val opCounts = sg.operators.groupingBy { it.opCode }.eachCount()
    for (code in
        listOf(
            Op.CALL_ONCE, Op.VAR_HANDLE, Op.RESHAPE, Op.READ_VARIABLE,
            Op.CONCATENATION, Op.STRIDED_SLICE, Op.ASSIGN_VARIABLE,
            Op.CONV_2D, Op.DEPTHWISE_CONV_2D, Op.FULLY_CONNECTED,
            Op.LOGISTIC, Op.QUANTIZE)) {
      assertTrue("Missing op ${Op.name(code)} ($code)", (opCounts[code] ?: 0) > 0)
    }

    // What the Go test logs for the same model.
    assertEquals(
        mapOf(2 to 6, 3 to 5, 4 to 4, 9 to 1, 14 to 1, 22 to 2, 45 to 6, 114 to 1, 129 to 1, 142 to 6, 143 to 6, 144 to 6),
        opCounts)
    val conv = sg.operators.first { it.opCode == Op.CONV_2D }
    assertEquals(listOf(3, 1, 1), listOf("stride_h", "padding", "fused_activation").map { conv.options[it] })
    assertEquals("variable tensors", 0, m.subgraphs.sumOf { sub -> sub.tensors.count { it.isVariable } })
  }
}
