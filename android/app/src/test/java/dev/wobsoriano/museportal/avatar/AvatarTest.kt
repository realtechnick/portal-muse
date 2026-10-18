package dev.wobsoriano.museportal.avatar

import dev.wobsoriano.museportal.ui.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarTest {
  private val pixelSet =
      """{"cell": 64, "fps": 12.5, "animations": {"idle": {"frames": 75, "columns": 10, "loop": true},
        "listening": {"frames": 50, "columns": 10, "loop": true}, "thinking": {"frames": 50, "columns": 10, "loop": true},
        "talking": {"frames": 50, "columns": 10, "loop": true}}, "grid": 0.72}"""

  @Test
  fun theSetHeyMusesToolWritesIsRead() {
    val m = AvatarManifest.parse(pixelSet).getOrThrow()
    assertEquals(64, m.cell)
    assertEquals(12.5, m.fps, 0.0)
    assertEquals(0.72, m.grid, 1e-9)
    assertEquals(80L, m.frameMs)
    assertTrue(m.pixelArt)
    val idle = m.animations.getValue("idle")
    assertEquals(Animation(75, 10, true), idle)
    assertEquals(640 to 512, idle.width(64) to idle.height(64))
  }

  @Test
  fun aSetMissingAnAnimationOrWithAbsurdSizesIsRefused() {
    val noTalking = pixelSet.replace("\"talking\"", "\"waving\"")
    assertTrue(AvatarManifest.parse(noTalking).exceptionOrNull()!!.message!!.contains("talking"))
    assertTrue(AvatarManifest.parse(pixelSet.replace("\"cell\": 64", "\"cell\": 0")).isFailure)
    assertTrue(AvatarManifest.parse(pixelSet.replace("\"fps\": 12.5", "\"fps\": 0")).isFailure)
    assertTrue(AvatarManifest.parse(pixelSet.replace("\"frames\": 75", "\"frames\": 0")).isFailure)
    assertTrue(AvatarManifest.parse(pixelSet.replace("\"cell\": 64", "\"cell\": 512").replace("\"frames\": 75", "\"frames\": 2000")).isFailure)
    assertTrue(AvatarManifest.parse("not json").isFailure)
  }

  @Test
  fun aVideoSetIsLargeAndSmooth() {
    val video = pixelSet.replace("\"cell\": 64", "\"cell\": 432").replace("\"fps\": 12.5", "\"fps\": 12")
    val m = AvatarManifest.parse(video).getOrThrow()
    assertEquals(false, m.pixelArt)
    assertEquals(1, m.scaleFor(600))
  }

  @Test
  fun thePixelCharacterIsScaledByAWholeNumber() {
    val m = AvatarManifest.parse(pixelSet).getOrThrow()
    assertEquals(7, m.scaleFor(480))
    assertEquals(1, m.scaleFor(10))
  }

  @Test
  fun framesAreReadAcrossThenDownAndLoop() {
    val a = Animation(frames = 25, columns = 10, loop = true)
    assertEquals(0 to 0, a.origin(64, 0))
    assertEquals(576 to 0, a.origin(64, 9))
    assertEquals(64 to 128, a.origin(64, 21))
    assertEquals(0, a.frameAt(12.5, 0))
    assertEquals(12, a.frameAt(12.5, 1000))
    assertEquals(0, a.frameAt(12.5, 2000))
    assertEquals(24, a.copy(loop = false).frameAt(12.5, 60_000))
  }

  @Test
  fun eachMoodPlaysItsAnimation() {
    assertEquals(
        listOf("idle", "listening", "thinking", "talking", "listening", "idle"),
        listOf(Mood.Ready, Mood.Hearing, Mood.Thinking, Mood.Speaking, Mood.Reply, Mood.Offline).map { it.animation })
  }
}
