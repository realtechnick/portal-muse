package dev.wobsoriano.museportal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.core.CoreState
import dev.wobsoriano.museportal.protocol.PairState
import dev.wobsoriano.museportal.protocol.Phase

class MainActivity : ComponentActivity() {
  private val permissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { muse.refreshConfig() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MuseService.start(this)
    applyExtras(intent)
    val missing =
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION).filter {
          checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418), contentColor = Color(0xFFF2F4F6)) {
          val state by muse.state.collectAsStateWithLifecycle()
          MuseScreen(state, muse)
        }
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    applyExtras(intent)
  }

  // Lets adb set the token without the Portal's on-screen keyboard, and ask without the mic:
  // `am start -n .../.MainActivity --es sdkToken mgst_... --es ask "what time is it"`.
  private fun applyExtras(intent: Intent?) {
    val name = intent?.getStringExtra("name")
    val token = intent?.getStringExtra("sdkToken")
    if (name != null || token != null) muse.saveSettings(name ?: muse.state.value.name, token)
    intent?.getStringExtra("ask")?.let(muse::ask)
  }
}

private val Big = 30.sp
private val Huge = 44.sp

@Composable
private fun MuseScreen(state: MuseState, muse: MuseController) {
  Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
    Column(
        Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(28.dp)) {
          Text("Muse", fontSize = 56.sp, fontWeight = FontWeight.Bold)
          val (status, color) = statusLine(state)
          Text(status, fontSize = Big, color = color)
          PairingPanel(state, muse)
          SettingsPanel(state, muse)
        }
    Column(
        Modifier.weight(1f).fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
          MicButton(state.ask, enabled = state.core == CoreState.Running, onTap = muse::tapMic)
          Spacer(Modifier.height(36.dp))
          Box(Modifier.height(300.dp), contentAlignment = Alignment.TopCenter) { AskText(state.ask, state.canSpeak) }
        }
  }
}

private fun statusLine(state: MuseState): Pair<String, Color> {
  val ok = Color(0xFF7BD88F)
  val warn = Color(0xFFFFC66D)
  val bad = Color(0xFFFF6B6B)
  return when (val core = state.core) {
    is CoreState.Missing -> "musecore is not in this build" to bad
    is CoreState.Restarting -> "Muse stopped (exit ${core.exitCode}), restarting in ${core.inMs / 1000} s" to warn
    CoreState.Stopped -> "Stopped" to warn
    CoreState.Starting -> "Starting" to warn
    CoreState.Running ->
        when (state.connection?.phase) {
          null -> "Starting" to warn
          Phase.Unpaired -> "Not paired yet" to warn
          Phase.Connecting -> "Connecting to Muse" to warn
          Phase.Online -> "Online" to ok
          Phase.Offline -> "Offline: ${state.connection.error.ifEmpty { "no connection" }}" to bad
        }
  }
}

@Composable
private fun PairingPanel(state: MuseState, muse: MuseController) {
  val pairing = state.pairing
  val gadget = pairing?.name?.ifEmpty { null }
  val (text, open) =
      when (pairing?.state) {
        PairState.Open -> "In the Muse app, add ${gadget ?: "this device"}" to true
        PairState.Connected -> "Phone connected to ${gadget ?: "this device"}" to true
        PairState.Paired -> "Paired as ${gadget ?: state.name}" to false
        PairState.Failed -> "Pairing failed: ${pairing.error}" to false
        PairState.Closed,
        null ->
            (if (state.hasToken) "Pair to add this Portal to the Muse app"
            else "Enter your SDK token below to pair") to false
      }
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    Text(text, fontSize = Big)
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
      val running = state.core == CoreState.Running
      if (open) {
        BigButton("Stop pairing", enabled = running) { muse.stopPairing() }
      } else {
        BigButton("Pair", enabled = running && state.hasToken) { muse.pair() }
      }
      val paired = state.connection != null && state.connection.phase != Phase.Unpaired
      if (paired && !open) OutlinedButton(onClick = { muse.unpair() }, enabled = running) {
        Text("Unpair", fontSize = Big)
      }
    }
  }
}

@Composable
private fun SettingsPanel(state: MuseState, muse: MuseController) {
  var name by remember(state.name) { mutableStateOf(state.name) }
  var token by remember { mutableStateOf("") }
  Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Device name") },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        value = token,
        onValueChange = { token = it },
        label = { Text(if (state.hasToken) "SDK token (saved)" else "SDK token") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth())
    BigButton("Save", enabled = name.isNotBlank()) {
      muse.saveSettings(name, token.ifBlank { null })
      token = ""
    }
  }
}

@Composable
private fun BigButton(label: String, enabled: Boolean, onClick: () -> Unit) {
  Button(onClick = onClick, enabled = enabled, contentPadding = ButtonDefaults.ContentPadding) {
    Text(label, fontSize = Big, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
  }
}

@Composable
private fun MicButton(ask: AskState, enabled: Boolean, onTap: () -> Unit) {
  val recording = ask is AskState.Recording
  val pulse = if (ask is AskState.Recording) 1f + ask.level * 0.15f else 1f
  Box(contentAlignment = Alignment.Center) {
    Box(
        Modifier.size(300.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(if (recording) Color(0x55FF6B6B) else Color(0x332D9CDB)))
    Button(
        onClick = onTap,
        enabled = enabled,
        shape = CircleShape,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (recording) Color(0xFFE5484D) else Color(0xFF2D9CDB),
                contentColor = Color.White),
        modifier = Modifier.size(240.dp)) {
          Text(if (recording) "Stop" else "Ask", fontSize = Huge, fontWeight = FontWeight.Bold)
        }
  }
}

@Composable
private fun AskText(ask: AskState, canSpeak: Boolean) {
  val dim = Color(0xFFB0B8C0)
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
    when (ask) {
      AskState.Idle -> Text("Tap and ask a question", fontSize = Big, color = dim)
      is AskState.Recording -> Text("Listening", fontSize = Big)
      is AskState.Sending -> Text("Thinking", fontSize = Big, color = dim)
      is AskState.Heard -> {
        Text("“${ask.heard}”", fontSize = Big, color = dim, textAlign = TextAlign.Center)
        Text("Thinking", fontSize = Big, color = dim)
      }
      is AskState.Answered -> {
        if (ask.heard.isNotEmpty()) {
          Text("“${ask.heard}”", fontSize = Big, color = dim, textAlign = TextAlign.Center)
        }
        Text(ask.answer, fontSize = 36.sp, textAlign = TextAlign.Center)
        if (ask.complete && !canSpeak) Text("(no text-to-speech on this device)", fontSize = 20.sp, color = dim)
      }
      is AskState.Failed -> Text(ask.reason, fontSize = Big, color = Color(0xFFFF6B6B), textAlign = TextAlign.Center)
    }
  }
}
