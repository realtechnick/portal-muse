package dev.wobsoriano.museportal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.wobsoriano.museportal.avatar.AvatarSet
import dev.wobsoriano.museportal.avatar.animation
import kotlin.math.PI
import kotlin.math.sin

/** Muse's character when a set is installed, otherwise the orb; [side] is the square it may fill. */
@Composable
fun Character(mood: Mood, level: Float, avatar: AvatarSet?, side: Dp, modifier: Modifier = Modifier) {
  if (avatar != null) Sprite(avatar, mood, side, modifier) else Orb(mood, level, side, modifier)
}

/**
 * Plays the mood's animation at the manifest's rate, scaled by a whole number. The pixel character is
 * drawn with no smoothing, and with hey-muse's grid: each big pixel's last row and column darkened.
 */
@Composable
private fun Sprite(avatar: AvatarSet, mood: Mood, side: Dp, modifier: Modifier) {
  val m = avatar.manifest
  val name = mood.animation
  val anim = m.animations.getValue(name)
  val sheet = avatar.sheets.getValue(name)
  val px = with(LocalDensity.current) { side.roundToPx() }
  val scale = m.scaleFor(px)
  val drawn = m.cell * scale
  var frame by remember(name) { mutableLongStateOf(0L) }
  LaunchedEffect(name) {
    val start = withFrameMillis { it }
    while (true) {
      val now = withFrameMillis { it }
      frame = anim.frameAt(m.fps, now - start).toLong()
    }
  }
  val grid = m.pixelArt && scale >= 3 && m.grid > 0 && m.grid < 1
  Canvas(
      modifier.size(with(LocalDensity.current) { drawn.toDp() }).graphicsLayer {
        if (grid) compositingStrategy = CompositingStrategy.Offscreen
      }) {
        val (sx, sy) = anim.origin(m.cell, frame.toInt())
        drawImage(
            sheet,
            srcOffset = IntOffset(sx, sy),
            srcSize = IntSize(m.cell, m.cell),
            dstSize = IntSize(drawn, drawn),
            filterQuality = if (m.pixelArt) FilterQuality.None else FilterQuality.Medium)
        if (grid) {
          val shade = Color.Black.copy(alpha = (1 - m.grid).toFloat())
          for (i in 1..m.cell) {
            val at = (i * scale - 1).toFloat()
            drawRect(shade, Offset(at, 0f), Size(1f, drawn.toFloat()), blendMode = BlendMode.SrcAtop)
            drawRect(shade, Offset(0f, at), Size(drawn.toFloat(), 1f), blendMode = BlendMode.SrcAtop)
          }
        }
      }
}

private fun Mood.color() =
    when (this) {
      Mood.Ready -> Color(0xFF7FA8FF)
      Mood.Hearing -> Color(0xFF5FE0C4)
      Mood.Reply -> Color(0xFF9BE58A)
      Mood.Thinking -> Color(0xFFB08CFF)
      Mood.Speaking -> Color(0xFFFFB86B)
      Mood.Offline -> Color(0xFF59636E)
    }

/** The built-in stand-in: a soft orb that breathes, follows the voice, swirls while thinking and ripples while speaking. */
@Composable
private fun Orb(mood: Mood, level: Float, side: Dp, modifier: Modifier) {
  val color by animateColorAsState(mood.color(), tween(600), label = "orb color")
  val voice by animateFloatAsState(if (mood == Mood.Hearing || mood == Mood.Reply) level else 0f, spring(stiffness = 300f), label = "orb voice")
  val t = rememberInfiniteTransition(label = "orb")
  val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
  val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
  val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wave")
  val calm = mood == Mood.Ready || mood == Mood.Offline
  Canvas(modifier.size(side)) {
    val c = center
    val full = size.minDimension / 2
    val base = full * 0.56f
    val r =
        when (mood) {
          Mood.Hearing,
          Mood.Reply -> base * (1f + 0.22f * voice.coerceIn(0f, 1f))
          Mood.Speaking -> base * (1f + 0.04f * sin(wave * 2 * PI).toFloat())
          Mood.Offline -> base * 0.9f
          else -> base * (1f + 0.035f * breath)
        }
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = if (calm) 0.22f else 0.34f), Color.Transparent), c, full), full, c)
    if (mood == Mood.Speaking) {
      for (k in 0..1) {
        val p = (wave + k * 0.5f) % 1f
        drawCircle(color.copy(alpha = 0.45f * (1 - p)), r + (full - r) * p, c, style = Stroke(width = 3.dp.toPx()))
      }
    }
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f)), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.5f), r, c)
    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent), Offset(c.x - r * 0.35f, c.y - r * 0.4f), r * 0.7f), r, c)
    if (mood == Mood.Thinking) {
      val ring = r + 18.dp.toPx()
      drawArc(
          color.copy(alpha = 0.9f),
          spin,
          110f,
          false,
          Offset(c.x - ring, c.y - ring),
          Size(ring * 2, ring * 2),
          style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
    }
  }
}
