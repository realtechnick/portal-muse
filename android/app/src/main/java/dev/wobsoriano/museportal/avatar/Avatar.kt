package dev.wobsoriano.museportal.avatar

import dev.wobsoriano.museportal.ui.Mood
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Muse character's animations, as hey-muse's tools/muse-avatar writes them: manifest.json beside
 * one PNG sheet per animation. The character is Meta's and not ours to ship, so a set is only ever
 * read from the device, never bundled.
 */
data class AvatarManifest(
    /** A frame's width and height, in pixels. */
    val cell: Int,
    val fps: Double,
    /** How bright each scaled pixel's last row and column are drawn, 0 to 1; 0 or 1 is no grid. */
    val grid: Double,
    val animations: Map<String, Animation>,
) {
  val frameMs: Long
    get() = (1000 / fps).toLong().coerceAtLeast(1)

  /** The pixel character is drawn crisp, whole pixels at a time; a set made from video is smooth. */
  val pixelArt: Boolean
    get() = cell <= PIXEL_ART_MAX

  /** The largest whole-number scale that fits [side] pixels. */
  fun scaleFor(side: Int): Int = maxOf(1, side / cell)

  companion object {
    const val IDLE = "idle"
    const val LISTENING = "listening"
    const val THINKING = "thinking"
    const val TALKING = "talking"
    val REQUIRED = listOf(IDLE, LISTENING, THINKING, TALKING)

    private const val MAX_CELL = 512
    private const val MAX_FRAMES = 2000
    private const val MAX_FPS = 60.0
    private const val PIXEL_ART_MAX = 128
    /** Pixels across all sheets: room for four six-second video clips at 432 px, about 256 MB decoded. */
    const val MAX_PIXELS = 64L shl 20

    /** Reads a manifest and refuses one the player could not trust, as hey-muse's player does. */
    fun parse(text: String): Result<AvatarManifest> = runCatching {
      val root = Json.parseToJsonElement(text).jsonObject
      val cell = root.int("cell")
      val fps = root["fps"]?.jsonPrimitive?.doubleOrNull ?: error("no fps")
      val grid = root["grid"]?.jsonPrimitive?.doubleOrNull ?: 0.0
      require(cell in 1..MAX_CELL) { "a cell of $cell pixels" }
      require(fps > 0 && fps <= MAX_FPS) { "$fps frames a second" }
      require(grid in 0.0..1.0) { "a grid of $grid" }
      val animations =
          (root["animations"] as? JsonObject ?: error("no animations")).mapValues { (name, value) ->
            require(name.matches(Regex("[a-z0-9_-]{1,32}"))) { "an animation named $name" }
            val a = value.jsonObject
            Animation(a.int("frames"), a.int("columns"), a["loop"]?.jsonPrimitive?.booleanOrNull ?: true).also {
              require(it.frames in 1..MAX_FRAMES && it.columns >= 1) { "$name has ${it.frames} frames in rows of ${it.columns}" }
            }
          }
      REQUIRED.forEach { require(it in animations) { "no \"$it\" animation" } }
      val pixels = animations.values.sumOf { it.width(cell).toLong() * it.height(cell) }
      require(pixels <= MAX_PIXELS) { "the sheets are too large" }
      AvatarManifest(cell, fps, grid, animations)
    }

    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: error("no $key")
  }
}

/** One sheet: [frames] frames, [columns] to a row, left to right and then down. */
data class Animation(val frames: Int, val columns: Int, val loop: Boolean) {
  val rows: Int
    get() = (frames + columns - 1) / columns

  fun width(cell: Int) = columns * cell

  fun height(cell: Int) = rows * cell

  /** The top-left corner of frame [i] in the sheet. */
  fun origin(cell: Int, i: Int) = (i % columns) * cell to (i / columns) * cell

  /** The frame showing [elapsedMs] into the animation: around again when it loops, held on the last when not. */
  fun frameAt(fps: Double, elapsedMs: Long): Int {
    val i = (elapsedMs * fps / 1000).toLong().coerceAtLeast(0)
    return if (loop) (i % frames).toInt() else minOf(i, frames - 1L).toInt()
  }
}

/** Which animation the character plays for what the screen shows. */
val Mood.animation: String
  get() =
      when (this) {
        Mood.Hearing,
        Mood.Reply -> AvatarManifest.LISTENING
        Mood.Thinking -> AvatarManifest.THINKING
        Mood.Speaking -> AvatarManifest.TALKING
        Mood.Ready,
        Mood.Offline -> AvatarManifest.IDLE
      }
