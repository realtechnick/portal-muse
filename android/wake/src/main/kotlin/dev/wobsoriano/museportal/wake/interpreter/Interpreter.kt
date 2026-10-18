// Ported from github.com/zserge/microwakeword (interpreter/interpreter.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.
package dev.wobsoriano.museportal.wake.interpreter

import dev.wobsoriano.museportal.wake.model.Model
import dev.wobsoriano.museportal.wake.model.Op
import dev.wobsoriano.museportal.wake.model.Tensor
import dev.wobsoriano.museportal.wake.model.TensorType
import dev.wobsoriano.museportal.wake.model.extractFlexBufferString
import dev.wobsoriano.museportal.wake.model.tensorSize

/**
 * Runs forward inference on a parsed TFLite model. It supports the subset of TFLite ops used by
 * microWakeWord v2 models, including stateful resource variables
 * (VarHandle/ReadVariable/AssignVariable).
 *
 * Unlike the Go interpreter, which looks every operator up and derives its parameters on each
 * [invoke], this one prepares a kernel per operator once, here, so that [invoke] allocates nothing.
 * An unsupported op is therefore an error at construction rather than at the first [invoke].
 */
class Interpreter(val model: Model) {
  internal val resources = HashMap<String, Resource>()
  internal var callOnceExecuted = false
  private val graphs = HashMap<Int, Graph>()
  private val main: Graph

  init {
    require(model.subgraphs.isNotEmpty()) { "model has no subgraphs" }
    main = graph(0)
  }

  internal fun graph(sgIdx: Int): Graph = graphs[sgIdx] ?: Graph(this, sgIdx).also { graphs[sgIdx] = it }

  internal fun resource(name: String): Resource = resources.getOrPut(name) { Resource(name) }

  /** Copies int8 features into the input tensor. */
  fun setInput(data: ByteArray) {
    val sg = model.subgraphs[0]
    require(sg.inputs.isNotEmpty()) { "no input tensors" }
    val td = main.tensors[sg.inputs[0]]
    require(data.size <= td.size) { "input too large: ${data.size} > ${td.size}" }
    data.copyInto(td)
  }

  /** Runs one inference step. */
  fun invoke() = main.run()

  /** The output tensor as uint8 values. */
  fun outputUint8(): IntArray {
    val sg = model.subgraphs[0]
    if (sg.outputs.isEmpty()) return IntArray(0)
    val data = main.tensors[sg.outputs[0]]
    return IntArray(data.size) { data[it].toInt() and 0xff }
  }

  /** The first output value as uint8, without allocating. */
  internal fun outputUint8At0(): Int {
    val sg = model.subgraphs[0]
    if (sg.outputs.isEmpty()) return -1
    val data = main.tensors[sg.outputs[0]]
    return if (data.isEmpty()) -1 else data[0].toInt() and 0xff
  }

  /** The dequantized output as float32. */
  fun outputFloat32(): FloatArray {
    val sg = model.subgraphs[0]
    if (sg.outputs.isEmpty()) return FloatArray(0)
    val outT = sg.tensors[sg.outputs[0]]
    val data = main.tensors[sg.outputs[0]]
    val scale = outT.quant.scale.firstOrNull() ?: 0f
    val zp = outT.quant.zeroPoint.firstOrNull()?.toInt() ?: 0
    return FloatArray(data.size) {
      val q = if (outT.type == TensorType.UINT8) data[it].toInt() and 0xff else data[it].toInt()
      (q - zp).toFloat() * scale
    }
  }

  /** Clears all resource variables and tensors, restoring the interpreter to its initial state. */
  fun reset() {
    for (r in resources.values) r.data = null
    callOnceExecuted = false
    for (g in graphs.values) g.reset()
  }
}

/** A resource variable's current value, shared by every handle with its name. */
internal class Resource(val name: String) {
  var data: ByteArray? = null
}

/** One subgraph's tensors and prepared kernels. */
internal class Graph(val interp: Interpreter, val sgIdx: Int) {
  val model = interp.model
  val sg = model.subgraphs[sgIdx]
  val tensors: Array<ByteArray> = Array(sg.tensors.size) { ByteArray(byteSize(sg.tensors[it])) }

  /** Resource names by VarHandle output tensor (the Go interpreter's varHandleNames). */
  private val handleNames = HashMap<Int, String>()

  private val kernels: Array<Kernel>

  init {
    reset()
    for (op in sg.operators) {
      if (op.opCode != Op.VAR_HANDLE) continue
      require(op.outputs.size == 1) { "VAR_HANDLE: expected 1 output, got ${op.outputs.size}" }
      // The FlexBuffer custom options name the resource; failing that, the tensor name with
      // trailing digits stripped from its last path component.
      val sharedName =
          extractFlexBufferString(op.customOptions, "shared_name").ifEmpty {
            normalizeResourceName(sg.tensors[op.outputs[0]].name)
          }
      handleNames[op.outputs[0]] = sharedName
    }
    kernels =
        Array(sg.operators.size) { i ->
          val op = sg.operators[i]
          val prepare =
              OPS[op.opCode] ?: throw IllegalArgumentException("op[$i]: unsupported ${Op.name(op.opCode)} (${op.opCode})")
          try {
            prepare(this, op)
          } catch (e: RuntimeException) {
            throw IllegalArgumentException("op[$i] ${Op.name(op.opCode)}: ${e.message}", e)
          }
        }
  }

  fun resourceFor(tensorIdx: Int): Resource = interp.resource(handleNames[tensorIdx] ?: sg.tensors[tensorIdx].name)

  fun run() {
    for (k in kernels) k.run()
  }

  /** Refills every tensor from its model buffer, zeroing the rest. */
  fun reset() {
    for (i in tensors.indices) {
      val td = tensors[i]
      td.fill(0)
      val buf = model.getBufferData(sg.tensors[i].bufferIdx)
      if (buf != null && buf.isNotEmpty()) buf.copyInto(td, 0, 0, minOf(buf.size, td.size))
    }
  }

  private fun byteSize(t: Tensor): Int {
    var size = tensorSize(t.shape)
    if (size <= 0) size = 1
    val bytesPerElem =
        when (t.type) {
          TensorType.INT32, TensorType.FLOAT32 -> 4
          TensorType.INT64 -> 8
          TensorType.FLOAT16, TensorType.INT16 -> 2
          else -> 1
        }
    return size * bytesPerElem
  }
}

/** Strips trailing digits from the last path component: "stream_5/states1" is "stream_5/states". */
internal fun normalizeResourceName(name: String): String {
  val lastSlash = name.lastIndexOf('/')
  val suffix = name.substring(lastSlash + 1)
  var end = suffix.length
  while (end > 0 && suffix[end - 1] in '0'..'9') end--
  if (end == 0 || end == suffix.length) return name // No digits to strip or all digits
  return name.substring(0, lastSlash + 1) + suffix.substring(0, end)
}
