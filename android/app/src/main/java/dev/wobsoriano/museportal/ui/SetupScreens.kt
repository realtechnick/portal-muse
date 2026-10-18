package dev.wobsoriano.museportal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.wobsoriano.muse.core.PairState
import dev.wobsoriano.museportal.CoreState
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.avatar.AvatarSet
import dev.wobsoriano.museportal.heymuse.WakeState
import dev.wobsoriano.museportal.setup.SetupStep

/** What the setup screens can ask of the app; a preview hands in one that changes nothing. */
interface SetupActions {
  fun saveToken(token: String)

  fun rename(name: String)

  fun pair()

  fun setWakeEnabled(on: Boolean)

  fun requestOverlay()

  fun finish()
}

@Composable
fun SetupScreen(step: SetupStep, state: MuseState, canOverlay: Boolean, avatar: AvatarSet?, actions: SetupActions) {
  val mood =
      when {
        state.pairing?.state == PairState.Connected && step == SetupStep.Pair -> Mood.Thinking
        state.speaking -> Mood.Speaking
        else -> Mood.Ready
      }
  Row(Modifier.fillMaxSize()) {
    Box(Modifier.weight(0.36f).fillMaxHeight(), contentAlignment = Alignment.Center) { Character(mood, 0f, avatar, 320.dp) }
    AnimatedContent(
        step,
        transitionSpec = { (slideInHorizontally { it / 8 } + fadeIn()) togetherWith fadeOut() },
        modifier = Modifier.weight(0.64f),
        label = "setup step") { s ->
          StepFrame(s) {
            when (s) {
              SetupStep.Token -> TokenStep(state, actions)
              SetupStep.Name -> NameStep(state, actions)
              SetupStep.Pair -> PairStep(state, actions)
              SetupStep.HeyMuse -> HeyMuseStep(state, canOverlay, actions)
            }
          }
        }
  }
}

private val SetupStep.title
  get() =
      when (this) {
        SetupStep.Token -> "Connect your Muse account"
        SetupStep.Name -> "Name this Portal"
        SetupStep.Pair -> "Pair with the Muse app"
        SetupStep.HeyMuse -> "Ask hands-free"
      }

private val SetupStep.sentence
  get() =
      when (this) {
        SetupStep.Token -> "Muse needs your developer token to add this Portal to your account."
        SetupStep.Name -> "This is the name you'll see for it in the Muse app on your phone."
        SetupStep.Pair -> "Your phone adds this Portal to your Muse account over Bluetooth."
        SetupStep.HeyMuse -> "Say “Hey Muse” from across the room instead of walking over to tap."
      }

@Composable
private fun StepFrame(step: SetupStep, content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.CenterStart) {
    Column(Modifier.widthIn(max = 700.dp).fillMaxWidth().padding(start = 8.dp, end = 64.dp, top = 72.dp, bottom = 56.dp)) {
      StepDots(step)
      Spacer(Modifier.height(28.dp))
      Text(step.title, style = MaterialTheme.typography.displaySmall)
      Spacer(Modifier.height(12.dp))
      Text(step.sentence, style = MaterialTheme.typography.bodyLarge, color = Palette.Dim)
      Spacer(Modifier.height(40.dp))
      content()
    }
  }
}

@Composable
private fun StepDots(step: SetupStep) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    SetupStep.entries.forEach { s ->
      val on = s.ordinal <= step.ordinal
      Box(Modifier.height(8.dp).size(width = if (s == step) 36.dp else 8.dp, height = 8.dp).clip(CircleShape).background(if (on) Palette.Accent else Palette.Line))
    }
    Spacer(Modifier.size(8.dp))
    Text("Step ${step.ordinal + 1} of ${SetupStep.entries.size}", style = MaterialTheme.typography.bodyMedium, color = Palette.Faint)
  }
}

@Composable
private fun TokenStep(state: MuseState, actions: SetupActions) {
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    Text(
        "Create one at gadgets.muse.ai → Settings → SDK tokens, then type it here.",
        style = MaterialTheme.typography.bodyLarge)
    TokenField(state, actions::saveToken)
  }
}

@Composable
private fun NameStep(state: MuseState, actions: SetupActions) {
  var name by remember { mutableStateOf(state.name) }
  Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        label = { Text("Name") },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) actions.rename(name) }),
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Line),
        modifier = Modifier.fillMaxWidth())
    Pill("Continue", { actions.rename(name) }, enabled = name.isNotBlank())
  }
}

@Composable
private fun PairStep(state: MuseState, actions: SetupActions) {
  val running = state.core == CoreState.Running
  val pairing = state.pairing?.state
  LaunchedEffect(running) {
    if (running && pairing != PairState.Open && pairing != PairState.Connected) actions.pair()
  }
  val gadget = state.pairing?.name?.ifEmpty { null }
  Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
    Column(Modifier.clip(RoundedCornerShape(24.dp)).background(Palette.Card).padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
      Instruction(1, "Open Muse on your phone")
      Instruction(2, "Go to Devices → Add device")
      Instruction(3, if (gadget != null) "Pick $gadget" else "Pick the MuseGadget it lists")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      when (pairing) {
        PairState.Paired -> PairLine("✓ Paired", Palette.Good)
        PairState.Connected -> {
          Spinner()
          PairLine("Phone connected, finishing up…", Palette.Text)
        }
        PairState.Failed -> {
          PairLine("Pairing didn't finish. ${state.pairing.error.substringBefore(':').ifEmpty { "" }}".trim(), Palette.Bad, Modifier.weight(1f, fill = false))
          Pill("Try again", actions::pair)
        }
        PairState.Closed -> {
          PairLine("Pairing timed out.", Palette.Warn)
          Pill("Try again", actions::pair)
        }
        PairState.Open,
        null -> {
          Spinner()
          PairLine(if (pairing == null) "Getting ready…" else "Waiting for your phone…", Palette.Dim)
        }
      }
    }
  }
}

@Composable
private fun Instruction(n: Int, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.Raised), contentAlignment = Alignment.Center) {
      Text("$n", style = MaterialTheme.typography.titleLarge, color = Palette.Accent)
    }
    Text(text, style = MaterialTheme.typography.titleLarge)
  }
}

@Composable
private fun PairLine(text: String, color: Color, modifier: Modifier = Modifier) {
  Text(text, modifier, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Medium)
}

@Composable
private fun Spinner() = CircularProgressIndicator(Modifier.size(32.dp), color = Palette.Accent, strokeWidth = 3.dp)

/** Shared by setup and Settings: what turning on "Hey Muse" means, and how it is coming along. */
@Composable
fun HeyMuseSwitch(state: MuseState, onChange: (Boolean) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SwitchRow(
        "“Hey Muse”",
        "Downloads a 40 MB speech model once. Listening happens on this Portal; only what you say after “Hey Muse” goes to Muse.",
        state.wakeEnabled,
        onChange)
    if (state.wakeEnabled) WakeProgress(state.wake)
  }
}

@Composable
private fun WakeProgress(wake: WakeState) {
  when (wake) {
    is WakeState.Downloading -> {
      Text(
          "Downloading the speech model" + (wake.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "…"),
          style = MaterialTheme.typography.bodyLarge,
          color = Palette.Dim)
      val bar = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape)
      if (wake.fraction == null) LinearProgressIndicator(bar, color = Palette.Accent, trackColor = Palette.Raised)
      else LinearProgressIndicator({ wake.fraction }, bar, color = Palette.Accent, trackColor = Palette.Raised)
    }
    WakeState.Loading -> Text("Loading the speech model…", style = MaterialTheme.typography.bodyLarge, color = Palette.Dim)
    WakeState.Listening,
    WakeState.Delegated,
    WakeState.Asking -> Text("Ready. Try it: say “Hey Muse”", style = MaterialTheme.typography.titleLarge, color = Palette.Good)
    WakeState.Yielded -> Text("Paused while another app uses the microphone.", style = MaterialTheme.typography.bodyLarge, color = Palette.Warn)
    is WakeState.Failed -> Text("${wake.reason}. Turn it off and on to try again.", style = MaterialTheme.typography.bodyLarge, color = Palette.Bad)
    WakeState.Off -> Unit
  }
}

/** Offers the system's "display over other apps" page, which lets answers show over Immortal. */
@Composable
fun OverlayOffer(canOverlay: Boolean, onRequest: () -> Unit, boxed: Boolean = true) {
  if (canOverlay) return
  Row(
      if (boxed) Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.Card).padding(24.dp) else Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Show answers over other apps", style = MaterialTheme.typography.titleLarge)
          Text("When you ask from the home screen or photo frame, a slim bar shows the answer.", style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
        }
        Pill("Allow", onRequest, primary = false)
      }
}

@Composable
private fun HeyMuseStep(state: MuseState, canOverlay: Boolean, actions: SetupActions) {
  Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
    HeyMuseSwitch(state, actions::setWakeEnabled)
    OverlayOffer(canOverlay, actions::requestOverlay)
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
      Pill(if (state.wakeEnabled) "Done" else "Skip for now", actions::finish, primary = state.wakeEnabled)
    }
  }
}
