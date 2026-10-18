// Ported from github.com/zserge/microwakeword (interpreter/quant.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.
package dev.wobsoriano.museportal.wake.interpreter

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/** A scale factor decomposed into (multiplier, shift) for fixed-point arithmetic. */
data class QuantMultiplier(val multiplier: Int, val shift: Int)

/** Decomposes a scale into a fixed-point multiplier (Q0.31 format) and an exponent shift. */
fun quantizeMultiplier(realMultiplier: Double): QuantMultiplier {
  if (realMultiplier == 0.0) return QuantMultiplier(0, 0)
  var sign = 1
  var m = realMultiplier
  if (m < 0) {
    sign = -1
    m = -m
  }
  var shift = 0
  while (m < 0.5) {
    m *= 2.0
    shift--
  }
  while (m >= 1.0) {
    m /= 2.0
    shift++
  }
  var qMul = goRound(m * (1L shl 31).toDouble()).toLong()
  if (qMul == 1L shl 31) {
    qMul /= 2
    shift++
  }
  return QuantMultiplier(qMul.toInt() * sign, shift)
}

/** The fixed-point multiply with rounding. */
fun multiplyByQuantizedMultiplier(x: Int, qm: QuantMultiplier): Int = multiplyByQuantizedMultiplier(x, qm.multiplier, qm.shift)

internal fun multiplyByQuantizedMultiplier(x: Int, multiplier: Int, shift: Int): Int {
  var product = x.toLong() * multiplier.toLong()
  val totalShift = 31 - shift
  if (totalShift > 0) {
    product += 1L shl (totalShift - 1)
    product = product shr totalShift
  } else if (totalShift < 0) {
    product = product shl -totalShift
  }
  return saturateInt32(product)
}

private fun saturateInt32(v: Long): Int = v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

/** Clamps a value to int8 range. */
fun clampInt8(v: Int): Int = v.coerceIn(-128, 127)

/** Clamps a value to uint8 range. */
fun clampUint8(v: Int): Int = v.coerceIn(0, 255)

/** Computes 1/(1+exp(-x)). */
fun logistic(x: Float): Float = (1.0 / (1.0 + StrictMath.exp(-x.toDouble()))).toFloat()

/** Go's math.Round: the nearest integer, rounding half away from zero. */
internal fun goRound(x: Double): Double {
  val t = truncate(x)
  return if (abs(x - t) >= 0.5) t + sign(x) else t
}
