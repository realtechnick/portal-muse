package dev.wobsoriano.museportal.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class WavTest {
  @Test
  fun wrapsPcm16MonoAt16kHzInA44ByteHeader() {
    val pcm = byteArrayOf(0x01, 0x00, 0xff.toByte(), 0x7f)
    val header =
        listOf(
                "52 49 46 46", "28 00 00 00", "57 41 56 45", // RIFF, 36 + 4, WAVE
                "66 6d 74 20", "10 00 00 00", "01 00", "01 00", // fmt , 16, PCM, mono
                "80 3e 00 00", "00 7d 00 00", "02 00", "10 00", // 16000 Hz, 32000 B/s, align 2, 16 bit
                "64 61 74 61", "04 00 00 00", // data, 4 bytes
            )
            .joinToString(" ")
            .split(" ")
            .map { it.toInt(16).toByte() }
            .toByteArray()
    assertArrayEquals(header + pcm, Wav.wrap(pcm))
  }
}
