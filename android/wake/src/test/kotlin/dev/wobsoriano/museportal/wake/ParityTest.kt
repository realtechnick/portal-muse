package dev.wobsoriano.museportal.wake

import dev.wobsoriano.museportal.wake.audiofrontend.Frontend
import dev.wobsoriano.museportal.wake.audiofrontend.quantizeFeatures
import dev.wobsoriano.museportal.wake.model.Model
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.wobsoriano.museportal.wake.audiofrontend.Config as FrontendConfig

/**
 * Replays the cases tools/wakeparity ran through the Go detector and asserts the port computes the
 * same at every 10 ms step: the raw and int8 features, whether an inference ran and its uint8
 * output, the sliding-window average to the bit, and where ProcessAudio reports detections.
 */
class ParityTest {
  private class Case(val name: String, val model: String, val wav: String, val window: Int, val stepMs: Int, val cutoff: Double)

  @Test
  fun matchesGo() {
    val cases =
        resource("parity/cases.txt").readLines().filter { it.isNotBlank() }.map {
          val f = it.split(" ")
          Case(f[0], f[1], f[2], f[3].toInt(), f[4].toInt(), f[5].toDouble())
        }
    assertTrue(cases.isNotEmpty())
    for (c in cases) check(c)
  }

  private fun check(c: Case) {
    val golden = ByteBuffer.wrap(resource("parity/${c.name}.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals("MWWP", String(ByteArray(4).also { golden.get(it) }))
    assertEquals(1, golden.getInt())
    val steps = golden.getInt()

    val samples = readWav(resource(c.wav))
    val model = Model.loadFile(resource(c.model).path)
    val det = Detector(Config(probabilityCutoff = NEVER_FIRES, slidingWindowSize = c.window, featuresStepMs = c.stepMs), model)
    var inferred = false
    var rawProb = 0
    det.onInference = InferenceListener { _, p, _ ->
      inferred = true
      rawProb = p
    }
    val fe = Frontend(FrontendConfig.default(c.stepMs))
    val raw = IntArray(40)
    val q = ByteArray(40)
    val goRaw = IntArray(40)
    val goQ = ByteArray(40)

    var step = 0
    var off = 0
    var maxProb = 0
    while (off + fe.windowSize <= samples.size) {
      fe.processFrame(samples, off, raw)
      quantizeFeatures(raw, q)
      inferred = false
      det.processFeatures(q)
      for (i in 0 until 40) goRaw[i] = golden.getShort().toInt() and 0xffff
      golden.get(goQ)
      val goInferred = golden.get().toInt() != 0
      val goProb = golden.get().toInt() and 0xff
      val goAvg = golden.getLong()
      val at = "${c.name} step $step"
      assertArrayEquals("$at raw features", goRaw, raw)
      assertArrayEquals("$at int8 features", goQ, q)
      assertEquals("$at inference ran", goInferred, inferred)
      if (goInferred) {
        assertEquals("$at probability", goProb, rawProb)
        maxProb = maxOf(maxProb, rawProb)
      }
      assertEquals("$at sliding average", goAvg, det.slidingAverage().toRawBits())
      step++
      off += fe.stepSize
    }
    assertEquals("${c.name} steps", steps, step)

    val goHits = IntArray(golden.getInt()) { golden.getInt() }
    val streaming = Detector(Config(probabilityCutoff = c.cutoff, slidingWindowSize = c.window, featuresStepMs = c.stepMs), model)
    val hits = ArrayList<Int>()
    var i = 0
    while (i + CHUNK <= samples.size) {
      if (streaming.processAudio(samples, i, CHUNK)) hits += i
      i += CHUNK
    }
    assertArrayEquals("${c.name} ProcessAudio detections", goHits, hits.toIntArray())
    assertEquals("${c.name} trailing bytes", 0, golden.remaining())
    println(
        "%-24s %4d steps identical, max prob %3d/255 (%.4f), %d ProcessAudio detections, first at %s"
            .format(c.name, step, maxProb, maxProb / 255.0, hits.size, hits.firstOrNull()?.let { "%.2fs".format(it / 16000.0) } ?: "-"))
  }

  private companion object {
    const val NEVER_FIRES = 1.1
    const val CHUNK = 160

    /** The samples of the data chunk of a 16 kHz mono PCM16 WAV, as tools/wakeparity reads them. */
    fun readWav(file: File): ShortArray {
      val data = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
      require(String(file.readBytes(), 0, 4) == "RIFF")
      var off = 12
      while (off + 8 <= data.capacity()) {
        val id = String(ByteArray(4) { data.get(off + it) })
        val size = data.getInt(off + 4)
        if (id == "fmt ") {
          require(data.getShort(off + 8).toInt() == 1 && data.getShort(off + 10).toInt() == 1 && data.getInt(off + 12) == 16000 && data.getShort(off + 22).toInt() == 16) {
            "${file.name}: want 16 kHz mono PCM16"
          }
        } else if (id == "data") {
          val n = minOf(size, data.capacity() - off - 8) / 2
          return ShortArray(n) { data.getShort(off + 8 + it * 2) }
        }
        off += 8 + size + size % 2
      }
      throw IllegalArgumentException("${file.name}: no data chunk")
    }
  }
}
