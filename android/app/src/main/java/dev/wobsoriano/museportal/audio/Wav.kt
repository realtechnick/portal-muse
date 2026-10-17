package dev.wobsoriano.museportal.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Wav {
  /** A RIFF/WAVE file around little-endian PCM16 samples. */
  fun wrap(pcm: ByteArray, sampleRate: Int = 16_000, channels: Int = 1): ByteArray {
    val blockAlign = channels * 2
    return ByteBuffer.allocate(44 + pcm.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII))
        .putInt(36 + pcm.size)
        .put("WAVE".toByteArray(Charsets.US_ASCII))
        .put("fmt ".toByteArray(Charsets.US_ASCII))
        .putInt(16)
        .putShort(1)
        .putShort(channels.toShort())
        .putInt(sampleRate)
        .putInt(sampleRate * blockAlign)
        .putShort(blockAlign.toShort())
        .putShort(16)
        .put("data".toByteArray(Charsets.US_ASCII))
        .putInt(pcm.size)
        .put(pcm)
        .array()
  }
}
