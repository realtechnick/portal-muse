// Ported from github.com/zserge/microwakeword (interpreter/ops.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.

// TFLite op kernels for microWakeWord models. Each prepare function checks its operator and derives
// what the Go op function derives on every call (shapes, padding, requantization multipliers), then
// returns the kernel that does the per-invoke arithmetic exactly as the Go op does.
package dev.wobsoriano.museportal.wake.interpreter

import dev.wobsoriano.museportal.wake.model.Activation
import dev.wobsoriano.museportal.wake.model.Op
import dev.wobsoriano.museportal.wake.model.Operator
import dev.wobsoriano.museportal.wake.model.Padding
import dev.wobsoriano.museportal.wake.model.Tensor
import dev.wobsoriano.museportal.wake.model.TensorType
import dev.wobsoriano.museportal.wake.model.tensorSize

internal fun interface Kernel {
  fun run()
}

internal val OPS: Map<Int, (Graph, Operator) -> Kernel> =
    mapOf(
        Op.CALL_ONCE to ::callOnce,
        Op.VAR_HANDLE to { _, _ -> Kernel {} },
        Op.RESHAPE to ::reshape,
        Op.READ_VARIABLE to ::readVariable,
        Op.ASSIGN_VARIABLE to ::assignVariable,
        Op.CONCATENATION to ::concatenation,
        Op.STRIDED_SLICE to ::stridedSlice,
        Op.CONV_2D to ::conv2D,
        Op.DEPTHWISE_CONV_2D to ::depthwiseConv2D,
        Op.FULLY_CONNECTED to ::fullyConnected,
        Op.LOGISTIC to ::logistic,
        Op.QUANTIZE to ::quantize,
        Op.SPLIT_V to ::splitV,
    )

private fun Operator.opt(name: String, default: Int = 0): Int = options[name] as Int? ?: default

private val Tensor.zeroPoint: Int
  get() = quant.zeroPoint.firstOrNull()?.toInt() ?: 0

private val Tensor.scale: Double
  get() = quant.scale.firstOrNull()?.toDouble() ?: 0.0

// --- Simple ops ---

private fun callOnce(g: Graph, op: Operator): Kernel {
  val sgIdx = op.options["init_subgraph_index"] as Int? ?: throw IllegalArgumentException("CALL_ONCE: missing init_subgraph_index")
  require(sgIdx >= 0 && sgIdx < g.model.subgraphs.size) { "CALL_ONCE: invalid subgraph $sgIdx" }
  val interp = g.interp
  val init = interp.graph(sgIdx)
  return Kernel {
    if (!interp.callOnceExecuted) {
      init.reset()
      init.run()
      interp.callOnceExecuted = true
    }
  }
}

private fun readVariable(g: Graph, op: Operator): Kernel {
  require(op.inputs.size == 1 && op.outputs.size == 1) { "READ_VARIABLE: expected 1 input 1 output" }
  val resource = g.resourceFor(op.inputs[0])
  val out = g.tensors[op.outputs[0]]
  return Kernel {
    val data = resource.data ?: throw IllegalStateException("READ_VARIABLE: resource \"${resource.name}\" not found")
    data.copyInto(out, 0, 0, minOf(data.size, out.size))
  }
}

private fun assignVariable(g: Graph, op: Operator): Kernel {
  require(op.inputs.size == 2) { "ASSIGN_VARIABLE: expected 2 inputs" }
  val resource = g.resourceFor(op.inputs[0])
  val src = g.tensors[op.inputs[1]]
  return Kernel {
    // Stores a copy of the value; the array is reused once it has the value's size.
    val dst = resource.data
    if (dst != null && dst.size == src.size) src.copyInto(dst) else resource.data = src.copyOf()
  }
}

private fun reshape(g: Graph, op: Operator): Kernel {
  require(op.inputs.isNotEmpty() && op.outputs.size == 1) { "RESHAPE: bad io counts" }
  val input = g.tensors[op.inputs[0]]
  val out = g.tensors[op.outputs[0]]
  // Just copy data; the shape is already set from the tensor definition.
  return Kernel { input.copyInto(out, 0, 0, minOf(input.size, out.size)) }
}

private fun concatenation(g: Graph, op: Operator): Kernel {
  require(op.outputs.size == 1) { "CONCATENATION: bad output count" }
  val sg = g.sg
  var axis = op.opt("axis")
  val outShape = sg.tensors[op.outputs[0]].shape
  val out = g.tensors[op.outputs[0]]
  val ndim = outShape.size
  if (axis < 0) axis += ndim

  // outerSize is the product of dims before axis, innerSize of dims after it.
  var outerSize = 1
  for (i in 0 until axis) outerSize *= outShape[i]
  var innerSize = 1
  for (i in axis + 1 until ndim) innerSize *= outShape[i]

  val inputs = Array(op.inputs.size) { g.tensors[op.inputs[it]] }
  val chunkSizes = IntArray(op.inputs.size) { sg.tensors[op.inputs[it]].shape[axis] * innerSize }
  return Kernel {
    var dstOff = 0
    for (outer in 0 until outerSize) {
      for (k in inputs.indices) {
        val chunkSize = chunkSizes[k]
        inputs[k].copyInto(out, dstOff, outer * chunkSize, outer * chunkSize + chunkSize)
        dstOff += chunkSize
      }
    }
  }
}

private fun stridedSlice(g: Graph, op: Operator): Kernel {
  require(op.inputs.size >= 4 && op.outputs.size == 1) { "STRIDED_SLICE: bad io" }
  val sg = g.sg
  val inShape = sg.tensors[op.inputs[0]].shape
  val inData = g.tensors[op.inputs[0]]
  val outShape = sg.tensors[op.outputs[0]].shape
  val out = g.tensors[op.outputs[0]]

  val ndim = inShape.size
  val beginData = g.tensors[op.inputs[1]]
  val endData = g.tensors[op.inputs[2]]
  val strideData = g.tensors[op.inputs[3]]
  val beginMask = op.opt("begin_mask")
  val endMask = op.opt("end_mask")

  val begin = IntArray(ndim)
  val end = IntArray(ndim)
  val stride = IntArray(ndim)
  val outCoord = IntArray(ndim)
  val outTotal = tensorSize(outShape)
  return Kernel {
    for (d in 0 until ndim) {
      begin[d] = getInt32FromBytes(beginData, d)
      end[d] = getInt32FromBytes(endData, d)
      stride[d] = getInt32FromBytes(strideData, d)
      if (beginMask and (1 shl d) != 0) begin[d] = 0
      if (endMask and (1 shl d) != 0) end[d] = inShape[d]
      if (begin[d] < 0) begin[d] += inShape[d]
      if (end[d] < 0) end[d] += inShape[d]
      if (stride[d] == 0) stride[d] = 1
    }
    // Iterate over output positions and copy from input.
    outCoord.fill(0)
    for (i in 0 until outTotal) {
      var inOff = 0
      var inStride = 1
      for (d in ndim - 1 downTo 0) {
        inOff += (begin[d] + outCoord[d] * stride[d]) * inStride
        inStride *= inShape[d]
      }
      out[i] = inData[inOff]
      for (d in ndim - 1 downTo 0) {
        outCoord[d]++
        if (outCoord[d] < outShape[d]) break
        outCoord[d] = 0
      }
    }
  }
}

private fun getInt32FromBytes(data: ByteArray, idx: Int): Int {
  val off = idx * 4
  if (off + 4 > data.size) return 0
  return (data[off].toInt() and 0xff) or ((data[off + 1].toInt() and 0xff) shl 8) or
      ((data[off + 2].toInt() and 0xff) shl 16) or (data[off + 3].toInt() shl 24)
}

private fun getBias32(data: ByteArray, idx: Int): Int = getInt32FromBytes(data, idx)

/** Per-channel requantization multipliers: input scale times filter scale over output scale. */
private class Requant(inT: Tensor, filterT: Tensor, outT: Tensor, channels: Int) {
  val multiplier = IntArray(channels)
  val shift = IntArray(channels)

  init {
    val inScale = inT.scale
    var outScale = outT.scale
    for (c in 0 until channels) {
      val filterScales = filterT.quant.scale
      val filterScale =
          if (c < filterScales.size) filterScales[c].toDouble() else if (filterScales.isNotEmpty()) filterScales[0].toDouble() else 0.0
      if (outScale == 0.0) outScale = 1.0 // avoid division by zero
      val qm = quantizeMultiplier(inScale * filterScale / outScale)
      multiplier[c] = qm.multiplier
      shift[c] = qm.shift
    }
  }
}

/** Padding offsets for SAME padding, or zero. */
private fun pad(padding: Int, out: Int, stride: Int, filter: Int, dilation: Int, input: Int): Int {
  if (padding != Padding.SAME) return 0
  val eff = (filter - 1) * dilation + 1
  return maxOf(((out - 1) * stride + eff - input) / 2, 0)
}

private fun activationBounds(activation: Int, outputZP: Int): IntArray {
  var actMin = -128
  val actMax = 127
  when (activation) {
    // RELU6 would also need a quantized 6.0 as its max, but MWW models use RELU.
    Activation.RELU, Activation.RELU6 -> if (outputZP > actMin) actMin = outputZP
  }
  return intArrayOf(actMin, actMax)
}

/** Quantized 2D convolution. Input [batch, inH, inW, inC], filter [outC, fH, fW, inC], bias [outC]. */
private fun conv2D(g: Graph, op: Operator): Kernel {
  require(op.inputs.size >= 3 && op.outputs.size == 1) { "CONV_2D: bad io" }
  val sg = g.sg
  val inT = sg.tensors[op.inputs[0]]
  val filterT = sg.tensors[op.inputs[1]]
  val outT = sg.tensors[op.outputs[0]]
  val inData = g.tensors[op.inputs[0]]
  val filterData = g.tensors[op.inputs[1]]
  val biasData = g.tensors[op.inputs[2]]
  val outData = g.tensors[op.outputs[0]]

  val strideW = op.opt("stride_w", 1)
  val strideH = op.opt("stride_h", 1)
  val dilationW = op.opt("dilation_w", 1)
  val dilationH = op.opt("dilation_h", 1)

  val batch = inT.shape[0]
  val inH = inT.shape[1]
  val inW = inT.shape[2]
  val inC = inT.shape[3]
  val outC = filterT.shape[0]
  val fH = filterT.shape[1]
  val fW = filterT.shape[2]
  val outH = outT.shape[1]
  val outW = outT.shape[2]

  val inputZP = inT.zeroPoint
  val outputZP = outT.zeroPoint
  val q = Requant(inT, filterT, outT, outC)
  val padH = pad(op.opt("padding"), outH, strideH, fH, dilationH, inH)
  val padW = pad(op.opt("padding"), outW, strideW, fW, dilationW, inW)
  val (actMin, actMax) = activationBounds(op.opt("fused_activation"), outputZP)

  return Kernel {
    for (b in 0 until batch) {
      for (oh in 0 until outH) {
        for (ow in 0 until outW) {
          for (oc in 0 until outC) {
            var acc = getBias32(biasData, oc)
            for (fh in 0 until fH) {
              for (fw in 0 until fW) {
                val ih = oh * strideH - padH + fh * dilationH
                val iw = ow * strideW - padW + fw * dilationW
                if (ih < 0 || ih >= inH || iw < 0 || iw >= inW) continue
                val inBase = ((b * inH + ih) * inW + iw) * inC
                val fBase = ((oc * fH + fh) * fW + fw) * inC
                for (ic in 0 until inC) {
                  acc += (inData[inBase + ic] - inputZP) * filterData[fBase + ic]
                }
              }
            }
            acc = multiplyByQuantizedMultiplier(acc, q.multiplier[oc], q.shift[oc])
            acc += outputZP
            if (acc < actMin) acc = actMin
            if (acc > actMax) acc = actMax
            outData[((b * outH + oh) * outW + ow) * outC + oc] = clampInt8(acc).toByte()
          }
        }
      }
    }
  }
}

/**
 * Quantized depthwise convolution. Input [batch, inH, inW, inC], filter [1, fH, fW, outC], bias
 * [outC], outC = inC * depth_multiplier.
 */
private fun depthwiseConv2D(g: Graph, op: Operator): Kernel {
  require(op.inputs.size >= 3 && op.outputs.size == 1) { "DEPTHWISE_CONV_2D: bad io" }
  val sg = g.sg
  val inT = sg.tensors[op.inputs[0]]
  val filterT = sg.tensors[op.inputs[1]]
  val outT = sg.tensors[op.outputs[0]]
  val inData = g.tensors[op.inputs[0]]
  val filterData = g.tensors[op.inputs[1]]
  val biasData = g.tensors[op.inputs[2]]
  val outData = g.tensors[op.outputs[0]]

  val strideW = op.opt("stride_w", 1)
  val strideH = op.opt("stride_h", 1)
  val depthMul = op.opt("depth_multiplier", 1)
  val dilationW = op.opt("dilation_w", 1)
  val dilationH = op.opt("dilation_h", 1)

  val batch = inT.shape[0]
  val inH = inT.shape[1]
  val inW = inT.shape[2]
  val inC = inT.shape[3]
  val fH = filterT.shape[1]
  val fW = filterT.shape[2]
  val outC = inC * depthMul
  val outH = outT.shape[1]
  val outW = outT.shape[2]

  val inputZP = inT.zeroPoint
  val outputZP = outT.zeroPoint
  val q = Requant(inT, filterT, outT, outC)
  val padH = pad(op.opt("padding"), outH, strideH, fH, dilationH, inH)
  val padW = pad(op.opt("padding"), outW, strideW, fW, dilationW, inW)
  val (actMin, actMax) = activationBounds(op.opt("fused_activation"), outputZP)

  return Kernel {
    for (b in 0 until batch) {
      for (oh in 0 until outH) {
        for (ow in 0 until outW) {
          for (ic in 0 until inC) {
            for (dm in 0 until depthMul) {
              val oc = ic * depthMul + dm
              var acc = getBias32(biasData, oc)
              for (fh in 0 until fH) {
                for (fw in 0 until fW) {
                  val ih = oh * strideH - padH + fh * dilationH
                  val iw = ow * strideW - padW + fw * dilationW
                  if (ih < 0 || ih >= inH || iw < 0 || iw >= inW) continue
                  val inVal = inData[((b * inH + ih) * inW + iw) * inC + ic] - inputZP
                  acc += inVal * filterData[(fh * fW + fw) * outC + oc]
                }
              }
              acc = multiplyByQuantizedMultiplier(acc, q.multiplier[oc], q.shift[oc])
              acc += outputZP
              if (acc < actMin) acc = actMin
              if (acc > actMax) acc = actMax
              outData[((b * outH + oh) * outW + ow) * outC + oc] = clampInt8(acc).toByte()
            }
          }
        }
      }
    }
  }
}

/** Quantized fully connected (dense) layer. Input [batch, inputSize], weights [outputSize, inputSize]. */
private fun fullyConnected(g: Graph, op: Operator): Kernel {
  require(op.inputs.size >= 3 && op.outputs.size == 1) { "FULLY_CONNECTED: bad io" }
  val sg = g.sg
  val inT = sg.tensors[op.inputs[0]]
  val weightT = sg.tensors[op.inputs[1]]
  val outT = sg.tensors[op.outputs[0]]
  val inData = g.tensors[op.inputs[0]]
  val weightData = g.tensors[op.inputs[1]]
  val biasData = g.tensors[op.inputs[2]]
  val outData = g.tensors[op.outputs[0]]

  // The input is flattened; its size comes from the weights.
  val outputSize = weightT.shape[0]
  val inputSize = weightT.shape[1]
  val batch = tensorSize(inT.shape) / inputSize

  val inputZP = inT.zeroPoint
  val outputZP = outT.zeroPoint
  val q = Requant(inT, weightT, outT, outputSize)
  val (actMin, actMax) = activationBounds(op.opt("fused_activation"), outputZP)

  return Kernel {
    for (b in 0 until batch) {
      for (oc in 0 until outputSize) {
        var acc = getBias32(biasData, oc)
        val inBase = b * inputSize
        val wBase = oc * inputSize
        for (ic in 0 until inputSize) {
          acc += (inData[inBase + ic] - inputZP) * weightData[wBase + ic]
        }
        acc = multiplyByQuantizedMultiplier(acc, q.multiplier[oc], q.shift[oc])
        acc += outputZP
        if (acc < actMin) acc = actMin
        if (acc > actMax) acc = actMax
        outData[b * outputSize + oc] = clampInt8(acc).toByte()
      }
    }
  }
}

/** Requantizes [real] to the output tensor's type, as the Go LOGISTIC and QUANTIZE ops do. */
private fun requantize(real: Double, outScale: Double, outZP: Int, outType: Int): Byte {
  val outVal = goRound(real / outScale).toInt() + outZP
  return if (outType == TensorType.UINT8) clampUint8(outVal).toByte() else clampInt8(outVal).toByte()
}

/**
 * The logistic (sigmoid) function. Its input is one byte per element, so the kernel computes the
 * Go op's dequantize, sigmoid and requantize once for each of the 256 byte values and looks them up.
 */
private fun logistic(g: Graph, op: Operator): Kernel {
  require(op.inputs.size == 1 && op.outputs.size == 1) { "LOGISTIC: bad io" }
  val inT = g.sg.tensors[op.inputs[0]]
  val outT = g.sg.tensors[op.outputs[0]]
  val inData = g.tensors[op.inputs[0]]
  val outData = g.tensors[op.outputs[0]]
  val n = tensorSize(inT.shape)
  val inScale = inT.scale
  val inZP = inT.zeroPoint
  val table =
      ByteArray(256) { b ->
        val realVal = (b.toByte() - inZP).toDouble() * inScale
        val sig = 1.0 / (1.0 + StrictMath.exp(-realVal))
        requantize(sig, outT.scale, outT.zeroPoint, outT.type)
      }
  return Kernel { for (i in 0 until n) outData[i] = table[inData[i].toInt() and 0xff] }
}

/** Re-quantizes a tensor from one scale and zero point to another, by lookup as [logistic] does. */
private fun quantize(g: Graph, op: Operator): Kernel {
  require(op.inputs.size == 1 && op.outputs.size == 1) { "QUANTIZE: bad io" }
  val inT = g.sg.tensors[op.inputs[0]]
  val outT = g.sg.tensors[op.outputs[0]]
  val inData = g.tensors[op.inputs[0]]
  val outData = g.tensors[op.outputs[0]]
  val n = tensorSize(inT.shape)
  val inScale = inT.scale
  val inZP = inT.zeroPoint
  val table =
      ByteArray(256) { b ->
        val q = if (inT.type == TensorType.UINT8) b else b.toByte().toInt()
        requantize((q - inZP).toDouble() * inScale, outT.scale, outT.zeroPoint, outT.type)
      }
  return Kernel { for (i in 0 until n) outData[i] = table[inData[i].toInt() and 0xff] }
}

/**
 * Splits a tensor along an axis into multiple outputs. Inputs: the value, a 1-D int32 tensor of
 * split sizes (one may be -1, inferred), and a scalar int32 axis.
 */
private fun splitV(g: Graph, op: Operator): Kernel {
  require(op.inputs.size >= 3 && op.outputs.size >= 2) {
    "SPLIT_V: need 3 inputs and >=2 outputs, got ${op.inputs.size}/${op.outputs.size}"
  }
  val inT = g.sg.tensors[op.inputs[0]]
  val inData = g.tensors[op.inputs[0]]
  val inShape = inT.shape
  val ndim = inShape.size
  val axisData = g.tensors[op.inputs[2]]
  val splitSizesData = g.tensors[op.inputs[1]]
  val outputs = Array(op.outputs.size) { g.tensors[op.outputs[it]] }
  val splitSizes = IntArray(outputs.size)
  val bpe =
      when (inT.type) {
        TensorType.INT32, TensorType.FLOAT32 -> 4
        TensorType.INT16 -> 2
        else -> 1
      }
  return Kernel {
    var axis = getInt32FromBytes(axisData, 0)
    if (axis < 0) axis += ndim
    check(axis in 0 until ndim) { "SPLIT_V: axis $axis out of range for $ndim dims" }

    var inferIdx = -1
    var knownSum = 0
    for (i in splitSizes.indices) {
      splitSizes[i] = getInt32FromBytes(splitSizesData, i)
      if (splitSizes[i] == -1) inferIdx = i else knownSum += splitSizes[i]
    }
    if (inferIdx >= 0) splitSizes[inferIdx] = inShape[axis] - knownSum

    var outerSize = 1
    for (d in 0 until axis) outerSize *= inShape[d]
    var innerSize = 1
    for (d in axis + 1 until ndim) innerSize *= inShape[d]

    var axisOffset = 0
    for (s in outputs.indices) {
      val out = outputs[s]
      val sliceSize = splitSizes[s]
      var pos = 0
      for (o in 0 until outerSize) {
        val srcStart = (o * inShape[axis] + axisOffset) * innerSize * bpe
        val chunkBytes = sliceSize * innerSize * bpe
        inData.copyInto(out, pos, srcStart, srcStart + chunkBytes)
        pos += chunkBytes
      }
      axisOffset += sliceSize
    }
  }
}
