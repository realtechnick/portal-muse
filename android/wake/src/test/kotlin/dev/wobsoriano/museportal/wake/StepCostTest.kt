package dev.wobsoriano.museportal.wake

import dev.wobsoriano.museportal.wake.audiofrontend.Frontend
import dev.wobsoriano.museportal.wake.model.Model
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.wobsoriano.museportal.wake.audiofrontend.Config as FrontendConfig

/**
 * Feeding the detector allocates nothing per step, and what a 10 ms step costs once the JIT has
 * warmed up. The cost is printed, not asserted: it depends on the machine.
 */
class StepCostTest {
  private val samples = loadTestWav(testdata("nowake.wav"))
  private val model = Model.loadFile(resource("hey_muse.tflite").path)

  private fun detector() = Detector(Config(probabilityCutoff = 1.1, slidingWindowSize = 6, featuresStepMs = 10), model)

  /** Feeds the whole WAV in 10 ms chunks and returns the steps fed. */
  private fun feed(det: Detector): Int {
    var i = 0
    while (i + STEP <= samples.size) {
      det.processAudio(samples, i, STEP)
      i += STEP
    }
    return i / STEP
  }

  /**
   * The only allocation left is inside the PC JDK's StrictMath.pow, a Java port of fdlibm that
   * builds small constant arrays on each call. Android's StrictMath.pow is native fdlibm and
   * allocates nothing, so this subtracts what the front end's 40 pow calls a step allocate here.
   */
  @Test
  fun noAllocationPerStep() {
    val det = detector()
    val args = DoubleArray(40) { 80.0 + it * 37.3 }
    var sink = 0.0
    fun pows(steps: Int) {
      repeat(steps) { for (a in args) sink += StrictMath.pow(a, 0.95) }
    }
    repeat(20) {
      feed(det)
      pows(1000)
    }
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val id = Thread.currentThread().id
    var before = threads.getThreadAllocatedBytes(id)
    val steps = feed(det)
    val allocated = threads.getThreadAllocatedBytes(id) - before
    before = threads.getThreadAllocatedBytes(id)
    pows(steps)
    val inPow = threads.getThreadAllocatedBytes(id) - before
    println("allocated $allocated bytes over $steps steps, of which JDK StrictMath.pow accounts for $inPow ($sink)")
    assertEquals(inPow, allocated)
  }

  @Test
  fun costPerStep() {
    val det = detector()
    val fe = Frontend(FrontendConfig.default(10))
    val raw = IntArray(40)
    fun frontendOnly(): Int {
      var off = 0
      var n = 0
      while (off + fe.windowSize <= samples.size) {
        fe.processFrame(samples, off, raw)
        off += fe.stepSize
        n++
      }
      return n
    }
    repeat(50) {
      feed(det)
      frontendOnly()
    }
    val runs = 20
    val full = LongArray(runs)
    val front = LongArray(runs)
    var steps = 0
    var frames = 0
    for (r in 0 until runs) {
      var t = System.nanoTime()
      steps = feed(det)
      full[r] = System.nanoTime() - t
      t = System.nanoTime()
      frames = frontendOnly()
      front[r] = System.nanoTime() - t
    }
    full.sort()
    front.sort()
    fun us(total: Long, n: Int) = total / 1000.0 / n
    println(
        "per 10 ms step over $runs runs of $steps steps: full median %.1f us (min %.1f, max %.1f); front end alone median %.1f us (min %.1f)"
            .format(us(full[runs / 2], steps), us(full[0], steps), us(full[runs - 1], steps), us(front[runs / 2], frames), us(front[0], frames)))
  }

  private companion object {
    const val STEP = 160
  }
}
