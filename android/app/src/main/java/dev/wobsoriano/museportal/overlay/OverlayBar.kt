package dev.wobsoriano.museportal.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.wobsoriano.museportal.MainActivity
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.avatar.AvatarLoad
import dev.wobsoriano.museportal.ui.Character
import dev.wobsoriano.museportal.ui.MuseTheme
import dev.wobsoriano.museportal.ui.Palette
import dev.wobsoriano.museportal.ui.level
import dev.wobsoriano.museportal.ui.mood
import dev.wobsoriano.museportal.ui.statusLine
import dev.wobsoriano.museportal.ui.turnText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The slim bar a turn shows over whatever is in front, usually Immortal's home screen or photo frame,
 * when our own screen isn't. It needs "display over other apps"; without it nothing shows.
 */
class OverlayBar(private val context: Context, private val state: StateFlow<MuseState>, private val avatar: StateFlow<AvatarLoad>) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val windows = context.getSystemService(WindowManager::class.java)
  private val shown = MutableTransitionState(false)
  private var view: ComposeView? = null
  private var owner: Owner? = null

  fun start() {
    scope.launch {
      state.map { overlayWanted(it.ask, it.speaking, it.inFront) to it.inFront }.distinctUntilChanged().collect { (wanted, inFront) ->
        if (wanted) show()
        else if (inFront) remove()
        else shown.targetState = false
      }
    }
  }

  fun stop() {
    scope.cancel()
    remove()
  }

  private fun show() {
    if (!Settings.canDrawOverlays(context)) return
    if (view == null) attach()
    shown.targetState = true
  }

  private fun attach() {
    val o = Owner().also { owner = it }
    val v =
        ComposeView(context).apply {
          setViewTreeLifecycleOwner(o)
          setViewTreeSavedStateRegistryOwner(o)
          setContent { MuseTheme { Bar() } }
        }
    val params =
        WindowManager.LayoutParams(
                ((BAR_WIDTH_DP + 32) * context.resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
    try {
      windows.addView(v, params)
      view = v
    } catch (e: RuntimeException) {
      Log.w(TAG, "could not show the overlay", e)
      o.destroy()
      owner = null
    }
  }

  /** Takes the window away; called once the bar has slid out, or at once when our screen comes to the front. */
  private fun remove() {
    shown.targetState = false
    view?.let { runCatching { windows.removeView(it) } }
    view = null
    owner?.destroy()
    owner = null
  }

  @Composable
  private fun Bar() {
    val s by state.collectAsStateWithLifecycle()
    val load by avatar.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
      // Lingers so the end of an answer, or a failure, can be read before it slides away.
      snapshotFlow { shown.targetState }.collect { target ->
        if (!target) {
          delay(LINGER_MS)
          if (!shown.targetState) {
            snapshotFlow { shown.isIdle }.first { it }
            if (!shown.targetState) remove()
          }
        }
      }
    }
    AnimatedVisibility(shown, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
      val turn = s.ask.turnText
      Box(Modifier.padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Row(
            Modifier.width(BAR_WIDTH_DP.dp)
                .heightIn(min = 136.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Palette.Card.copy(alpha = 0.97f))
                .border(1.dp, Palette.Line, RoundedCornerShape(32.dp))
                .clickable { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
              Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                Character(s.mood, s.level, (load as? AvatarLoad.Loaded)?.set, 128.dp)
              }
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val heard = turn?.heard.orEmpty()
                Text(
                    if (heard.isNotEmpty()) "“$heard”" else s.statusLine.ifEmpty { "Muse" },
                    style = if (heard.isNotEmpty()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
                    color = if (heard.isNotEmpty()) Palette.Dim else Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                val answer = turn?.answer.orEmpty()
                if (answer.isNotEmpty()) {
                  Text(
                      answer,
                      style = MaterialTheme.typography.titleLarge,
                      color = if (turn?.failed == true) Palette.Bad else Palette.Text,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis)
                } else if (heard.isNotEmpty()) {
                  Text(s.statusLine, style = MaterialTheme.typography.titleLarge)
                }
              }
            }
      }
    }
  }

  /** What Compose needs from a window that has no activity behind it. */
  private class Owner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle
      get() = registry

    override val savedStateRegistry
      get() = saved.savedStateRegistry

    init {
      saved.performRestore(null)
      registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
      registry.currentState = Lifecycle.State.DESTROYED
    }
  }

  private companion object {
    const val TAG = "MuseOverlay"
    const val BAR_WIDTH_DP = 880
  }
}
