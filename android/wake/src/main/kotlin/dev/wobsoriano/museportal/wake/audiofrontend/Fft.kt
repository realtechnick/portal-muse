// Ported from github.com/zserge/microwakeword (audiofrontend/fft.go).
// Copyright (c) 2025 Serge Zaitsev. MIT License, see LICENSE in this module.
package dev.wobsoriano.museportal.wake.audiofrontend

// The transcendental functions are StrictMath's, which give the same bits on every JVM and on
// Android, so the features computed in a test on a PC are the ones a device computes.

/** The smallest power of 2 >= n. */
internal fun nextPow2(n: Int): Int {
  var p = 1
  while (p < n) p = p shl 1
  return p
}

/** A Hann window of the given size, the "periodic" variant: 0.5 - 0.5*cos(2*pi*i/N). */
internal fun makeHannWindow(n: Int): DoubleArray = DoubleArray(n) { 0.5 - 0.5 * StrictMath.cos(2 * Math.PI * it.toDouble() / n.toDouble()) }

/**
 * A radix-2 decimation-in-time FFT of a real signal of length n (a power of 2), in place: [re]
 * holds the signal and [im] must be zero on entry. Bins 0..n/2 of the result are the real FFT.
 */
internal fun realFFT(re: DoubleArray, im: DoubleArray, n: Int) {
  // Bit-reversal permutation
  var j = 0
  for (i in 0 until n) {
    if (i < j) {
      var t = re[i]
      re[i] = re[j]
      re[j] = t
      t = im[i]
      im[i] = im[j]
      im[j] = t
    }
    var m = n shr 1
    while (m >= 1 && j >= m) {
      j -= m
      m = m shr 1
    }
    j += m
  }

  // FFT butterfly
  var size = 2
  while (size <= n) {
    val half = size shr 1
    val angle = -2 * Math.PI / size.toDouble()
    val wRe = StrictMath.cos(angle)
    val wIm = StrictMath.sin(angle)
    var start = 0
    while (start < n) {
      var uRe = 1.0
      var uIm = 0.0
      for (k in 0 until half) {
        val a = start + k
        val b = a + half
        val tRe = uRe * re[b] - uIm * im[b]
        val tIm = uRe * im[b] + uIm * re[b]
        re[b] = re[a] - tRe
        im[b] = im[a] - tIm
        re[a] += tRe
        im[a] += tIm
        val newURe = uRe * wRe - uIm * wIm
        uIm = uRe * wIm + uIm * wRe
        uRe = newURe
      }
      start += size
    }
    size = size shl 1
  }
}

/** Frequency in Hz to the mel scale. */
internal fun hzToMel(hz: Double): Double = 1127.0 * StrictMath.log(1.0 + hz / 700.0)

/** The mel scale to frequency in Hz. */
internal fun melToHz(mel: Double): Double = 700.0 * (StrictMath.exp(mel / 1127.0) - 1.0)

/** Triangular mel-spaced filterbank weights, one row of fftSize/2+1 bins per channel. */
internal fun makeMelFilterbank(numChannels: Int, fftSize: Int, sampleRate: Int, lowerHz: Double, upperHz: Double): Array<DoubleArray> {
  val numBins = fftSize / 2 + 1
  val lowerMel = hzToMel(lowerHz)
  val upperMel = hzToMel(upperHz)

  // numChannels+2 equally spaced mel points, converted to FFT bin indices.
  val melPoints = DoubleArray(numChannels + 2) { lowerMel + it.toDouble() * (upperMel - lowerMel) / (numChannels + 1).toDouble() }
  val binPoints = DoubleArray(numChannels + 2) { melToHz(melPoints[it]) * fftSize.toDouble() / sampleRate.toDouble() }

  return Array(numChannels) { ch ->
    val left = binPoints[ch]
    val center = binPoints[ch + 1]
    val right = binPoints[ch + 2]
    DoubleArray(numBins) { bin ->
      val fb = bin.toDouble()
      if (fb >= left && fb <= center && center > left) (fb - left) / (center - left)
      else if (fb > center && fb <= right && right > center) (right - fb) / (right - center)
      else 0.0
    }
  }
}
