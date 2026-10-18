// Ported from github.com/zserge/microwakeword (model/model.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.

/**
 * Parses TFLite FlatBuffer (.tflite) model files. It extracts tensors, operators, quantisation
 * parameters and weight buffers needed for inference.
 */
package dev.wobsoriano.museportal.wake.model

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builtin operator codes from the TFLite schema. */
object Op {
  const val ADD = 0
  const val AVERAGE_POOL_2D = 1
  const val CONCATENATION = 2
  const val CONV_2D = 3
  const val DEPTHWISE_CONV_2D = 4
  const val FULLY_CONNECTED = 9
  const val LOGISTIC = 14
  const val MAX_POOL_2D = 17
  const val MUL = 18
  const val RESHAPE = 22
  const val MEAN = 25
  const val PAD = 34
  const val STRIDED_SLICE = 45
  const val PACK = 83
  const val SPLIT_V = 102
  const val QUANTIZE = 114
  const val CALL_ONCE = 129
  const val VAR_HANDLE = 142
  const val READ_VARIABLE = 143
  const val ASSIGN_VARIABLE = 144

  private val names =
      mapOf(
          ADD to "ADD", AVERAGE_POOL_2D to "AVERAGE_POOL_2D",
          CONCATENATION to "CONCATENATION", CONV_2D to "CONV_2D",
          DEPTHWISE_CONV_2D to "DEPTHWISE_CONV_2D", FULLY_CONNECTED to "FULLY_CONNECTED",
          LOGISTIC to "LOGISTIC", MAX_POOL_2D to "MAX_POOL_2D",
          MUL to "MUL", RESHAPE to "RESHAPE", PAD to "PAD",
          MEAN to "MEAN", PACK to "PACK", STRIDED_SLICE to "STRIDED_SLICE",
          QUANTIZE to "QUANTIZE", CALL_ONCE to "CALL_ONCE",
          ASSIGN_VARIABLE to "ASSIGN_VARIABLE", READ_VARIABLE to "READ_VARIABLE",
          VAR_HANDLE to "VAR_HANDLE", SPLIT_V to "SPLITV",
      )

  /** A human-readable name for a builtin op code. */
  fun name(code: Int): String = names[code] ?: "OP_$code"
}

/** Tensor type codes. */
object TensorType {
  const val FLOAT32 = 0
  const val FLOAT16 = 1
  const val INT32 = 2
  const val UINT8 = 3
  const val INT64 = 4
  const val STRING = 5
  const val BOOL = 6
  const val INT16 = 7
  const val INT8 = 9
}

/** Padding and activation constants. */
object Padding {
  const val SAME = 0
  const val VALID = 1
}

object Activation {
  const val NONE = 0
  const val RELU = 1
  const val RELU6 = 3
}

/** Per-tensor or per-channel quantization parameters. */
class QuantParams(val scale: FloatArray = FloatArray(0), val zeroPoint: LongArray = LongArray(0))

/** One tensor in the model graph. */
class Tensor(
    val name: String,
    val shape: IntArray,
    val type: Int,
    val bufferIdx: Int,
    val isVariable: Boolean,
    val quant: QuantParams,
)

/** One operation in the model graph. */
class Operator(
    val opCode: Int,
    val inputs: IntArray,
    val outputs: IntArray,
    /** Builtin options by name: an Int, or an IntArray for `new_shape`. */
    val options: Map<String, Any>,
    val customOptions: ByteArray?,
)

/** One computation subgraph. */
class Subgraph(
    val name: String,
    val tensors: List<Tensor>,
    val operators: List<Operator>,
    val inputs: IntArray,
    val outputs: IntArray,
)

/** Raw model data (weights, biases, constants). */
class Buffer(val data: ByteArray?)

/** A parsed TFLite model. */
class Model(val version: Int, val subgraphs: List<Subgraph>, val buffers: List<Buffer>) {
  /** The raw bytes for a buffer index. */
  fun getBufferData(idx: Int): ByteArray? = if (idx < 0 || idx >= buffers.size) null else buffers[idx].data

  /** Buffer data as int8. */
  fun getInt8Weights(idx: Int): ByteArray? = getBufferData(idx)?.copyOf()

  /** Buffer data as int32. */
  fun getInt32Bias(idx: Int): IntArray? {
    val data = getBufferData(idx) ?: return null
    val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    return IntArray(data.size / 4) { b.getInt(it * 4) }
  }

  companion object {
    /** Reads and parses a .tflite model from disk. */
    fun loadFile(path: String): Model = parse(File(path).readBytes())

    /** Parses a TFLite FlatBuffer from raw bytes. */
    fun parse(data: ByteArray): Model {
      require(data.size >= 8) { "file too small" }
      val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
      val tab = Table(bytes, bytes.getInt(0))
      val o = tab.offset(4)
      val version = if (o != 0) tab.getInt(o + tab.pos) else 0
      val buffers = parseBuffers(tab)
      val opCodes = parseOpCodes(tab)
      return Model(version, parseSubgraphs(tab, opCodes), buffers)
    }
  }
}

/** The total number of elements. */
fun tensorSize(shape: IntArray): Int = if (shape.isEmpty()) 0 else shape.fold(1) { n, s -> n * s }

// --- FlatBuffer parsing helpers ---

/** The subset of the flatbuffers Go runtime's Table that the parser uses. */
internal class Table(val bytes: ByteBuffer, val pos: Int) {
  /** The field's offset from [pos], read from the vtable, or 0 when the field is absent. */
  fun offset(vtableOffset: Int): Int {
    val vtable = pos - bytes.getInt(pos)
    return if (vtableOffset < getVOffset(vtable)) getVOffset(vtable + vtableOffset) else 0
  }

  fun vectorLen(off: Int): Int {
    var o = off + pos
    o += bytes.getInt(o)
    return bytes.getInt(o)
  }

  /** The start of the data of the vector whose offset is stored at [off]. */
  fun vector(off: Int): Int {
    val o = off + pos
    return o + bytes.getInt(o) + 4
  }

  fun byteVector(off: Int): ByteArray {
    val o = off + bytes.getInt(off)
    val length = bytes.getInt(o)
    return ByteArray(length).also { bytes.get(o + 4, it) }
  }

  /** The table whose offset is stored at the element starting at [elemOff]. */
  fun indirect(elemOff: Int): Table = Table(bytes, elemOff + bytes.getInt(elemOff))

  fun getByte(off: Int): Int = bytes.get(off).toInt() and 0xff

  fun getInt(off: Int): Int = bytes.getInt(off)

  fun getBool(off: Int): Boolean = bytes.get(off).toInt() != 0

  private fun getVOffset(off: Int): Int = bytes.getShort(off).toInt() and 0xffff

  /** Reads an int32 vector field into an IntArray. */
  fun intVector(o: Int): IntArray {
    val start = vector(o)
    return IntArray(vectorLen(o)) { bytes.getInt(start + it * 4) }
  }

  /** The tables of a vector-of-tables field. */
  fun tables(o: Int): List<Table> {
    val start = vector(o)
    return List(vectorLen(o)) { indirect(start + it * 4) }
  }
}

private fun parseBuffers(root: Table): List<Buffer> {
  val o = root.offset(12) // field 4: buffers
  if (o == 0) return emptyList()
  return root.tables(o).map { bufTab ->
    val dOff = bufTab.offset(4)
    var data: ByteArray? = null
    if (dOff != 0) {
      val dLen = bufTab.vectorLen(dOff)
      if (dLen > 0) data = ByteArray(dLen).also { root.bytes.get(bufTab.vector(dOff), it) }
    }
    Buffer(data)
  }
}

private fun parseOpCodes(root: Table): IntArray {
  val o = root.offset(6)
  if (o == 0) return IntArray(0)
  return root.tables(o).map { ocTab ->
    val dOff = ocTab.offset(4)
    val depCode = if (dOff != 0) ocTab.getByte(dOff + ocTab.pos).toByte().toInt() else 0
    // Prefer builtin_code (voffset 10, int32) over the deprecated int8 field which can't represent
    // codes >= 128.
    val nOff = ocTab.offset(10)
    if (nOff != 0) ocTab.getInt(nOff + ocTab.pos) else depCode
  }.toIntArray()
}

private fun parseSubgraphs(root: Table, opCodes: IntArray): List<Subgraph> {
  val o = root.offset(8)
  if (o == 0) return emptyList()
  return root.tables(o).map { parseSubgraph(it, opCodes) }
}

private fun parseSubgraph(tab: Table, opCodes: IntArray): Subgraph {
  var o = tab.offset(4)
  val tensors = if (o != 0) tab.tables(o).map(::parseTensor) else emptyList()
  o = tab.offset(6)
  val inputs = if (o != 0) tab.intVector(o) else IntArray(0)
  o = tab.offset(8)
  val outputs = if (o != 0) tab.intVector(o) else IntArray(0)
  o = tab.offset(10)
  val operators = if (o != 0) tab.tables(o).map { parseOperator(it, opCodes) } else emptyList()
  o = tab.offset(12)
  val name = if (o != 0) String(tab.byteVector(o + tab.pos)) else ""
  return Subgraph(name, tensors, operators, inputs, outputs)
}

private fun parseTensor(tab: Table): Tensor {
  var o = tab.offset(4)
  val shape = if (o != 0) tab.intVector(o) else IntArray(0)
  o = tab.offset(6)
  val type = if (o != 0) tab.getByte(o + tab.pos) else 0
  o = tab.offset(8)
  val bufferIdx = if (o != 0) tab.getInt(o + tab.pos) else 0
  o = tab.offset(10)
  val name = if (o != 0) String(tab.byteVector(o + tab.pos)) else ""
  o = tab.offset(12)
  val quant = if (o != 0) parseQuantParams(tab.indirect(tab.pos + o)) else QuantParams()
  o = tab.offset(14)
  val isVariable = o != 0 && tab.getBool(o + tab.pos)
  return Tensor(name, shape, type, bufferIdx, isVariable, quant)
}

private fun parseQuantParams(tab: Table): QuantParams {
  // QuantizationParameters: min(4), max(6), scale(8), zero_point(10)
  var o = tab.offset(8)
  val scale =
      if (o == 0) FloatArray(0)
      else tab.vector(o).let { start -> FloatArray(tab.vectorLen(o)) { tab.bytes.getFloat(start + it * 4) } }
  o = tab.offset(10)
  val zeroPoint =
      if (o == 0) LongArray(0)
      else tab.vector(o).let { start -> LongArray(tab.vectorLen(o)) { tab.bytes.getLong(start + it * 8) } }
  return QuantParams(scale, zeroPoint)
}

private fun parseOperator(tab: Table, opCodes: IntArray): Operator {
  // opcode_index defaults to 0 when absent from vtable.
  var o = tab.offset(4)
  val idx = if (o != 0) tab.getInt(o + tab.pos) else 0
  val opCode = if (idx in opCodes.indices) opCodes[idx] else 0
  o = tab.offset(6)
  val inputs = if (o != 0) tab.intVector(o) else IntArray(0)
  o = tab.offset(8)
  val outputs = if (o != 0) tab.intVector(o) else IntArray(0)
  o = tab.offset(10)
  val optType = if (o != 0) tab.getByte(o + tab.pos) else 0
  val options = HashMap<String, Any>()
  if (optType != 0) {
    o = tab.offset(12)
    if (o != 0) parseBuiltinOptions(opCode, tab.indirect(tab.pos + o), options)
  }
  // custom_options (voffset 14) is used by VarHandle for shared_name.
  o = tab.offset(14)
  var customOptions: ByteArray? = null
  if (o != 0) {
    val n = tab.vectorLen(o)
    if (n > 0) customOptions = ByteArray(n).also { tab.bytes.get(tab.vector(o), it) }
  }
  return Operator(opCode, inputs, outputs, options, customOptions)
}

private fun parseBuiltinOptions(opCode: Int, tab: Table, opts: MutableMap<String, Any>) {
  when (opCode) {
    Op.CONV_2D -> {
      opts["padding"] = getI8(tab, 4)
      opts["stride_w"] = getI32(tab, 6)
      opts["stride_h"] = getI32(tab, 8)
      opts["fused_activation"] = getI8(tab, 10)
      opts["dilation_w"] = getI32(tab, 12, 1)
      opts["dilation_h"] = getI32(tab, 14, 1)
    }
    Op.DEPTHWISE_CONV_2D -> {
      opts["padding"] = getI8(tab, 4)
      opts["stride_w"] = getI32(tab, 6)
      opts["stride_h"] = getI32(tab, 8)
      opts["depth_multiplier"] = getI32(tab, 10)
      opts["fused_activation"] = getI8(tab, 12)
      opts["dilation_w"] = getI32(tab, 14, 1)
      opts["dilation_h"] = getI32(tab, 16, 1)
    }
    Op.FULLY_CONNECTED -> opts["fused_activation"] = getI8(tab, 4)
    Op.RESHAPE -> {
      val o = tab.offset(4)
      if (o != 0) opts["new_shape"] = tab.intVector(o)
    }
    Op.CONCATENATION -> {
      opts["axis"] = getI32(tab, 4)
      opts["fused_activation"] = getI8(tab, 6)
    }
    Op.STRIDED_SLICE -> {
      opts["begin_mask"] = getI32(tab, 4)
      opts["end_mask"] = getI32(tab, 6)
      opts["ellipsis_mask"] = getI32(tab, 8)
      opts["new_axis_mask"] = getI32(tab, 10)
      opts["shrink_axis_mask"] = getI32(tab, 12)
    }
    Op.CALL_ONCE -> opts["init_subgraph_index"] = getI32(tab, 4)
  }
}

private fun getI8(tab: Table, voff: Int): Int {
  val o = tab.offset(voff)
  return if (o != 0) tab.getByte(o + tab.pos).toByte().toInt() else 0
}

private fun getI32(tab: Table, voff: Int, def: Int = 0): Int {
  val o = tab.offset(voff)
  return if (o != 0) tab.getInt(o + tab.pos) else def
}

/**
 * Extracts a string value for a given key from FlexBuffer-encoded custom options. VarHandle uses it
 * for the shared_name. Returns "" if not found.
 *
 * In microWakeWord models the FlexBuffer holds {"container":"","shared_name":"XXX"}, and its
 * null-terminated strings appear in the order "container", "", "shared_name", "XXX", so the value
 * is the printable string after the key.
 */
fun extractFlexBufferString(data: ByteArray?, key: String): String {
  if (data == null || data.size < 3) return ""
  val strings = extractNullTermStrings(data)
  for (i in strings.indices) {
    if (strings[i] == key && i + 1 < strings.size) return strings[i + 1]
  }
  return ""
}

private fun extractNullTermStrings(data: ByteArray): List<String> {
  val result = ArrayList<String>()
  var start = -1
  for (i in data.indices) {
    if (data[i].toInt() == 0) {
      if (start >= 0) {
        val s = String(data, start, i - start, Charsets.UTF_8)
        if (isPrintable(s)) result += s
      }
      start = -1
    } else if (start < 0) {
      start = i
    }
  }
  return result
}

private fun isPrintable(s: String): Boolean = s.all { it.code in 32..126 }
