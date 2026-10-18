package dev.wobsoriano.museportal.wake

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private val resources = File(checkNotNull(object {}.javaClass.getResource("/hey_muse.tflite")).toURI()).parentFile

/** A file from zserge/microwakeword's testdata. */
fun testdata(name: String): String = File(resources, "testdata/$name").path

/** A file under the test resources. */
fun resource(name: String): File = File(resources, name)

/** The library tests' WAV loader: skips a 44-byte header and reads the rest as PCM16. */
fun loadTestWav(path: String): ShortArray {
  val data = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
  return ShortArray((data.capacity() - 44) / 2) { data.getShort(44 + it * 2) }
}

/** Reads the golden features file: uint32 frameCount, uint32 featureSize, then int8 features. */
fun loadFeatures(path: String): Array<ByteArray> {
  val data = ByteBuffer.wrap(File(path).readBytes()).order(ByteOrder.LITTLE_ENDIAN)
  val frameCount = data.getInt(0)
  val featureSize = data.getInt(4)
  return Array(frameCount) { i -> ByteArray(featureSize) { j -> data.get(8 + i * featureSize + j) } }
}
