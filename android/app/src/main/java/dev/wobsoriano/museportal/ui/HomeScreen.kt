package dev.wobsoriano.museportal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.avatar.AvatarSet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the turn has to say: the question as heard and the answer so far, or why it failed. */
data class TurnText(val heard: String, val answer: String, val failed: Boolean, val complete: Boolean = true)

val AskState.turnText: TurnText?
  get() =
      when (this) {
        is AskState.Heard -> TurnText(heard, "", false, complete = false)
        is AskState.Answered -> TurnText(heard, answer, false, complete).takeIf { heard.isNotEmpty() || answer.isNotEmpty() }
        is AskState.Failed -> TurnText("", reason, true)
        else -> null
      }

/**
 * The screen a room sees: the character (or the orb) in the middle, one line beneath it, and the turn's
 * words under that. Tapping the character asks; the rest of the screen ignores taps, so waking the
 * screen or brushing it doesn't start a recording.
 */
@Composable
fun HomeScreen(
    state: MuseState,
    avatar: AvatarSet?,
    onTap: () -> Unit,
    onSettings: () -> Unit,
    onDismissImage: () -> Unit = {},
    onReplayEntry: (Long) -> Unit = {},
    onToggleMute: () -> Unit = {},
) {
  val mood = state.mood
  var showReplays by remember { mutableStateOf(false) }
  Box(Modifier.fillMaxSize()) {
        val pushed = state.shownImage
        if (pushed != null) {
          // An image Muse pushed covers the room screen until tapped away or replaced.
          Box(Modifier.fillMaxSize().background(Color.Black).clickable { onDismissImage() }, contentAlignment = Alignment.Center) {
            Image(pushed.asImageBitmap(), contentDescription = "Pushed image", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
          }
        } else {
        val turn = state.ask.turnText
        val side = if (avatar == null && turn != null) 260.dp else 440.dp
        Column(
            Modifier.fillMaxSize().padding(top = 72.dp, bottom = 32.dp, start = 64.dp, end = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
              val dim by animateFloatAsState(if (mood == Mood.Offline) 0.45f else 1f, tween(600), label = "offline")
              Box(
                  Modifier.size(side)
                      .alpha(dim)
                      .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
                  contentAlignment = Alignment.Center) {
                    Character(mood, state.level, avatar, side)
                  }
              Spacer(Modifier.height(16.dp))
              AnimatedContent(
                  targetState = Line(state.statusLine, turn, state.spokenRange),
                  transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) },
                  label = "turn",
                  // Streaming snapshots and per-word ranges must not retrigger the crossfade —
                  // that was the blinking. Only a new phase or a new question fades.
                  contentKey = { it.status to (it.turn?.heard ?: "") }) { line ->
                TurnWords(line, captionOnly = avatar != null)
              }
              val left = state.recordingLeftS
              if (left != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Recording… ${left / 60}:${(left % 60).toString().padStart(2, '0')} left · tap to send",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Warn,
                    textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                CapLine(state.recordingFraction ?: 0f)
              }
            }
        }
        // Drawn last so the buttons stay reachable while a pushed announcement covers the screen.
        TopBar(state, onSettings, onReplay = { showReplays = true }, onToggleMute = onToggleMute)
      }
  if (showReplays) {
    ReplayDialog(state, onDismiss = { showReplays = false }, onReplayEntry = { id ->
      showReplays = false
      onReplayEntry(id)
    })
  }
}

/** The last few announcements, newest first; tapping one shows and plays it again. */
@Composable
private fun ReplayDialog(state: MuseState, onDismiss: () -> Unit, onReplayEntry: (Long) -> Unit) {
  val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.US) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Recent announcements") },
      text = {
        if (state.announcements.isEmpty()) {
          Text("No announcements yet.")
        } else {
          Column {
            state.announcements.forEach { a ->
              val kind = when {
                a.hasCard && a.hasAudio -> "Card + audio"
                a.hasCard -> "Card"
                else -> "Audio"
              }
              Row(
                  Modifier.fillMaxWidth().clickable { onReplayEntry(a.id) }.padding(vertical = 14.dp),
                  verticalAlignment = Alignment.CenterVertically) {
                    Text("${timeFormat.format(Date(a.atMs))} · $kind", style = MaterialTheme.typography.bodyLarge)
                  }
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

private data class Line(val status: String, val turn: TurnText?, val range: IntRange? = null)

/** Chars of answer per caption page on the avatar layout: about four lines at caption size. */
private const val CAPTION_PAGE_CHARS = 352

/** Chars per page on the large-text layout, where lines hold fewer, bigger characters. */
private const val FULL_PAGE_CHARS = 480

/** One page of a long answer, with the spoken word's span inside it when there is one. */
private data class CaptionPage(val text: String, val highlight: IntRange?)

/**
 * Pages a long answer on word breaks, each page up to [charsPerPage] chars, and returns the page
 * holding [focus] (a char index). While the answer streams in, focus sits at 0: the first page
 * fills and holds instead of the text racing past. While it is spoken, focus is the spoken word,
 * so a page holds until the voice reaches its last word and then flips to the next page whole —
 * no sliding, no half-scrolled lines.
 */
private fun captionPage(text: String, focus: Int, highlight: IntRange?, charsPerPage: Int): CaptionPage {
  if (text.isEmpty()) return CaptionPage(text, null)
  if (text.length <= charsPerPage) {
    val hl =
        highlight?.let {
          val s = it.first.coerceIn(0, text.length - 1)
          val e = it.last.coerceIn(0, text.length - 1)
          if (e < s) null else s..e
        }
    return CaptionPage(text, hl)
  }
  val f = focus.coerceIn(0, text.length)
  var pageStart = 0
  while (true) {
    var pageEnd = minOf(text.length, pageStart + charsPerPage)
    if (pageEnd < text.length) {
      val breakAt = text.lastIndexOf(' ', pageEnd - 1)
      if (breakAt > pageStart) pageEnd = breakAt
    }
    if (f in pageStart..pageEnd || pageEnd == text.length) {
      val pageLength = pageEnd - pageStart
      val hl =
          highlight?.let {
            val s = it.first - pageStart
            val e = it.last - pageStart
            if (e < 0 || s > pageLength - 1) null
            else s.coerceIn(0, pageLength - 1)..e.coerceIn(0, pageLength - 1)
          }
      return CaptionPage(text.substring(pageStart, pageEnd), hl)
    }
    pageStart = pageEnd + 1 // step over the break space
  }
}

/** The recording time left as a line: full and green at the start, shrinking to the center and reddening. */
@Composable
private fun CapLine(fraction: Float) {
  val shown by animateFloatAsState(fraction, tween(160, easing = LinearEasing), label = "cap")
  Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
        Modifier.fillMaxWidth(shown.coerceIn(0f, 1f))
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(lerp(Palette.Bad, Palette.Good, shown)))
  }
}

@Composable
private fun TurnWords(line: Line, captionOnly: Boolean) {
  Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val turn = line.turn
    if (turn != null && turn.heard.isNotEmpty()) {
      Text("“${turn.heard}”", style = MaterialTheme.typography.bodyLarge, color = Palette.Faint, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (line.status.isNotEmpty() && (turn == null || turn.answer.isEmpty())) {
      Text(line.status, style = MaterialTheme.typography.titleLarge, color = Palette.Dim, textAlign = TextAlign.Center)
    }
    if (turn != null && turn.answer.isNotEmpty()) {
      val size = if (captionOnly) 26.sp else if (turn.answer.length > 160) 30.sp else 38.sp
      val color = if (turn.failed) Palette.Bad else if (captionOnly) Palette.Dim else Palette.Text
      val maxLines = if (captionOnly) 5 else 6
      val range = line.range?.takeIf { !turn.complete || it.first in turn.answer.indices }
      if (!turn.complete || range != null) {
        // In progress: show the page holding the spoken word (page one while the answer is
        // still streaming in), with the word being spoken lit up when the engine reports one.
        val page =
            captionPage(
                turn.answer,
                focus = range?.first ?: 0,
                highlight = range,
                charsPerPage = if (captionOnly) CAPTION_PAGE_CHARS else FULL_PAGE_CHARS)
        val annotated =
            buildAnnotatedString {
              append(page.text)
              page.highlight?.let {
                addStyle(
                    SpanStyle(color = Palette.Accent, fontWeight = FontWeight.Bold),
                    it.first.coerceIn(0, page.text.length),
                    minOf(it.last + 1, page.text.length))
              }
            }
        Text(
            annotated,
            fontSize = size,
            lineHeight = size * 1.3f,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis)
      } else {
        Text(
            turn.answer,
            fontSize = size,
            lineHeight = size * 1.3f,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
private fun TopBar(state: MuseState, onSettings: () -> Unit, onReplay: () -> Unit, onToggleMute: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
    val online = state.online
    val dot by animateColorAsState(if (online) Palette.Good else Palette.Warn, label = "dot")
    Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
    Spacer(Modifier.size(14.dp))
    Text(state.connectionLabel, style = MaterialTheme.typography.bodyLarge, color = if (online) Palette.Faint else Palette.Warn)
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onReplay, modifier = Modifier.size(64.dp)) {
      Icon(Icons.Filled.Replay, contentDescription = "Replay an announcement", tint = Palette.Dim, modifier = Modifier.size(36.dp))
    }
    IconButton(onClick = onToggleMute, modifier = Modifier.size(64.dp)) {
      Icon(
          if (state.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
          contentDescription = if (state.muted) "Unmute" else "Mute",
          tint = Palette.Dim,
          modifier = Modifier.size(36.dp))
    }
    IconButton(onClick = onSettings, modifier = Modifier.size(64.dp)) {
      Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.Dim, modifier = Modifier.size(36.dp))
    }
  }
}
